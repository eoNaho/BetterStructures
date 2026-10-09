package com.magmaguy.betterstructures.listeners;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.util.ChunkFootprint;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeferredChunkWorkTest {
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
