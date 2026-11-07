package com.nebysse.minetomesh.job;

import com.nebysse.minetomesh.dh.DhAvailability;
import com.nebysse.minetomesh.dh.DhConfig;
import com.nebysse.minetomesh.dh.DhLevelResolver;
import com.nebysse.minetomesh.dh.DhLodMesher;
import com.nebysse.minetomesh.dh.DhTerrainAccessor;
import com.nebysse.minetomesh.report.ExportReport;
import com.nebysse.minetomesh.scene.BatchCounters;
import com.nebysse.minetomesh.scene.CapturedNode;
import com.nebysse.minetomesh.scene.ChunkBatch;
import com.nebysse.minetomesh.scene.PrimitiveAccumulator;
import com.nebysse.minetomesh.world.ExportPlan;
import com.nebysse.minetomesh.world.Selection;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DhPipelineHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(DhPipelineHelper.class);
    private static final long MAX_RENDER_THREAD_BUDGET_MILLIS = 500L;
    private static final String LOD_APPROXIMATION_NOTE =
            "LOD geometry is approximate and generated from Distant Horizons terrain data";

    private DhPipelineHelper() {
    }

    public static DhLodResult captureLodChunks(
            ClientLevel level,
            Selection selection,
            List<ExportPlan.MissingChunk> missingChunks,
            com.nebysse.minetomesh.texture.TextureRegistry textures) {
        boolean present = false;
        String apiVersion = null;
        boolean enabled = false;

        try {
            present = DhAvailability.isAvailable();
            apiVersion = present ? DhAvailability.apiVersion() : null;
            enabled = present && DhConfig.isLodFallbackEnabled();
        } catch (Throwable t) {
            LOGGER.warn("Failed checking Distant Horizons availability: {}", t.getMessage());
        }

        int attempted = 0;
        int filled = 0;
        int failed = 0;

        List<ChunkBatch> batches = new ArrayList<>();
        List<ExportPlan.MissingChunk> remainingMissing = new ArrayList<>();

        if (!enabled || missingChunks == null || missingChunks.isEmpty()) {
            if (missingChunks != null) {
                remainingMissing.addAll(missingChunks);
            }
            ExportReport.DhStats stats = new ExportReport.DhStats(
                    present, apiVersion, attempted, filled, failed, LOD_APPROXIMATION_NOTE);
            return new DhLodResult(batches, remainingMissing, stats);
        }

        long startTime = System.currentTimeMillis();

        try {
            String dimensionId = level.dimension().location().toString();
            Optional<IDhApiLevelWrapper> levelWrapperOpt = DhLevelResolver.resolveLevel(dimensionId);
            if (levelWrapperOpt.isEmpty()) {
                remainingMissing.addAll(missingChunks);
                ExportReport.DhStats stats = new ExportReport.DhStats(
                        present, apiVersion, attempted, filled, failed, LOD_APPROXIMATION_NOTE);
                return new DhLodResult(batches, remainingMissing, stats);
            }

            IDhApiLevelWrapper levelWrapper = levelWrapperOpt.get();
            int minHeight = level.getMinBuildHeight();

            if (textures != null) {
                try {
                    textures.register(
                            com.nebysse.minetomesh.dh.DhMaterialResolver.GENERIC_LOD_TEXTURE,
                            com.nebysse.minetomesh.dh.DhMaterialResolver.genericLodTextureImage());
                } catch (Throwable t) {
                    LOGGER.warn("Could not register DH generic LOD texture: {}", t.getMessage());
                }
            }

            try (DhTerrainAccessor accessor = new DhTerrainAccessor(levelWrapper)) {
                for (int i = 0; i < missingChunks.size(); i++) {
                    ExportPlan.MissingChunk missing = missingChunks.get(i);

                    // Budget check: do not block render thread longer than 500ms
                    if (System.currentTimeMillis() - startTime > MAX_RENDER_THREAD_BUDGET_MILLIS) {
                        LOGGER.info("DH LOD fallback reached 500ms budget limit; deferring {} remaining missing chunk(s)",
                                missingChunks.size() - i);
                        for (int rem = i; rem < missingChunks.size(); rem++) {
                            remainingMissing.add(missingChunks.get(rem));
                        }
                        break;
                    }

                    attempted++;
                    try {
                        Optional<DhApiTerrainDataPoint[][][]> dataOpt =
                                accessor.getChunkTerrainData(missing.chunkX(), missing.chunkZ());
                        if (dataOpt.isEmpty()) {
                            failed++;
                            remainingMissing.add(missing);
                            continue;
                        }

                        String nodeName = "c_" + missing.chunkX() + "_" + missing.chunkZ();
                        PrimitiveAccumulator accumulator = new PrimitiveAccumulator(nodeName);
                        DhLodMesher.meshChunk(
                                levelWrapper,
                                missing.chunkX(),
                                missing.chunkZ(),
                                dataOpt.get(),
                                selection,
                                minHeight,
                                accumulator);

                        PrimitiveAccumulator.SealResult sealed = accumulator.seal();
                        if (sealed.primitives().isEmpty()) {
                            failed++;
                            remainingMissing.add(missing);
                        } else {
                            filled++;
                            CapturedNode lodNode = new CapturedNode(
                                    nodeName,
                                    CapturedNode.Kind.DH_LOD,
                                    sealed.primitives(),
                                    Map.of(
                                            "chunkX", missing.chunkX(),
                                            "chunkZ", missing.chunkZ(),
                                            "source", "DistantHorizons"));
                            int triangleCount = sealed.primitives().stream()
                                    .mapToInt(p -> p.vertices().size() / 2)
                                    .sum();
                            BatchCounters counters = new BatchCounters(
                                    256, 0, 0, 0, 0, 0, 0, triangleCount, 0);
                            batches.add(new ChunkBatch(List.of(lodNode), sealed.diagnostics(), counters));
                        }
                    } catch (Throwable chunkError) {
                        failed++;
                        remainingMissing.add(missing);
                        LOGGER.warn("Error processing DH LOD for chunk [{}, {}]: {}",
                                missing.chunkX(), missing.chunkZ(), chunkError.getMessage());
                    }
                }
            }
        } catch (Throwable topLevelError) {
            LOGGER.warn("Uncaught exception in DH LOD pass: {}", topLevelError.getMessage(), topLevelError);
            // Ensure all unhandled missing chunks are retained
            for (ExportPlan.MissingChunk missing : missingChunks) {
                if (!remainingMissing.contains(missing)) {
                    remainingMissing.add(missing);
                }
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        LOGGER.info("DH LOD capture pass completed in {}ms: attempted={}, filled={}, failed={}",
                duration, attempted, filled, failed);

        ExportReport.DhStats stats = new ExportReport.DhStats(
                present, apiVersion, attempted, filled, failed, LOD_APPROXIMATION_NOTE);
        return new DhLodResult(batches, remainingMissing, stats);
    }

    public record DhLodResult(
            List<ChunkBatch> lodBatches,
            List<ExportPlan.MissingChunk> remainingMissing,
            ExportReport.DhStats reportStats) {
    }
}
