package com.magmaguy.betterstructures.util;

import org.bukkit.Location;
import org.bukkit.World;

/** Inclusive X/Z bounds; flooring before shifting also handles negative/fractional anchors. */
public record ChunkFootprint(int minX, int minZ, int maxX, int maxZ) {
    public static ChunkFootprint blocks(double minX, double minZ, double maxX, double maxZ) {
        return new ChunkFootprint((int) Math.floor(minX) >> 4, (int) Math.floor(minZ) >> 4,
                (int) Math.floor(maxX) >> 4, (int) Math.floor(maxZ) >> 4);
    }

    public static ChunkFootprint at(Location at) {
        return blocks(at.getX(), at.getZ(), at.getX(), at.getZ());
    }

    public boolean isLoaded(World world) {
        ChunkAccess.requireMainThread();
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++)
                if (!world.isChunkLoaded(x, z)) return false;
        return true;
    }
}
