package com.magmaguy.betterstructures.util;

import java.util.LinkedHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Server-thread queue. Retries never reset the timeout, reorder FIFO eviction, or duplicate keys. */
public final class DeferredWorkQueue<K, V> {
    public enum DropReason { QUEUE_LIMIT, TIMEOUT, ATTEMPTS, FAILURE }
    public enum TimeoutStart { ENQUEUE, FIRST_ATTEMPT }
    public static final int MAX_ATTEMPTS = 16;
    public static final long TIMEOUT_NANOS = 60_000_000_000L;
    private final int capacity;
    private final TimeoutStart timeoutStart;
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>();
    private record Entry<V>(V value, Long startedAt, long nextTick, int attempts) { }

    public DeferredWorkQueue(int capacity) { this(capacity, TimeoutStart.ENQUEUE); }

    public DeferredWorkQueue(int capacity, TimeoutStart timeoutStart) {
        this.capacity = capacity;
        this.timeoutStart = java.util.Objects.requireNonNull(timeoutStart);
    }

    public void add(K key, V value, long tick, long now, Consumer<V> dropped) {
        addWithReason(key, value, tick, now, (item, reason) -> dropped.accept(item));
    }

    public void addWithReason(K key, V value, long tick, long now,
                              java.util.function.BiConsumer<V, DropReason> dropped) {
        if (entries.containsKey(key)) return;
        if (entries.size() >= capacity) {
            var oldest = entries.entrySet().iterator();
            V evicted = oldest.next().getValue().value();
            oldest.remove();
            dropped.accept(evicted, DropReason.QUEUE_LIMIT);
        }
        entries.put(key, new Entry<>(value, timeoutStart == TimeoutStart.ENQUEUE ? now : null, tick + 1, 0));
    }

    public int drain(long tick, long now, int maxOperations, Predicate<V> ready,
                     Consumer<V> run, Consumer<V> dropped, java.util.function.BooleanSupplier withinBudget) {
        return drainWithReason(tick, now, maxOperations, ready, run,
                (item, reason) -> dropped.accept(item), withinBudget);
    }

    public int drainWithReason(long tick, long now, int maxOperations, Predicate<V> ready,
                     Consumer<V> run, java.util.function.BiConsumer<V, DropReason> dropped,
                     java.util.function.BooleanSupplier withinBudget) {
        int operations = 0;
        // Callbacks can enqueue successors. Inspect a snapshot, never run those in the same tick.
        for (K key : java.util.List.copyOf(entries.keySet())) {
            if (operations >= maxOperations || !withinBudget.getAsBoolean()) break;
            Entry<V> entry = entries.get(key);
            if (entry == null) continue;
            if (entry.startedAt() != null && now - entry.startedAt() >= TIMEOUT_NANOS) {
                entries.remove(key);
                dropped.accept(entry.value(), DropReason.TIMEOUT);
                continue;
            }
            if (tick < entry.nextTick()) continue;
            if (entry.startedAt() == null) {
                entry = new Entry<>(entry.value(), now, entry.nextTick(), entry.attempts());
                entries.put(key, entry);
            }
            operations++;
            boolean isReady;
            try {
                isReady = ready.test(entry.value());
            } catch (RuntimeException failure) {
                entries.remove(key);
                dropped.accept(entry.value(), DropReason.FAILURE);
                continue;
            }
            if (isReady) {
                entries.remove(key);
                run.accept(entry.value());
            } else if (entry.attempts() + 1 >= MAX_ATTEMPTS) {
                entries.remove(key);
                dropped.accept(entry.value(), DropReason.ATTEMPTS);
            } else {
                int attempts = entry.attempts() + 1;
                entries.put(key, new Entry<>(entry.value(), entry.startedAt(),
                        tick + backoffTicks(attempts), attempts));
            }
        }
        return operations;
    }

    public static long backoffTicks(int attempts) { return Math.min(100L, 1L << Math.min(attempts, 7)); }
    public void removeIf(Predicate<V> predicate) { entries.values().removeIf(e -> predicate.test(e.value())); }
    public void removeIf(Predicate<V> predicate, Consumer<V> removed) {
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            V value = iterator.next().value();
            if (predicate.test(value)) {
                iterator.remove();
                removed.accept(value);
            }
        }
    }
    public boolean containsKey(K key) { return entries.containsKey(key); }
    public boolean isEmpty() { return entries.isEmpty(); }
    public int size() { return entries.size(); }
    public void clear() { entries.clear(); }
    public void clear(Consumer<V> removed) { removeIf(value -> true, removed); }
}
