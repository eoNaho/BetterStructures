package com.magmaguy.betterstructures.listeners;

import com.magmaguy.betterstructures.util.ChunkFootprint;
import com.magmaguy.betterstructures.util.ChunkAccess;
import com.magmaguy.betterstructures.buildingfitter.FitAirBuilding;
import com.magmaguy.betterstructures.buildingfitter.FitLiquidBuilding;
import com.magmaguy.betterstructures.buildingfitter.FitSurfaceBuilding;
import com.magmaguy.betterstructures.buildingfitter.FitUndergroundShallowBuilding;
import com.magmaguy.betterstructures.buildingfitter.util.FitUndergroundDeepBuilding;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.config.ValidWorldsConfig;
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfig;
import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfigFields;
import com.magmaguy.betterstructures.modules.WFCGenerator;
import com.magmaguy.betterstructures.util.ChunkPregenerator;
import com.magmaguy.betterstructures.modules.NaturalDungeonReservation;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import com.magmaguy.betterstructures.thirdparty.WorldGuard;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class NewChunkLoadEvent implements Listener {

    private static final Map<LoadingChunkKey, Long> recentlyGeneratedChunks = new LinkedHashMap<>();
    private static final long RECENT_NEW_CHUNK_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final int MAX_RECENT_NEW_CHUNKS = 65_536;

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk()) return;
        LoadingChunkKey key = LoadingChunkKey.from(event.getChunk());
        purgeExpiredGeneratedChunks();
        if (recentlyGeneratedChunks.containsKey(key)) return;
        rememberGeneratedChunk(key);
        DeferredChunkWork.submit(key, key.worldId(),
                new ChunkFootprint(key.x(), key.z(), key.x(), key.z()),
                "new chunk " + key.x() + "," + key.z(),
                world -> scanNewChunk(ChunkAccess.loadedChunk(world, key.x(), key.z()), key));
    }

    private static void scanNewChunk(Chunk chunk, LoadingChunkKey key) {
        if (!ValidWorldsConfig.isValidWorld(chunk.getWorld())) return;
        surfaceScanner(chunk);
        shallowUndergroundScanner(chunk);
        deepUndergroundScanner(chunk);
        skyScanner(chunk);
        liquidSurfaceScanner(chunk);
        scheduleDungeonScanner(key);
    }

    public static void prepareForContentReload() {
        // Fitter continuations refer to the old content. Drop them before replacing it.
        DeferredChunkWork.clear();
        recentlyGeneratedChunks.clear();
    }

    public static void replayDeferredNewChunks() { DeferredChunkWork.start(); }
    public static void discardDeferredNewChunks() { DeferredChunkWork.clear(); }
    public static void shutdown() {
        DeferredChunkWork.clear();
        recentlyGeneratedChunks.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(org.bukkit.event.world.WorldUnloadEvent event) {
        UUID id = event.getWorld().getUID();
        DeferredChunkWork.discardWorld(id);
        recentlyGeneratedChunks.keySet().removeIf(key -> key.worldId().equals(id));
        com.magmaguy.betterstructures.worldedit.Schematic.discardWorld(id);
        ChunkPregenerator.getActivePregenerators().stream()
                .filter(generator -> generator.getWorld().getUID().equals(id))
                .forEach(ChunkPregenerator::cancel);
    }

    private record LoadingChunkKey(UUID worldId, int x, int z) {
        private static LoadingChunkKey from(Chunk chunk) {
            return new LoadingChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        }
    }

    private static void rememberGeneratedChunk(LoadingChunkKey key) {
        purgeExpiredGeneratedChunks();
        recentlyGeneratedChunks.remove(key);
        recentlyGeneratedChunks.put(key, System.nanoTime());
        while (recentlyGeneratedChunks.size() > MAX_RECENT_NEW_CHUNKS) {
            var iterator = recentlyGeneratedChunks.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
    }

    private static void purgeExpiredGeneratedChunks() {
        long cutoff = System.nanoTime() - RECENT_NEW_CHUNK_NANOS;
        var iterator = recentlyGeneratedChunks.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() >= cutoff) break;
            iterator.remove();
        }
    }

    private static boolean wasRecentlyGenerated(UUID worldId, NaturalDungeonReservation.ChunkCoordinate coordinate) {
        LoadingChunkKey key = new LoadingChunkKey(worldId, coordinate.x(), coordinate.z());
        Long observed = recentlyGeneratedChunks.get(key);
        if (observed == null) return false;
        if (System.nanoTime() - observed > RECENT_NEW_CHUNK_NANOS) {
            recentlyGeneratedChunks.remove(key);
            return false;
        }
        return true;
    }

    private static void scheduleDungeonScanner(LoadingChunkKey key) {
        DeferredChunkWork.submit(new DungeonKey(key), key.worldId(),
                new ChunkFootprint(key.x(), key.z(), key.x(), key.z()),
                "dungeon scan " + key.x() + "," + key.z(),
                world -> dungeonScanner(ChunkAccess.loadedChunk(world, key.x(), key.z())));
    }

    private record DungeonKey(LoadingChunkKey chunk) { }

    /**
     * Determines if the given chunk is a valid structure position based on
     * a diamond grid pattern with seeded random offsets.
     *
     * @param chunk The chunk to check
     * @param structureType The type of structure
     * @param gridDistance The distance between grid points
     * @param maxOffset The maximum random offset from grid points
     * @return True if this chunk should have a structure
     */
    private static boolean isValidStructurePosition(Chunk chunk, GeneratorConfigFields.StructureType structureType,
                                                    int gridDistance, int maxOffset) {
        int x = chunk.getX();
        int z = chunk.getZ();

        // Check spawn protection radius (2D distance from 0,0 in blocks)
        int spawnProtectionRadius = DefaultConfig.getSpawnProtectionRadius();
        if (spawnProtectionRadius > 0) {
            int blockX = x * 16 + 8;
            int blockZ = z * 16 + 8;
            if ((long) blockX * blockX + (long) blockZ * blockZ < (long) spawnProtectionRadius * spawnProtectionRadius) {
                return false;
            }
        }

        long worldSeed = chunk.getWorld().getSeed();

        // Create a unique seed for each structure type
        long typeSeed = worldSeed + structureType.name().hashCode() * 7919; // Use a prime number for better distribution

        // Check all nearby grid cells that could have a structure landing on this chunk
        long minimumGridX = ((long) x - maxOffset) / gridDistance - 1;
        long maximumGridX = ((long) x + maxOffset) / gridDistance + 1;
        long minimumGridZ = ((long) z - maxOffset) / gridDistance - 1;
        long maximumGridZ = ((long) z + maxOffset) / gridDistance + 1;
        int offsetBound = (int) (2L * maxOffset + 1L);
        for (long gridX = minimumGridX; gridX <= maximumGridX; gridX++) {
            for (long gridZ = minimumGridZ; gridZ <= maximumGridZ; gridZ++) {
                // Base position of this grid cell
                long baseX = gridX * gridDistance;
                long baseZ = gridZ * gridDistance;

                // Apply diamond pattern offset (shift every other row by gridDistance/2)
                if (gridZ % 2L != 0) {
                    baseX += gridDistance / 2;
                }

                // Create a seeded random for this specific grid cell
                Random cellRandom = new Random(typeSeed ^ (((long)baseX << 32) | (baseZ & 0xFFFFFFFFL)));

                // Generate the random offset for structure in this grid cell
                int offsetX = maxOffset > 0 ? cellRandom.nextInt(offsetBound) - maxOffset : 0;
                int offsetZ = maxOffset > 0 ? cellRandom.nextInt(offsetBound) - maxOffset : 0;

                // Final structure position for this grid cell
                long structureX = baseX + offsetX;
                long structureZ = baseZ + offsetZ;

                // If this chunk matches the structure position
                if (x == structureX && z == structureZ) {
                    return true;
                }
            }
        }

        return false;
    }

    private static void surfaceScanner(Chunk chunk) {
        if (SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.SURFACE).isEmpty()) return;
        // Get config values directly instead of using static finals
        if (!isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.SURFACE,
                DefaultConfig.getDistanceSurface(), DefaultConfig.getMaxOffsetSurface())) return;
        new FitSurfaceBuilding(chunk);
    }

    private static void shallowUndergroundScanner(Chunk chunk) {
        if (!DefaultConfig.isShallowUndergroundScannerEnabled()) return;
        if (SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.UNDERGROUND_SHALLOW).isEmpty()) return;
        if (!isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.UNDERGROUND_SHALLOW,
                DefaultConfig.getDistanceShallow(), DefaultConfig.getMaxOffsetShallow())) return;
        FitUndergroundShallowBuilding.fit(chunk);
    }

    private static void deepUndergroundScanner(Chunk chunk) {
        if (SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.UNDERGROUND_DEEP).isEmpty()) return;
        if (!isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.UNDERGROUND_DEEP,
                DefaultConfig.getDistanceDeep(), DefaultConfig.getMaxOffsetDeep())) return;
        FitUndergroundDeepBuilding.fit(chunk);
    }

    private static void skyScanner(Chunk chunk) {
        if (SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.SKY).isEmpty()) return;
        if (!isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.SKY,
                DefaultConfig.getDistanceSky(), DefaultConfig.getMaxOffsetSky())) return;
        new FitAirBuilding(chunk);
    }

    private static void liquidSurfaceScanner(Chunk chunk) {
        if (SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.LIQUID_SURFACE).isEmpty()) return;
        if (!isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.LIQUID_SURFACE,
                DefaultConfig.getDistanceLiquid(), DefaultConfig.getMaxOffsetLiquid())) return;
        new FitLiquidBuilding(chunk);
    }

    private static void dungeonScanner(Chunk chunk) {
        if (ModuleGeneratorsConfig.getModuleGenerators().isEmpty()) return;
        if (!isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.DUNGEON,
                DefaultConfig.getDistanceDungeon(), DefaultConfig.getMaxOffsetDungeon())) return;
        List<ModuleGeneratorsConfigFields> validatedGenerators = new ArrayList<>();
        for (ModuleGeneratorsConfigFields moduleGeneratorsConfigFields : ModuleGeneratorsConfig.getModuleGenerators().values()){
            if (!moduleGeneratorsConfigFields.isEnabled()) continue;
            if (moduleGeneratorsConfigFields.getValidWorlds() != null && !moduleGeneratorsConfigFields.getValidWorlds().isEmpty() && !moduleGeneratorsConfigFields.getValidWorlds().contains(chunk.getWorld().getName())) continue;
            if (moduleGeneratorsConfigFields.getValidWorldEnvironments() != null && !moduleGeneratorsConfigFields.getValidWorldEnvironments().isEmpty() && !moduleGeneratorsConfigFields.getValidWorldEnvironments().contains(chunk.getWorld().getEnvironment())) continue;
            validatedGenerators.add(moduleGeneratorsConfigFields);
        }
        if (validatedGenerators.isEmpty()) return;
        ModuleGeneratorsConfigFields moduleGeneratorsConfigFields = validatedGenerators.get(ThreadLocalRandom.current().nextInt(0, validatedGenerators.size()));
        var startLocation = new org.bukkit.Location(chunk.getWorld(), chunk.getX() * 16 + 8,
                moduleGeneratorsConfigFields.getCenterModuleAltitude(), chunk.getZ() * 16 + 8);
        var bounds = NaturalDungeonReservation.blockBounds(startLocation.getBlockX(), startLocation.getBlockZ(),
                moduleGeneratorsConfigFields.getRadius(), moduleGeneratorsConfigFields.getModuleSizeXZ());
        var footprint = new ChunkFootprint(
                bounds.minChunkX(), bounds.minChunkZ(), bounds.maxChunkX(), bounds.maxChunkZ());
        DeferredChunkWork.submit(new Object(), chunk.getWorld().getUID(), footprint, "natural dungeon footprint",
                world -> finishDungeonScan(world, startLocation, moduleGeneratorsConfigFields, footprint));
    }

    private static void finishDungeonScan(World world, org.bukkit.Location startLocation,
                                         ModuleGeneratorsConfigFields moduleGeneratorsConfigFields,
                                         ChunkFootprint footprint) {
        UUID worldId = world.getUID();
        var reservation = NaturalDungeonReservation.tryCreate(
                startLocation.getBlockX(),
                startLocation.getBlockZ(),
                moduleGeneratorsConfigFields.getRadius(),
                moduleGeneratorsConfigFields.getModuleSizeXZ(),
                DefaultConfig.getSpawnProtectionRadius(),
                coordinate -> ChunkAccess.generatedIfLoaded(world, coordinate.x(), coordinate.z()),
                coordinate -> wasRecentlyGenerated(worldId, coordinate));
        if (reservation.isEmpty()) {
            Logger.info("Skipped natural modular generation because its full footprint overlaps "
                    + "spawn protection or a chunk that was not just generated.");
            return;
        }

        NaturalDungeonReservation naturalReservation = reservation.get();
        if (hasWorldGuardOverlap(world, naturalReservation.blockBounds())) {
            Logger.info("Skipped natural modular generation because its full footprint overlaps "
                    + "a WorldGuard region.");
            return;
        }

        WFCGenerator.generateNaturally(
                moduleGeneratorsConfigFields,
                startLocation,
                () -> Bukkit.getWorld(worldId) == world && footprint.isLoaded(world)
                        && naturalReservation.remainsSafe(
                        coordinate -> ChunkAccess.generatedIfLoaded(world, coordinate.x(), coordinate.z()))
                        && !hasWorldGuardOverlap(world, naturalReservation.blockBounds()));
    }

    private static boolean hasWorldGuardOverlap(
            World world,
            NaturalDungeonReservation.BlockBounds bounds) {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) return false;
        try {
            return WorldGuard.hasRegionOverlap(world, bounds);
        } catch (Throwable throwable) {
            Logger.warn("Could not verify WorldGuard regions for natural modular generation; "
                    + "the paste was cancelled: " + throwable.getMessage());
            return true;
        }
    }
}
