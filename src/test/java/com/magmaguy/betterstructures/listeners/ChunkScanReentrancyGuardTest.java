package com.magmaguy.betterstructures.listeners;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkScanReentrancyGuardTest {
    @Test void nestedDrainIsRejectedAndFailureAlwaysReleasesGuard() {
        var guard = new ChunkScanReentrancyGuard();
        assertThrows(IllegalStateException.class, () -> guard.runIfIdle(() -> {
            assertTrue(guard.isActive());
            assertFalse(guard.runIfIdle(() -> fail("Nested work must not run")));
            throw new IllegalStateException("Fixture failure");
        }));
        assertFalse(guard.isActive());
        assertTrue(guard.runIfIdle(() -> { }));
    }
}
