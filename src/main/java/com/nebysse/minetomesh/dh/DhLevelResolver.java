package com.nebysse.minetomesh.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the {@link IDhApiLevelWrapper} for a given dimension string.
 */
public final class DhLevelResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(DhLevelResolver.class);

    private DhLevelResolver() {
    }

    public static Optional<IDhApiLevelWrapper> resolveLevel(String dimensionId) {
        if (!DhAvailability.isAvailable()) {
            return Optional.empty();
        }

        try {
            IDhApiWorldProxy worldProxy = DhApi.Delayed.worldProxy;
            if (worldProxy == null || !worldProxy.worldLoaded()) {
                return Optional.empty();
            }

            Iterable<IDhApiLevelWrapper> levels = worldProxy.getAllLoadedLevelWrappers();
            if (levels != null) {
                for (IDhApiLevelWrapper level : levels) {
                    if (matchesDimension(level, dimensionId)) {
                        return Optional.of(level);
                    }
                }
            }

            // Fallback for singleplayer
            try {
                IDhApiLevelWrapper singlePlayer = worldProxy.getSinglePlayerLevel();
                if (singlePlayer != null && matchesDimension(singlePlayer, dimensionId)) {
                    return Optional.of(singlePlayer);
                }
            } catch (Throwable ignored) {
            }

            // Fallback matching dimension name like
            try {
                String path = dimensionId.contains(":") ? dimensionId.substring(dimensionId.indexOf(':') + 1) : dimensionId;
                Iterable<IDhApiLevelWrapper> likeLevels = worldProxy.getAllLoadedLevelsWithDimensionNameLike(path);
                if (likeLevels != null) {
                    for (IDhApiLevelWrapper level : likeLevels) {
                        if (matchesDimension(level, dimensionId)) {
                            return Optional.of(level);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

        } catch (Throwable throwable) {
            LOGGER.debug("Failed resolving DH level for dimension {}: {}", dimensionId, throwable.getMessage());
        }
        return Optional.empty();
    }

    private static boolean matchesDimension(IDhApiLevelWrapper level, String dimensionId) {
        if (level == null) {
            return false;
        }
        try {
            String id = level.getDhIdentifier();
            if (id != null && (id.equalsIgnoreCase(dimensionId) || id.contains(dimensionId))) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            String name = level.getDimensionName();
            if (name != null) {
                if (name.equalsIgnoreCase(dimensionId)) {
                    return true;
                }
                String normalized = dimensionId.toLowerCase(Locale.ROOT);
                if (normalized.endsWith("/" + name.toLowerCase(Locale.ROOT))
                        || normalized.endsWith(":" + name.toLowerCase(Locale.ROOT))
                        || name.toLowerCase(Locale.ROOT).equals(normalized)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
