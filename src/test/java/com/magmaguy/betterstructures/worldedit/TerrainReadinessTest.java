package com.magmaguy.betterstructures.worldedit;

import com.magmaguy.betterstructures.buildingfitter.util.TerrainAdequacy;
import com.magmaguy.betterstructures.buildingfitter.util.Topology;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TerrainReadinessTest {
    @Test void undergroundScanRejectsMissingNeighborBeforeReadingTerrainOrClipboardBlocks() {
        World world = mock(World.class);
        Clipboard clipboard = mock(Clipboard.class);
        when(clipboard.getDimensions()).thenReturn(BlockVector3.at(33, 20, 33));
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.isChunkLoaded(1, 1)).thenReturn(false);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            assertThrows(IllegalStateException.class, () -> TerrainAdequacy.scan(3, clipboard,
                    new Location(world, 8, 20, 8), new Vector(-16, -10, -16), TerrainAdequacy.ScanType.UNDERGROUND));
            verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
            verify(world, never()).getChunkAt(anyInt(), anyInt());
            verify(clipboard, never()).getBlock(any());
        }
    }

    @Test void surfaceTopologyRejectsMissingNeighborBeforeHeightQueries() {
        World world = mock(World.class);
        Clipboard clipboard = mock(Clipboard.class);
        when(clipboard.getDimensions()).thenReturn(BlockVector3.at(33, 20, 33));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            assertThrows(IllegalStateException.class, () -> Topology.scan(100, 3, clipboard,
                    new Location(world, 8, 20, 8), new Vector(-16, -10, -16)));
            verify(world, never()).getHighestBlockYAt(any(Location.class));
            verify(world, never()).getHighestBlockAt(any(Location.class));
            verify(world, never()).getChunkAt(anyInt(), anyInt());
        }
    }
}
