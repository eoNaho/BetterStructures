package com.magmaguy.betterstructures.listeners;

/**
 * Prevents nested drains, including those triggered by third-party event listeners.
 * Chunk events only enqueue work now; our reads never enter managedBlock. Retain
 * the guard as defense against callbacks that execute scheduler work recursively.
 */
final class ChunkScanReentrancyGuard {

    private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);

    boolean isActive() {
        return active.get();
    }

    boolean runIfIdle(Runnable scan) {
        if (active.get()) return false;

        active.set(true);
        try {
            scan.run();
            return true;
        } finally {
            active.remove();
        }
    }
}
