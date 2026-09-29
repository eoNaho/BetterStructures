package com.magmaguy.betterstructures.modules;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModularPasteCursorTest {
    @ParameterizedTest(name = "{displayName} rotation={0}, origin=({1},{2})")
    @CsvSource({
            "0,0,0", "90,0,0", "180,0,0", "270,0,0",
            "0,15,31", "90,15,31", "180,15,31", "270,15,31",
            "0,-17,-33", "90,-17,-33", "180,-17,-33", "270,-17,-33",
            "0,-1,16", "90,-1,16", "180,-1,16", "270,-1,16"
    })
    void coversEverySourceBlockOnceWithoutRevisitingADestinationChunk(int rotation, int originX, int originZ) {
        BlockVector3 low = BlockVector3.at(-11, -2, 7), high = BlockVector3.at(25, 0, 27);
        AffineTransform transform = new AffineTransform().rotateY((360 - rotation) % 360);
        ModularPasteCursor cursor = new ModularPasteCursor(low, high, transform, originX, originZ);

        // Independent reference: the existing clipboard region's full Cartesian set.
        Set<BlockVector3> expected = new HashSet<>();
        for (int x = -11; x <= 25; x++) for (int y = -2; y <= 0; y++) for (int z = 7; z <= 27; z++)
            expected.add(BlockVector3.at(x, y, z));
        BlockVector3 transformedMinimum = expected.stream().map(point -> transform.apply(point.toVector3()).toBlockPoint())
                .reduce(BlockVector3::getMinimum).orElseThrow();
        assertEquals(transformedMinimum, cursor.transformedMinimum());

        Set<Long> visitedChunks = new HashSet<>();
        Long previousChunk = null;
        int visitedBlocks = 0;
        while (cursor.hasNext()) {
            BlockVector3 source = cursor.next();
            assertTrue(expected.remove(source), "Duplicate or out-of-region source block: " + source);
            var destination = transform.apply(source.toVector3()).toBlockPoint().subtract(transformedMinimum)
                    .add(originX, 0, originZ);
            long chunk = ((long) (destination.x() >> 4) << 32) ^ ((destination.z() >> 4) & 0xffffffffL);
            if (previousChunk == null || chunk != previousChunk) {
                assertTrue(visitedChunks.add(chunk), "Revisited a destination chunk after releasing its ticket");
                previousChunk = chunk;
            }
            visitedBlocks++;
        }
        assertEquals(2331, visitedBlocks);
        assertTrue(expected.isEmpty(), "The chunk cursor omitted clipboard blocks");
        assertThrows(NoSuchElementException.class, cursor::next);
    }

    @Test
    void aSingleBlockAtANegativeOriginIsVisitedOnce() {
        BlockVector3 source = BlockVector3.at(-23, 5, 41);
        ModularPasteCursor cursor = new ModularPasteCursor(source, source, new AffineTransform().rotateY(270), -1, -17);
        assertEquals(source, cursor.next());
        assertFalse(cursor.hasNext());
    }

    @Test
    void beginningALargePasteDoesNotMaterializeItsVolume() {
        ModularPasteCursor cursor = new ModularPasteCursor(BlockVector3.ZERO, BlockVector3.at(999_999, 255, 999_999),
                new AffineTransform(), 15, 31);
        assertEquals(BlockVector3.ZERO, cursor.next());
        assertEquals(BlockVector3.at(0, 1, 0), cursor.next(),
                "The first one-block-wide destination chunk must finish vertically before the next chunk");
        assertTrue(cursor.hasNext());
    }
}
