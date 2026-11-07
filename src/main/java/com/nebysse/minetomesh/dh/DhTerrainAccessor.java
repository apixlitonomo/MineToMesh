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
        } catch (Throwable throwable) {
            LOGGER.debug("Error querying DH terrain data for chunk {}, {}: {}",
                    chunkPosX, chunkPosZ, throwable.getMessage());
        }

        return Optional.empty();
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
