package com.magmaguy.betterstructures.util;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Shared ownership: Bukkit has only one ticket per plugin/chunk, even for overlapping work. */
public final class ChunkTicketLease implements AutoCloseable {
    private record Key(UUID world, int x, int z, Plugin plugin) { }
    private static final class Ticket {
        final Chunk chunk;
        final boolean owned;
        int users = 1;
        Ticket(Chunk chunk, boolean owned) { this.chunk = chunk; this.owned = owned; }
    }
    private static final Map<Key, Ticket> shared = new HashMap<>();
    private final Map<Key, Ticket> held = new HashMap<>();
    private final Plugin plugin;

    public ChunkTicketLease(Plugin plugin) { this.plugin = plugin; }

    public void retain(Chunk chunk) {
        ChunkAccess.requireLoaded(chunk.getWorld(), chunk.getX(), chunk.getZ());
        Key key = new Key(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ(), plugin);
        if (held.containsKey(key)) return;
        Ticket ticket = shared.get(key);
        if (ticket == null) {
            ticket = new Ticket(chunk, chunk.addPluginChunkTicket(plugin));
            shared.put(key, ticket);
        } else ticket.users++;
        held.put(key, ticket);
    }

    /** Keep available neighbours while waiting, without loading or generating missing chunks. */
    public boolean retainLoaded(World world, ChunkFootprint footprint) {
        ChunkAccess.requireMainThread();
        boolean ready = true;
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                if (world.isChunkLoaded(x, z)) retain(ChunkAccess.loadedChunk(world, x, z));
                else ready = false;
            }
        }
        return ready;
    }

    @Override public void close() {
        ChunkAccess.requireMainThread();
        held.forEach((key, ticket) -> {
            if (--ticket.users == 0) {
                shared.remove(key);
                if (ticket.owned) ticket.chunk.removePluginChunkTicket(plugin);
            }
        });
        held.clear();
    }
}
