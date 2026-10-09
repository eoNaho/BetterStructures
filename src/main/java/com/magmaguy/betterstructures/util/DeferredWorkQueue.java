package com.magmaguy.betterstructures.util;

import java.util.LinkedHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Server-thread queue. Retries never reset age, reorder FIFO eviction, or duplicate keys. */
public final class DeferredWorkQueue<K, V> {
    public static final int MAX_ATTEMPTS = 16;
    public static final long TIMEOUT_NANOS = 60_000_000_000L;
    private final int capacity;
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>();
    private record Entry<V>(V value, long created, long nextTick, int attempts) { }

    public DeferredWorkQueue(int capacity) { this.capacity = capacity; }

    public void add(K key, V value, long tick, long now, Consumer<V> dropped) {
        if (entries.containsKey(key)) return;
        if (entries.size() >= capacity) {
            var oldest = entries.entrySet().iterator();
            V evicted = oldest.next().getValue().value();
            oldest.remove();
            dropped.accept(evicted);
        }
        entries.put(key, new Entry<>(value, now, tick + 1, 0));
    }

    public int drain(long tick, long now, int maxOperations, Predicate<V> ready,
                     Consumer<V> run, Consumer<V> dropped, java.util.function.BooleanSupplier withinBudget) {
        int operations = 0;
        // Callbacks can enqueue successors. Inspect a snapshot, never run those in the same tick.
        for (K key : java.util.List.copyOf(entries.keySet())) {
            if (operations >= maxOperations || !withinBudget.getAsBoolean()) break;
            Entry<V> entry = entries.get(key);
            if (entry == null) continue;
            if (now - entry.created() >= TIMEOUT_NANOS) {
                entries.remove(key);
                dropped.accept(entry.value());
                continue;
            }
            if (tick < entry.nextTick()) continue;
            operations++;
            if (ready.test(entry.value())) {
                entries.remove(key);
                run.accept(entry.value());
            } else if (entry.attempts() + 1 >= MAX_ATTEMPTS) {
                entries.remove(key);
                dropped.accept(entry.value());
            } else {
                int attempts = entry.attempts() + 1;
                entries.put(key, new Entry<>(entry.value(), entry.created(),
                        tick + backoffTicks(attempts), attempts));
            }
        }
        return operations;
    }

    public static long backoffTicks(int attempts) { return Math.min(100L, 1L << Math.min(attempts, 7)); }
    public void removeIf(Predicate<V> predicate) { entries.values().removeIf(e -> predicate.test(e.value())); }
    public boolean isEmpty() { return entries.isEmpty(); }
    public int size() { return entries.size(); }
    public void clear() { entries.clear(); }
}
