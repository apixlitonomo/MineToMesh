package com.nebysse.minetomesh.dh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DhAvailabilityTest {

    @AfterEach
    void tearDown() {
        DhAvailability.reset();
        DhConfig.setLodFallbackEnabled(null);
    }

    @Test
    void detectsAvailableDhApiInTestClasspath() {
        boolean available = DhAvailability.isAvailable();
        assertTrue(available, "Expected DH API to be available on test classpath");
        assertNotNull(DhAvailability.apiVersion());
        assertTrue(DhAvailability.majorVersion() >= 3 && DhAvailability.majorVersion() <= 7);
    }

    @Test
    void configToggleWorks() {
        assertTrue(DhConfig.isLodFallbackEnabled());
        DhConfig.setLodFallbackEnabled(false);
        assertFalse(DhConfig.isLodFallbackEnabled());
        DhConfig.setLodFallbackEnabled(true);
        assertTrue(DhConfig.isLodFallbackEnabled());
    }
}
