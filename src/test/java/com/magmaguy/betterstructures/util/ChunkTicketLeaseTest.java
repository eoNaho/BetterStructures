package com.magmaguy.betterstructures.util;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChunkTicketLeaseTest {
    @Test void overlappingScansAndPastesHoldTicketUntilLastOwnerFinishes() {
        World world = mock(World.class);
        Plugin plugin = mock(Plugin.class);
        Chunk chunk = mock(Chunk.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(world.getChunkAt(0, 0)).thenReturn(chunk);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.addPluginChunkTicket(plugin)).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class);
             var scan = new ChunkTicketLease(plugin);
             var continuation = new ChunkTicketLease(plugin);
             var paste = new ChunkTicketLease(plugin)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            assertTrue(scan.retainLoaded(world, new ChunkFootprint(0, 0, 0, 0)));
            scan.retain(chunk); // A readiness retry must not acquire another reference.
            continuation.retain(chunk);
            paste.retain(chunk);
            verify(chunk, times(1)).addPluginChunkTicket(plugin);
            scan.close();
            continuation.close();
            verify(chunk, never()).removePluginChunkTicket(plugin);
            paste.close();
            paste.close();
            verify(chunk, times(1)).removePluginChunkTicket(plugin);
        }
    }

    @Test void missingNeighboursAreNeverSynchronouslyLoadedAndExistingTicketsArePreserved() {
        World world = mock(World.class);
        Plugin plugin = mock(Plugin.class);
        Chunk chunk = mock(Chunk.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(world.getChunkAt(0, 0)).thenReturn(chunk);
        when(chunk.getWorld()).thenReturn(world);
        // false means the plugin already owns a ticket outside this lease.
        when(chunk.addPluginChunkTicket(plugin)).thenReturn(false);
        try (var bukkit = mockStatic(Bukkit.class); var lease = new ChunkTicketLease(plugin)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            assertFalse(lease.retainLoaded(world, new ChunkFootprint(0, 0, 1, 0)));
            verify(world, never()).getChunkAt(1, 0);
            lease.close();
            verify(chunk, never()).removePluginChunkTicket(plugin);
        }
    }
}
