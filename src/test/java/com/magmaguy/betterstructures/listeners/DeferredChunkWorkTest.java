package com.magmaguy.betterstructures.listeners;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.util.ChunkFootprint;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.world.ChunkLoadEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeferredChunkWorkTest {
    @Test void chunkyUnloadedAnchorIsReloadedWithoutGeneratingAndRetainedUntilScan() throws Exception {
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        UUID id = UUID.randomUUID();
        var previous = MetadataHandler.PLUGIN;
        JavaPlugin plugin = mock(JavaPlugin.class);
        MetadataHandler.PLUGIN = plugin;
        when(world.getUID()).thenReturn(id);
        when(world.getChunkAt(0, 0)).thenReturn(chunk);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.addPluginChunkTicket(plugin)).thenReturn(true);
        var future = new CompletableFuture<Chunk>();
        when(world.getChunkAtAsync(0, 0, false)).thenReturn(future);
        var scans = new AtomicInteger();
        try (var bukkit = mockStatic(Bukkit.class); var config = mockStatic(DefaultConfig.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(() -> Bukkit.getWorld(id)).thenReturn(world);
            config.when(DefaultConfig::getMaxChunkScansPerTick).thenReturn(2);
            config.when(DefaultConfig::getChunkScanBudgetMilliseconds).thenReturn(10.0);
            DeferredChunkWork.submitFromChunkLoad(new Object(), id, new ChunkFootprint(0, 0, 0, 0),
                    "new chunk", loaded -> scans.incrementAndGet());
            verify(world, never()).getChunkAtAsync(anyInt(), anyInt(), anyBoolean());
            var drain = DeferredChunkWork.class.getDeclaredMethod("drain");
            drain.setAccessible(true);
            drain.invoke(null);
            verify(world).getChunkAtAsync(0, 0, false);
            verify(world, never()).getChunkAt(anyInt(), anyInt());
            assertEquals(0, scans.get());
            when(world.isChunkLoaded(0, 0)).thenReturn(true);
            future.complete(chunk);
            // Hold as soon as the async load completes, before the backoff check.
            verify(chunk).addPluginChunkTicket(plugin);
            drain.invoke(null);
            drain.invoke(null);
            assertEquals(1, scans.get());
            verify(chunk, times(1)).addPluginChunkTicket(plugin);
            verify(chunk, times(1)).removePluginChunkTicket(plugin);
            verify(world, never()).getChunkAtAsync(anyInt(), anyInt(), eq(true));

            var cancelled = new CompletableFuture<Chunk>();
            when(world.isChunkLoaded(0, 0)).thenReturn(false);
            when(world.getChunkAtAsync(0, 0, false)).thenReturn(cancelled);
            DeferredChunkWork.submitFromChunkLoad(new Object(), id, new ChunkFootprint(0, 0, 0, 0),
                    "cancelled scan", loaded -> fail());
            drain.invoke(null);
            DeferredChunkWork.discardWorld(id);
            assertTrue(cancelled.isCancelled());
        } finally {
            try (var bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
                DeferredChunkWork.clear();
            }
            MetadataHandler.PLUGIN = previous;
        }
    }

    @Test void chunkLoadCallbackDoesNotRetrieveOrTicketChunkUntilNextTick() throws Exception {
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        UUID id = UUID.randomUUID();
        var previous = MetadataHandler.PLUGIN;
        JavaPlugin plugin = mock(JavaPlugin.class);
        MetadataHandler.PLUGIN = plugin;
        when(world.getUID()).thenReturn(id);
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(world.getChunkAt(0, 0)).thenReturn(chunk);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.addPluginChunkTicket(plugin)).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class); var config = mockStatic(DefaultConfig.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(() -> Bukkit.getWorld(id)).thenReturn(world);
            config.when(DefaultConfig::getMaxChunkScansPerTick).thenReturn(2);
            config.when(DefaultConfig::getChunkScanBudgetMilliseconds).thenReturn(10.0);
            // Purpur/Paper may report loaded here, yet still be inside its load callback.
            new NewChunkLoadEvent().onChunkLoad(new ChunkLoadEvent(chunk, true));
            verify(world, never()).isChunkLoaded(anyInt(), anyInt());
            verify(world, never()).getChunkAt(anyInt(), anyInt());
            verify(chunk, never()).addPluginChunkTicket(any());

            var drain = DeferredChunkWork.class.getDeclaredMethod("drain");
            drain.setAccessible(true);
            // ValidWorldsConfig rejects this mock world; acquiring/releasing still happens.
            try (var worlds = mockStatic(com.magmaguy.betterstructures.config.ValidWorldsConfig.class)) {
                drain.invoke(null);
            }
            verify(chunk, times(1)).addPluginChunkTicket(plugin);
            verify(chunk, times(1)).removePluginChunkTicket(plugin);
        } finally {
            try (var bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
                NewChunkLoadEvent.shutdown();
            }
            MetadataHandler.PLUGIN = previous;
        }
    }

    @Test void pendingChunkIsHeldAcrossSuccessorAndReleasedAfterScan() throws Exception {
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        UUID id = UUID.randomUUID();
        var previous = MetadataHandler.PLUGIN;
        JavaPlugin plugin = mock(JavaPlugin.class);
        MetadataHandler.PLUGIN = plugin;
        when(world.getUID()).thenReturn(id);
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(world.getChunkAt(0, 0)).thenReturn(chunk);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.addPluginChunkTicket(plugin)).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var scans = new AtomicInteger();
        var footprint = new ChunkFootprint(0, 0, 0, 0);
        try (var bukkit = mockStatic(Bukkit.class); var config = mockStatic(DefaultConfig.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(() -> Bukkit.getWorld(id)).thenReturn(world);
            config.when(DefaultConfig::getMaxChunkScansPerTick).thenReturn(2);
            config.when(DefaultConfig::getChunkScanBudgetMilliseconds).thenReturn(10.0);
            Object key = new Object();
            DeferredChunkWork.submit(key, id, footprint, "new chunk", loaded -> {
                scans.incrementAndGet();
                DeferredChunkWork.submit(new Object(), id, footprint, "continuation", next -> scans.incrementAndGet());
            });
            DeferredChunkWork.submit(key, id, footprint, "duplicate", loaded -> fail());
            verify(chunk, times(1)).addPluginChunkTicket(plugin);
            var drain = DeferredChunkWork.class.getDeclaredMethod("drain");
            drain.setAccessible(true);
            drain.invoke(null);
            assertEquals(1, scans.get());
            verify(chunk, never()).removePluginChunkTicket(plugin);
            drain.invoke(null);
            assertEquals(2, scans.get());
            verify(chunk, times(1)).removePluginChunkTicket(plugin);

            DeferredChunkWork.submit(new Object(), id, footprint, "world unload", loaded -> fail());
            DeferredChunkWork.discardWorld(id);
            verify(chunk, times(2)).removePluginChunkTicket(plugin);
            DeferredChunkWork.submit(new Object(), id, footprint, "reload", loaded -> fail());
            DeferredChunkWork.clear();
            verify(chunk, times(3)).removePluginChunkTicket(plugin);
        } finally {
            MetadataHandler.PLUGIN = previous;
        }
    }
}
