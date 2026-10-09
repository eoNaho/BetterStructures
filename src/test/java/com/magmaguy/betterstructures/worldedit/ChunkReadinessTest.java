package com.magmaguy.betterstructures.worldedit;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.util.ChunkAccess;
import com.magmaguy.betterstructures.util.ChunkFootprint;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChunkReadinessTest {
    @Test void missingChunkNeverUsesSynchronousRetrievalOrGeneratedQuery() {
        World world = mock(World.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            assertThrows(IllegalStateException.class, () -> ChunkAccess.loadedChunk(world, -1, 2));
            assertFalse(ChunkAccess.generatedIfLoaded(world, -1, 2));
            verify(world, never()).getChunkAt(anyInt(), anyInt());
            verify(world, never()).isChunkGenerated(anyInt(), anyInt());
        }
    }

    @Test void footprintsFloorNegativeFractionalCoordinatesAndOnlyInspectLoadedState() {
        assertEquals(new ChunkFootprint(-2, -1, 1, 0), ChunkFootprint.blocks(-16.5, -.5, 16, 15.9));
        World world = mock(World.class);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.isChunkLoaded(-1, 0)).thenReturn(false);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            assertFalse(new ChunkFootprint(-2, -1, 1, 0).isLoaded(world));
            verify(world, never()).getChunkAt(anyInt(), anyInt());
            verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        }
    }

    @Test void stalledAsyncLoadReturnsImmediatelyAndTimesOut() throws Exception {
        World world = mock(World.class);
        UUID id = UUID.randomUUID();
        when(world.getUID()).thenReturn(id);
        when(world.getChunkAtAsync(13, 26, true)).thenReturn(new CompletableFuture<>());
        try (var bukkit = mockStatic(Bukkit.class); var readiness = new PasteChunkReadiness(world)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(() -> Bukkit.getWorld(id)).thenReturn(world);
            Location at = new Location(world, 13 * 16, 20, 26 * 16);
            assertFalse(readiness.ready(at));
            assertFalse(readiness.ready(at));
            Field time = PasteChunkReadiness.class.getDeclaredField("requestedAt");
            time.setAccessible(true);
            time.setLong(readiness, System.nanoTime() - 61_000_000_000L);
            assertThrows(IllegalStateException.class, () -> readiness.ready(at));
            verify(world, never()).getChunkAt(anyInt(), anyInt());
        }
    }

    @Test void completedAsyncLoadIsRecheckedBeforeTicketAndTicketReleasedOnClose() {
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        UUID id = UUID.randomUUID();
        when(world.getUID()).thenReturn(id);
        when(chunk.getX()).thenReturn(1);
        when(chunk.getZ()).thenReturn(2);
        var future = new CompletableFuture<Chunk>();
        when(world.getChunkAtAsync(1, 2, true)).thenReturn(future);
        MetadataHandler.PLUGIN = mock(JavaPlugin.class);
        try (var bukkit = mockStatic(Bukkit.class); var readiness = new PasteChunkReadiness(world)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(() -> Bukkit.getWorld(id)).thenReturn(world);
            Location at = new Location(world, 16, 20, 32);
            assertFalse(readiness.ready(at));
            future.complete(chunk);
            when(world.isChunkLoaded(1, 2)).thenReturn(true);
            assertTrue(readiness.ready(at));
            verify(chunk).addPluginChunkTicket(MetadataHandler.PLUGIN);
            readiness.close();
            verify(chunk).removePluginChunkTicket(MetadataHandler.PLUGIN);
            verify(world, never()).getChunkAt(anyInt(), anyInt());
        } finally { MetadataHandler.PLUGIN = null; }
    }

    @Test void liveWorldAccessRejectsWorkerThreads() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            assertThrows(IllegalStateException.class, () -> ChunkAccess.loadedChunk(mock(World.class), 0, 0));
        }
    }
}
