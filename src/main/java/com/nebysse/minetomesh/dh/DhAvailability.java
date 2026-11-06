package com.nebysse.minetomesh.dh;

import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects presence of Distant Horizons runtime and checks API compatibility.
 */
public final class DhAvailability {
    private static final Logger LOGGER = LoggerFactory.getLogger(DhAvailability.class);
    private static final String DH_API_CLASS = "com.seibel.distanthorizons.api.DhApi";

    private static Boolean available;
    private static String apiVersionString = "none";
    private static int detectedMajor = -1;
    private static int detectedMinor = -1;
    private static int detectedPatch = -1;
    private static boolean loggedOnce;

    private DhAvailability() {
    }

    /**
     * Checks if Distant Horizons runtime classes are present and API version is supported.
     * Supported major versions: 3, 4, 5, 6, 7.
     */
    public static synchronized boolean isAvailable() {
        if (available != null) {
            return available;
        }

        try {
            Class<?> dhApi = Class.forName(DH_API_CLASS, false, DhAvailability.class.getClassLoader());
            Method majorMethod = dhApi.getMethod("getApiMajorVersion");
            Method minorMethod = dhApi.getMethod("getApiMinorVersion");
            Method patchMethod = dhApi.getMethod("getApiPatchVersion");

            detectedMajor = (int) majorMethod.invoke(null);
            detectedMinor = (int) minorMethod.invoke(null);
            detectedPatch = (int) patchMethod.invoke(null);
            apiVersionString = detectedMajor + "." + detectedMinor + "." + detectedPatch;

            if (detectedMajor < 3 || detectedMajor > 7) {
                if (!loggedOnce) {
                    LOGGER.warn("Distant Horizons API detected version {} is outside supported range (3-7); disabling LOD fallback",
                            apiVersionString);
                    loggedOnce = true;
                }
                available = false;
                return false;
            }

            if (!loggedOnce) {
                LOGGER.info("Distant Horizons API detected: version {}", apiVersionString);
                loggedOnce = true;
            }
            available = true;
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            available = false;
            apiVersionString = "absent";
            return false;
        } catch (Throwable throwable) {
            if (!loggedOnce) {
                LOGGER.warn("Distant Horizons API detection encountered error; disabling LOD fallback: {}",
                        throwable.getMessage());
                loggedOnce = true;
            }
            available = false;
            apiVersionString = "error";
            return false;
        }
    }

    public static synchronized String apiVersion() {
        if (available == null) {
            isAvailable();
        }
        return apiVersionString;
    }

    public static synchronized int majorVersion() {
        if (available == null) {
            isAvailable();
        }
        return detectedMajor;
    }

    /** For testing resets. */
    public static synchronized void reset() {
        available = null;
        apiVersionString = "none";
        detectedMajor = -1;
        detectedMinor = -1;
        detectedPatch = -1;
        loggedOnce = false;
    }
}
