package com.magmaguy.betterstructures.worldedit;

import com.magmaguy.betterstructures.MetadataHandler;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import com.magmaguy.betterstructures.util.ChunkAccess;
import com.magmaguy.betterstructures.util.DeferredWorkQueue;

import java.util.concurrent.CompletableFuture;

/** One requested chunk and one owned ticket per active paste; all world mutation stays on the server thread. */
public final class PasteChunkReadiness implements AutoCloseable {
    private final World world;
    private CompletableFuture<Chunk> pending;
    private Chunk held;
    private boolean closed;
    private long requestedAt;
    private int requestedX, requestedZ;
    private int attempts;

    public PasteChunkReadiness(World world) {
        this.world = world;
    }

    public boolean ready(Location location) {
        ChunkAccess.requireMainThread();
        if (closed) return false;
        if (Bukkit.getWorld(world.getUID()) != world)
            throw new IllegalStateException("Paste world was unloaded: " + world.getName());
        int x = location.getBlockX() >> 4, z = location.getBlockZ() >> 4;
        if (held != null && held.getX() == x && held.getZ() == z && world.isChunkLoaded(x, z)) return true;
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
        pending = ChunkAccess.request(world, x, z);
        return false;
    }

    private void hold(Chunk chunk) {
        ChunkAccess.requireLoaded(world, chunk.getX(), chunk.getZ());
        release();
        chunk.addPluginChunkTicket(MetadataHandler.PLUGIN);
        held = chunk;
    }

    private void release() {
        if (held != null) {
            held.removePluginChunkTicket(MetadataHandler.PLUGIN);
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
