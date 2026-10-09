package com.magmaguy.betterstructures.worldedit;

import com.magmaguy.betterstructures.MetadataHandler;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import com.magmaguy.betterstructures.util.ChunkAccess;
import com.magmaguy.betterstructures.util.DeferredWorkQueue;
import com.magmaguy.betterstructures.util.ChunkTicketLease;

import java.util.concurrent.CompletableFuture;

/** One requested chunk and one owned ticket per active paste; all world mutation stays on the server thread. */
public final class PasteChunkReadiness implements AutoCloseable {
    private final World world;
    private final boolean generate;
    private CompletableFuture<Chunk> pending;
    private Chunk held;
    private ChunkTicketLease tickets;
    private boolean closed;
    private long requestedAt;
    private int requestedX, requestedZ;
    private int attempts;

    public PasteChunkReadiness(World world) {
        this(world, true);
    }

    public PasteChunkReadiness(World world, boolean generate) {
        this.world = world;
        this.generate = generate;
    }

    public boolean ready(Location location) {
        ChunkAccess.requireMainThread();
        if (closed) return false;
        if (Bukkit.getWorld(world.getUID()) != world)
            throw new IllegalStateException("Paste world was unloaded: " + world.getName());
        int x = location.getBlockX() >> 4, z = location.getBlockZ() >> 4;
        if (held != null && held.getX() == x && held.getZ() == z && world.isChunkLoaded(x, z)) {
            pending = null;
            attempts = 0;
            return true;
        }
        if (pending != null) {
            if (System.nanoTime() - requestedAt >= DeferredWorkQueue.TIMEOUT_NANOS)
                throw new IllegalStateException("Chunk load timed out: " + world.getName() + " (" + requestedX + ", " + requestedZ + ")");
            if (!pending.isDone()) return false;
            Chunk loaded = pending.join(); // isDone above: never waits on the server thread.
            pending = null;
            if (closed || Bukkit.getWorld(world.getUID()) != world)
                throw new IllegalStateException("Paste world was unloaded while loading a chunk");
            if (loaded == null || !world.isChunkLoaded(loaded.getX(), loaded.getZ())) return false;
            hold(loaded);
            attempts = 0;
            return loaded.getX() == x && loaded.getZ() == z;
        }
        if (world.isChunkLoaded(x, z)) {
            hold(ChunkAccess.loadedChunk(world, x, z));
            attempts = 0;
            return true;
        }
        release();
        if (attempts == 0) requestedAt = System.nanoTime();
        if (++attempts > DeferredWorkQueue.MAX_ATTEMPTS || System.nanoTime() - requestedAt >= DeferredWorkQueue.TIMEOUT_NANOS)
            throw new IllegalStateException("Chunk retries exhausted: " + world.getName() + " (" + x + ", " + z + ")");
        requestedX = x;
        requestedZ = z;
        pending = ChunkAccess.request(world, x, z, generate);
        // Paper's async load has a temporary ticket. Retain on completion instead of
        // waiting for the next backoff check, which may happen after it has unloaded.
        pending.thenAccept(loaded -> {
            if (loaded == null) return;
            Runnable retain = () -> {
                if (!closed && Bukkit.getWorld(world.getUID()) == world
                        && world.isChunkLoaded(loaded.getX(), loaded.getZ())) hold(loaded);
            };
            if (Bukkit.isPrimaryThread()) retain.run();
            else Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, retain);
        });
        return false;
    }

    private void hold(Chunk chunk) {
        ChunkAccess.requireLoaded(world, chunk.getX(), chunk.getZ());
        release();
        tickets = new ChunkTicketLease(MetadataHandler.PLUGIN);
        tickets.retain(chunk);
        held = chunk;
    }

    private void release() {
        if (held != null) {
            tickets.close();
            tickets = null;
            held = null;
        }
    }

    @Override public void close() {
        closed = true;
        release();
        if (pending != null) pending.cancel(false);
        pending = null;
    }
}
