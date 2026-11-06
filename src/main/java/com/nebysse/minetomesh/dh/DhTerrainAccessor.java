package com.nebysse.minetomesh.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import java.io.Closeable;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accesses Distant Horizons terrain repository data.
 */
public final class DhTerrainAccessor implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DhTerrainAccessor.class);

    private final IDhApiLevelWrapper levelWrapper;
    private IDhApiTerrainDataCache cache;

    public DhTerrainAccessor(IDhApiLevelWrapper levelWrapper) {
        this.levelWrapper = levelWrapper;
        if (DhAvailability.isAvailable()) {
            try {
                IDhApiTerrainDataRepo repo = DhApi.Delayed.terrainRepo;
                if (repo != null) {
                    this.cache = repo.createSoftCache();
                }
            } catch (Throwable throwable) {
                LOGGER.debug("Could not create DH soft cache: {}", throwable.getMessage());
            }
        }
    }

    /**
     * Retrieves terrain data for a chunk (chunkPosX, chunkPosZ).
     * The returned array is ordered [relX][relZ][colIndex] where relX, relZ are 0..15.
     */
    public Optional<DhApiTerrainDataPoint[][][]> getChunkTerrainData(int chunkPosX, int chunkPosZ) {
        if (!DhAvailability.isAvailable() || levelWrapper == null) {
            return Optional.empty();
        }

        try {
            IDhApiTerrainDataRepo repo = DhApi.Delayed.terrainRepo;
            if (repo == null) {
                return Optional.empty();
            }

            DhApiResult<DhApiTerrainDataPoint[][][]> result = null;
            try {
                result = repo.getAllTerrainDataAtChunkPos(levelWrapper, chunkPosX, chunkPosZ, cache);
            } catch (NoSuchMethodError | Exception e) {
                try {
                    java.lang.reflect.Method m = repo.getClass().getMethod(
                            "getAllTerrainDataAtChunkPos", IDhApiLevelWrapper.class, int.class, int.class);
                    @SuppressWarnings("unchecked")
                    DhApiResult<DhApiTerrainDataPoint[][][]> res =
                            (DhApiResult<DhApiTerrainDataPoint[][][]>) m.invoke(repo, levelWrapper, chunkPosX, chunkPosZ);
                    result = res;
                } catch (Throwable ignored) {
                }
            }

            if (result != null && result.success && result.payload != null) {
                return Optional.of(result.payload);
            }

            // Fallback: query column by column if chunk-level query was unsuccessful
            DhApiTerrainDataPoint[][][] fallback = queryColumnsForChunk(repo, chunkPosX, chunkPosZ);
            if (fallback != null) {
                return Optional.of(fallback);
            }
        } catch (Throwable throwable) {
            LOGGER.debug("Error querying DH terrain data for chunk {}, {}: {}",
                    chunkPosX, chunkPosZ, throwable.getMessage());
        }

        return Optional.empty();
    }

    private DhApiTerrainDataPoint[][][] queryColumnsForChunk(
            IDhApiTerrainDataRepo repo, int chunkPosX, int chunkPosZ) {
        int baseBlockX = chunkPosX << 4;
        int baseBlockZ = chunkPosZ << 4;
        DhApiTerrainDataPoint[][][] chunkData = new DhApiTerrainDataPoint[16][16][];
        boolean hasAnyData = false;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                try {
                    DhApiResult<DhApiTerrainDataPoint[]> colResult = null;
                    try {
                        colResult = repo.getColumnDataAtBlockPos(levelWrapper, baseBlockX + x, baseBlockZ + z, cache);
                    } catch (NoSuchMethodError | Exception e) {
                        try {
                            java.lang.reflect.Method m = repo.getClass().getMethod(
                                    "getColumnDataAtBlockPos", IDhApiLevelWrapper.class, int.class, int.class);
                            @SuppressWarnings("unchecked")
                            DhApiResult<DhApiTerrainDataPoint[]> res =
                                    (DhApiResult<DhApiTerrainDataPoint[]>) m.invoke(repo, levelWrapper, baseBlockX + x, baseBlockZ + z);
                            colResult = res;
                        } catch (Throwable ignored) {
                        }
                    }

                    if (colResult != null && colResult.success && colResult.payload != null && colResult.payload.length > 0) {
                        chunkData[x][z] = colResult.payload;
                        hasAnyData = true;
                    } else {
                        chunkData[x][z] = new DhApiTerrainDataPoint[0];
                    }
                } catch (Throwable ignored) {
                    chunkData[x][z] = new DhApiTerrainDataPoint[0];
                }
            }
        }

        return hasAnyData ? chunkData : null;
    }

    @Override
    public void close() {
        if (cache != null) {
            try {
                cache.close();
            } catch (Throwable throwable) {
                LOGGER.debug("Error closing DH terrain cache: {}", throwable.getMessage());
            } finally {
                cache = null;
            }
        }
    }
}
