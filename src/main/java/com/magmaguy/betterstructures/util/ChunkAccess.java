package com.magmaguy.betterstructures.util;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.concurrent.CompletableFuture;

/** Never turn a missing chunk into a synchronous load, even on non-Paper servers. */
public final class ChunkAccess {
    private ChunkAccess() { }

    public static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Live world access requires the server thread");
    }

    public static void requireLoaded(World world, int x, int z) {
        requireMainThread();
        if (!world.isChunkLoaded(x, z))
            throw new IllegalStateException("Chunk is not ready: " + world.getName() + " (" + x + ", " + z + ")");
    }

    public static Chunk loadedChunk(World world, int x, int z) {
        requireLoaded(world, x, z);
        // No scheduler yield between the test and retrieval, on the server thread.
        return world.getChunkAt(x, z);
    }

    public static Block block(Location at) {
        requireLoaded(at.getWorld(), at.getBlockX() >> 4, at.getBlockZ() >> 4);
        return at.getBlock();
    }

    public static int highestY(Location at) {
        requireLoaded(at.getWorld(), at.getBlockX() >> 4, at.getBlockZ() >> 4);
        return at.getWorld().getHighestBlockYAt(at);
    }

    public static boolean generatedIfLoaded(World world, int x, int z) {
        requireMainThread();
        // CraftWorld.isChunkGenerated may wait for disk IO through managedBlock otherwise.
        return world.isChunkLoaded(x, z) && world.isChunkGenerated(x, z);
    }

    public static CompletableFuture<Chunk> request(World world, int x, int z) {
        return request(world, x, z, true);
    }

    public static CompletableFuture<Chunk> request(World world, int x, int z, boolean generate) {
        requireMainThread();
        try {
            // Keep the existing Spigot compile API; Paper/Purpur supplies this method.
            @SuppressWarnings("unchecked")
            var future = (CompletableFuture<Chunk>) world.getClass()
                    .getMethod("getChunkAtAsync", int.class, int.class, boolean.class)
                    .invoke(world, x, z, generate);
            return future;
        } catch (ReflectiveOperationException failure) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Asynchronous chunk loading is unavailable; refusing synchronous fallback", failure));
        }
    }
}
