package com.nebysse.minetomesh.dh;

/**
 * Configuration options for Distant Horizons fallback.
 */
public final class DhConfig {
    private static final String PROPERTY_DH_ENABLED = "minetomesh.dh.fallback";
    private static Boolean fallbackEnabled;

    private DhConfig() {
    }

    /**
     * Whether DH fallback is enabled. Defaults to true when DH is present.
     * Can be overridden via system property -Dminetomesh.dh.fallback=false/true.
     */
    public static boolean isLodFallbackEnabled() {
        if (fallbackEnabled != null) {
            return fallbackEnabled;
        }
        String property = System.getProperty(PROPERTY_DH_ENABLED);
        if (property != null) {
            return Boolean.parseBoolean(property);
        }
        return true;
    }

    public static void setLodFallbackEnabled(Boolean enabled) {
        fallbackEnabled = enabled;
    }
}
