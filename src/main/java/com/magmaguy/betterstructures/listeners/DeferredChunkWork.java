package com.magmaguy.betterstructures.listeners;

import com.magmaguy.betterstructures.BetterStructures;
import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.util.ChunkAccess;
import com.magmaguy.betterstructures.util.ChunkFootprint;
import com.magmaguy.betterstructures.util.DeferredWorkQueue;
import com.magmaguy.betterstructures.util.ChunkTicketLease;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.function.Consumer;

/** Shared budget for chunk events, fitter initialization and footprint continuations. */
public final class DeferredChunkWork {
    private static final DeferredWorkQueue<Object, Work> queue = new DeferredWorkQueue<>(4096);
    private static final ChunkScanReentrancyGuard guard = new ChunkScanReentrancyGuard();
    private static BukkitTask task;
    private static long tick;
    private record Work(UUID worldId, ChunkFootprint footprint, String description, Consumer<World> action,
                        ChunkTicketLease tickets) { }

    private DeferredChunkWork() { }

    public static void submit(Object key, UUID worldId, ChunkFootprint footprint, String description, Consumer<World> action) {
        ChunkAccess.requireMainThread();
        if (queue.containsKey(key)) return;
        var tickets = new ChunkTicketLease(MetadataHandler.PLUGIN);
        try {
            World world = Bukkit.getWorld(worldId);
            if (world != null) tickets.retainLoaded(world, footprint);
            queue.addWithReason(key, new Work(worldId, footprint, description, action, tickets),
                    tick, System.nanoTime(), DeferredChunkWork::drop);
        } catch (RuntimeException failure) {
            tickets.close();
            throw failure;
        }
        start();
    }

    public static void start() {
        if (task != null || queue.isEmpty() || MetadataHandler.PLUGIN == null || !MetadataHandler.PLUGIN.isEnabled()) return;
        task = Bukkit.getScheduler().runTaskTimer(MetadataHandler.PLUGIN, DeferredChunkWork::drain, 1L, 1L);
    }

    private static void drain() {
        if (guard.isActive()) return;
        tick++;
        if (BetterStructures.isReloading()) return;
        long deadline = System.nanoTime() + (long) (DefaultConfig.getChunkScanBudgetMilliseconds() * 1_000_000);
        queue.drainWithReason(tick, System.nanoTime(), DefaultConfig.getMaxChunkScansPerTick(),
                work -> {
                    World world = Bukkit.getWorld(work.worldId());
                    return world != null && work.tickets().retainLoaded(world, work.footprint()) && !guard.isActive();
                }, work -> {
                    try { guard.runIfIdle(() -> work.action().accept(Bukkit.getWorld(work.worldId()))); }
                    catch (RuntimeException failure) {
                        warn(work, "FAILURE");
                        MetadataHandler.PLUGIN.getLogger().warning("Deferred operation failed: " + failure);
                    } finally {
                        work.tickets().close();
                    }
                }, DeferredChunkWork::drop, () -> System.nanoTime() < deadline);
        if (queue.isEmpty()) clear();
    }

    private static void drop(Work work, DeferredWorkQueue.DropReason reason) {
        work.tickets().close();
        warn(work, reason.name());
    }

    private static void warn(Work work, String reason) {
        World world = Bukkit.getWorld(work.worldId());
        MetadataHandler.PLUGIN.getLogger().warning("Discarded chunk operation (" + reason + "): "
                + work.description() + " world=" + (world == null ? work.worldId() : world.getName())
                + " chunks=" + work.footprint());
    }

    public static void discardWorld(UUID id) {
        queue.removeIf(work -> work.worldId().equals(id), work -> work.tickets().close());
    }

    public static void clear() {
        if (task != null) task.cancel();
        task = null;
        queue.clear(work -> work.tickets().close());
    }
}
