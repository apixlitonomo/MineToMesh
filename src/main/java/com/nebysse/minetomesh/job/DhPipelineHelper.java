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

public final class DhPipelineHelper {
    private DhPipelineHelper() {
    }

    public static DhLodResult captureLodChunks(
            ClientLevel level,
            Selection selection,
            List<ExportPlan.MissingChunk> missingChunks,
            com.nebysse.minetomesh.texture.TextureRegistry textures) {
        boolean present = DhAvailability.isAvailable();
        String apiVersion = present ? DhAvailability.apiVersion() : null;
        boolean enabled = present && DhConfig.isLodFallbackEnabled();

        int attempted = 0;
        int filled = 0;
        int failed = 0;

        List<ChunkBatch> batches = new ArrayList<>();
        List<ExportPlan.MissingChunk> remainingMissing = new ArrayList<>();

        if (!enabled || missingChunks.isEmpty()) {
            remainingMissing.addAll(missingChunks);
            ExportReport.DhStats stats = new ExportReport.DhStats(
                    present, apiVersion, attempted, filled, failed,
                    "LOD geometry is approximate and generated from Distant Horizons terrain data");
            return new DhLodResult(batches, remainingMissing, stats);
        }

        String dimensionId = level.dimension().location().toString();
        Optional<IDhApiLevelWrapper> levelWrapperOpt = DhLevelResolver.resolveLevel(dimensionId);
        if (levelWrapperOpt.isEmpty()) {
            remainingMissing.addAll(missingChunks);
            ExportReport.DhStats stats = new ExportReport.DhStats(
                    present, apiVersion, attempted, filled, failed,
                    "LOD geometry is approximate and generated from Distant Horizons terrain data");
            return new DhLodResult(batches, remainingMissing, stats);
        }

        IDhApiLevelWrapper levelWrapper = levelWrapperOpt.get();
        int minHeight = level.getMinBuildHeight();

        if (textures != null) {
            textures.register(
                    com.nebysse.minetomesh.dh.DhMaterialResolver.GENERIC_LOD_TEXTURE,
                    com.nebysse.minetomesh.dh.DhMaterialResolver.genericLodTextureImage());
        }

        try (DhTerrainAccessor accessor = new DhTerrainAccessor(levelWrapper)) {
            for (ExportPlan.MissingChunk missing : missingChunks) {
                attempted++;
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
            }
        }

        ExportReport.DhStats stats = new ExportReport.DhStats(
                present, apiVersion, attempted, filled, failed,
                "LOD geometry is approximate and generated from Distant Horizons terrain data");
        return new DhLodResult(batches, remainingMissing, stats);
    }

    public record DhLodResult(
            List<ChunkBatch> lodBatches,
            List<ExportPlan.MissingChunk> remainingMissing,
            ExportReport.DhStats reportStats) {
    }
}
