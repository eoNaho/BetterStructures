package com.magmaguy.betterstructures.util;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DeferredWorkQueueTest {
    @Test void scanBacklogDoesNotConsumeReadinessTimeout() {
        var queue = new DeferredWorkQueue<String, String>(4, DeferredWorkQueue.TimeoutStart.FIRST_ATTEMPT);
        var ran = new ArrayList<String>();
        queue.add("a", "a", 0, 0, value -> fail());
        queue.add("b", "b", 0, 0, value -> fail());
        long delayed = DeferredWorkQueue.TIMEOUT_NANOS * 2;
        // Neither exhausted time budget nor operation budget starts a load timeout.
        queue.drain(1, delayed, 2, value -> fail(), value -> fail(), value -> fail(), () -> false);
        queue.drain(2, delayed, 1, value -> true, ran::add, value -> fail(), () -> true);
        queue.drain(3, delayed * 2, 1, value -> true, ran::add, value -> fail(), () -> true);
        assertEquals(List.of("a", "b"), ran);
        assertTrue(queue.isEmpty());
    }

    @Test void firstAttemptTimeoutStillExpiresDuringBackoffAndDuplicatesDoNotResetIt() {
        var queue = new DeferredWorkQueue<String, String>(4, DeferredWorkQueue.TimeoutStart.FIRST_ATTEMPT);
        var dropped = new ArrayList<String>();
        queue.add("a", "original", 0, 0, dropped::add);
        long started = DeferredWorkQueue.TIMEOUT_NANOS * 2;
        queue.drain(1, started, 1, value -> false, value -> fail(), dropped::add, () -> true);
        queue.add("a", "replacement", 1, started + 1, dropped::add);
        queue.drain(2, started + DeferredWorkQueue.TIMEOUT_NANOS, 1,
                value -> fail(), value -> fail(), dropped::add, () -> true);
        assertEquals(List.of("original"), dropped);
        assertTrue(queue.isEmpty());
    }

    @Test void unstartedScanBacklogRemainsBoundedAndEvictsOldest() {
        var queue = new DeferredWorkQueue<String, String>(2, DeferredWorkQueue.TimeoutStart.FIRST_ATTEMPT);
        var dropped = new ArrayList<String>();
        queue.add("a", "a", 0, 0, dropped::add);
        queue.add("b", "b", 0, 0, dropped::add);
        queue.drain(1, 0, 1, value -> false, value -> fail(), dropped::add, () -> true);
        queue.add("c", "c", 1, DeferredWorkQueue.TIMEOUT_NANOS * 2, dropped::add);
        assertEquals(List.of("a"), dropped);
        assertEquals(2, queue.size());
    }

    @Test void identifiesOverflowTimeoutAndExhaustedRetries() {
        var queue = new DeferredWorkQueue<String, String>(1);
        var reasons = new ArrayList<DeferredWorkQueue.DropReason>();
        queue.addWithReason("a", "a", 0, 0, (value, reason) -> reasons.add(reason));
        queue.addWithReason("b", "b", 0, 0, (value, reason) -> reasons.add(reason));
        queue.drainWithReason(1, DeferredWorkQueue.TIMEOUT_NANOS, 1, value -> false,
                value -> fail(), (value, reason) -> reasons.add(reason), () -> true);
        queue.addWithReason("c", "c", 0, 0, (value, reason) -> reasons.add(reason));
        for (int i = 1; i <= DeferredWorkQueue.MAX_ATTEMPTS; i++) {
            queue.drainWithReason(i * 100L, 0, 1, value -> false,
                    value -> fail(), (value, reason) -> reasons.add(reason), () -> true);
        }
        assertEquals(List.of(DeferredWorkQueue.DropReason.QUEUE_LIMIT,
                DeferredWorkQueue.DropReason.TIMEOUT, DeferredWorkQueue.DropReason.ATTEMPTS), reasons);
    }

    @Test void cleanupCallbacksRunOnceForWorldRemovalAndShutdown() {
        var queue = new DeferredWorkQueue<String, String>(4);
        var released = new ArrayList<String>();
        queue.add("a", "world-a", 0, 0, value -> fail());
        queue.add("b", "world-b", 0, 0, value -> fail());
        queue.removeIf(value -> value.equals("world-a"), released::add);
        queue.clear(released::add);
        queue.clear(released::add);
        assertEquals(List.of("world-a", "world-b"), released);
    }
    @Test void retriesBackOffWithoutExecutingWorkAndEventuallyDrop() {
        var queue = new DeferredWorkQueue<String, String>(4);
        var dropped = new ArrayList<String>();
        var checks = new AtomicInteger();
        queue.add("a", "a", 0, 0, dropped::add);
        long tick = 1;
        for (int attempt = 1; attempt <= DeferredWorkQueue.MAX_ATTEMPTS; attempt++) {
            queue.drain(tick, 0, 2, value -> { checks.incrementAndGet(); return false; },
                    value -> fail("Unavailable chunks must not execute"), dropped::add, () -> true);
            if (attempt < DeferredWorkQueue.MAX_ATTEMPTS) {
                queue.drain(tick + 1, 0, 2, value -> fail("Backoff must be honored"), value -> fail(), dropped::add, () -> true);
                tick += DeferredWorkQueue.backoffTicks(attempt);
            }
        }
        assertEquals(16, checks.get());
        assertEquals(List.of("a"), dropped);
        assertTrue(queue.isEmpty());
    }

    @Test void duplicateKeysKeepOriginalAgeAndOldestIsEvictedEvenAfterRetry() {
        var queue = new DeferredWorkQueue<String, String>(2);
        var dropped = new ArrayList<String>();
        queue.add("a", "first", 0, 0, dropped::add);
        queue.add("b", "second", 0, 0, dropped::add);
        queue.drain(1, 1, 2, value -> false, value -> fail(), dropped::add, () -> true);
        queue.add("a", "replacement", 2, 2, dropped::add);
        queue.add("c", "third", 2, 2, dropped::add);
        assertEquals(List.of("first"), dropped);
        assertEquals(2, queue.size());
    }

    @Test void wallClockTimeoutExpiresEvenDuringBackoff() {
        var queue = new DeferredWorkQueue<String, String>(2);
        var dropped = new ArrayList<String>();
        queue.add("a", "a", 0, 0, dropped::add);
        queue.drain(0, DeferredWorkQueue.TIMEOUT_NANOS, 1, value -> fail(), value -> fail(), dropped::add, () -> true);
        assertEquals(List.of("a"), dropped);
        assertTrue(queue.isEmpty());
    }

    @Test void obeysWorkBudgetAndDefersSuccessorsToNextTick() {
        var queue = new DeferredWorkQueue<String, String>(4);
        var ran = new ArrayList<String>();
        queue.add("a", "a", 0, 0, value -> fail());
        queue.add("b", "b", 0, 0, value -> fail());
        assertEquals(0, queue.drain(1, 0, 2, value -> true, ran::add, value -> fail(), () -> false));
        assertEquals(1, queue.drain(1, 0, 1, value -> true, value -> {
            ran.add(value);
            queue.add("c", "c", 1, 0, ignored -> fail());
        }, value -> fail(), () -> true));
        queue.drain(1, 0, 4, value -> true, ran::add, value -> fail(), () -> true);
        assertEquals(List.of("a", "b"), ran);
        queue.drain(2, 0, 4, value -> true, ran::add, value -> fail(), () -> true);
        assertEquals(List.of("a", "b", "c"), ran);
    }

    @Test void failedReadinessReleasesWorkAndDoesNotStopOtherChunks() {
        var queue = new DeferredWorkQueue<String, String>(4);
        var dropped = new ArrayList<DeferredWorkQueue.DropReason>();
        var ran = new ArrayList<String>();
        queue.add("a", "failed", 0, 0, value -> fail());
        queue.add("b", "healthy", 0, 0, value -> fail());
        queue.drainWithReason(1, 0, 2, value -> {
            if (value.equals("failed")) throw new IllegalStateException("Async load failed");
            return true;
        }, ran::add, (value, reason) -> dropped.add(reason), () -> true);
        assertEquals(List.of(DeferredWorkQueue.DropReason.FAILURE), dropped);
        assertEquals(List.of("healthy"), ran);
        assertTrue(queue.isEmpty());
    }

    @Test void unloadAndReloadCleanupReleaseQueuedWork() {
        var queue = new DeferredWorkQueue<String, String>(4);
        queue.add("a", "world-a", 0, 0, value -> fail());
        queue.add("b", "world-b", 0, 0, value -> fail());
        queue.removeIf(value -> value.equals("world-a"));
        assertEquals(1, queue.size());
        queue.clear();
        assertTrue(queue.isEmpty());
    }
}
