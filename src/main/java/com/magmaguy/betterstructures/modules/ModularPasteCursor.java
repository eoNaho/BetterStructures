package com.magmaguy.betterstructures.modules;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.AffineTransform;

import java.util.Iterator;
import java.util.NoSuchElementException;

/** Visits source cells in destination-chunk order without retaining cells or transformed clipboards. */
final class ModularPasteCursor implements Iterator<BlockVector3> {
    private final AffineTransform inverse;
    private final BlockVector3 minimum, maximum;
    private final int originX, originZ, maxX, maxZ, minChunkX, maxChunkX, maxChunkZ;
    private int chunkX, chunkZ, firstX, lastX, firstZ, lastZ, x, y, z;
    private boolean remaining = true;

    ModularPasteCursor(BlockVector3 low, BlockVector3 high, AffineTransform transform, int originX, int originZ) {
        this.inverse = transform.inverse();
        this.originX = originX;
        this.originZ = originZ;
        BlockVector3 min = transform.apply(low.toVector3()).toBlockPoint();
        BlockVector3 max = min;
        // Module rotations are quarter turns, so their transformed region is still a cuboid.
        for (int sourceX : new int[]{low.x(), high.x()})
            for (int sourceY : new int[]{low.y(), high.y()})
                for (int sourceZ : new int[]{low.z(), high.z()}) {
                    BlockVector3 point = transform.apply(BlockVector3.at(sourceX, sourceY, sourceZ).toVector3()).toBlockPoint();
                    min = min.getMinimum(point);
                    max = max.getMaximum(point);
                }
        minimum = min;
        maximum = max;
        maxX = originX + maximum.x() - minimum.x();
        maxZ = originZ + maximum.z() - minimum.z();
        minChunkX = originX >> 4;
        maxChunkX = maxX >> 4;
        maxChunkZ = maxZ >> 4;
        chunkX = minChunkX;
        chunkZ = originZ >> 4;
        beginChunk();
    }

    BlockVector3 transformedMinimum() { return minimum; }

    @Override public boolean hasNext() { return remaining; }

    @Override public BlockVector3 next() {
        if (!remaining) throw new NoSuchElementException("Modular paste cursor is exhausted");
        BlockVector3 source = inverse.apply(BlockVector3.at(x - originX + minimum.x(), y,
                z - originZ + minimum.z()).toVector3()).toBlockPoint();
        if (++x > lastX) {
            x = firstX;
            if (++z > lastZ) {
                z = firstZ;
                if (++y > maximum.y()) {
                    if (++chunkX > maxChunkX) {
                        chunkX = minChunkX;
                        chunkZ++;
                    }
                    if (chunkZ > maxChunkZ) remaining = false;
                    else beginChunk();
                }
            }
        }
        return source;
    }

    private void beginChunk() {
        firstX = Math.max(originX, chunkX << 4);
        lastX = Math.min(maxX, (chunkX << 4) + 15);
        firstZ = Math.max(originZ, chunkZ << 4);
        lastZ = Math.min(maxZ, (chunkZ << 4) + 15);
        x = firstX;
        z = firstZ;
        y = minimum.y();
    }
}
