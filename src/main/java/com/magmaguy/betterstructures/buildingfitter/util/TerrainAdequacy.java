package com.magmaguy.betterstructures.buildingfitter.util;

import com.magmaguy.betterstructures.util.ChunkFootprint;
import com.magmaguy.betterstructures.util.ChunkAccess;
import com.magmaguy.betterstructures.util.SurfaceMaterials;
import com.magmaguy.betterstructures.util.WorldEditUtils;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Vector;

public class TerrainAdequacy {
    private static final int MAX_SAMPLES_PER_CANDIDATE = 1024;
    public enum ScanType {
        SURFACE,
        UNDERGROUND,
        AIR,
        LIQUID
    }

    public static double scan(int scanStep, Clipboard schematicClipboard, Location iteratedLocation, Vector schematicOffset, ScanType scanType) {
        int width = schematicClipboard.getDimensions().x();
        int depth = schematicClipboard.getDimensions().z();
        int height = schematicClipboard.getDimensions().y();
        int effectiveStep = boundedStep(scanStep, width, height, depth);

        if (!ChunkFootprint.blocks(
                iteratedLocation.getX() + schematicOffset.getX(), iteratedLocation.getZ() + schematicOffset.getZ(),
                iteratedLocation.getX() + schematicOffset.getX() + width - 1,
                iteratedLocation.getZ() + schematicOffset.getZ() + depth - 1).isLoaded(iteratedLocation.getWorld()))
            throw new IllegalStateException("Terrain footprint is not loaded");

        //Clipboard reads are absolute: the region spans [minimumPoint, maximumPoint], and reads
        //outside it silently return air. Zero-based coordinates must be offset by the minimum
        //point, the same way SchematicContainer and Schematic read clipboards.
        BlockVector3 minimumPoint = schematicClipboard.getMinimumPoint();

        int totalCount = 0;
        int negativeCount = 0;

        for (int x = 0; x < width; x += effectiveStep) {
            for (int y = 0; y < height; y += effectiveStep) {
                for (int z = 0; z < depth; z += effectiveStep) {
                    BlockState schematicBlockStateAtPosition = schematicClipboard.getBlock(BlockVector3.at(x, y, z).add(minimumPoint));
                    Material schematicMaterialAtPosition = WorldEditUtils.adaptMaterial(schematicBlockStateAtPosition);
                    boolean schematicBlockIsAir = WorldEditUtils.isAir(schematicBlockStateAtPosition);
                    boolean schematicBlockIsLiquid = schematicMaterialAtPosition == Material.WATER || schematicMaterialAtPosition == Material.LAVA;
                    Location projectedLocation = LocationProjector.project(iteratedLocation, schematicOffset, new Vector(x, y, z));
                    if (!isBlockAdequate(projectedLocation, schematicBlockIsAir, schematicBlockIsLiquid, iteratedLocation.getBlockY() - 1, scanType))
                        negativeCount++;
                    totalCount++;
                }
            }
        }

        double score = 100 - negativeCount * 100D / (double) totalCount;

        return score;
    }

    /** Keep one candidate's main-thread terrain probe bounded for unusually large schematics. */
    private static int boundedStep(int requestedStep, int width, int height, int depth) {
        int step = Math.max(1, requestedStep);
        double points = (double) width * height * depth;
        if (points <= 0) return step;
        int estimate = (int) Math.ceil(Math.cbrt(points / MAX_SAMPLES_PER_CANDIDATE));
        step = Math.max(step, estimate);
        while (sampleCount(width, step) * sampleCount(height, step) * sampleCount(depth, step)
                > MAX_SAMPLES_PER_CANDIDATE) step++;
        return step;
    }

    private static long sampleCount(int dimension, int step) {
        return ((long) dimension + step - 1) / step;
    }

    private static boolean isBlockAdequate(Location projectedWorldLocation, boolean schematicBlockIsAir, boolean schematicBlockIsLiquid, int floorHeight, ScanType scanType) {
        int floorYValue = projectedWorldLocation.getBlockY();
        Material terrain = ChunkAccess.block(projectedWorldLocation).getType();
        if (terrain == Material.VOID_AIR) return false;
        switch (scanType) {
            case SURFACE:
                if (floorYValue > floorHeight)
                    //for air level
                    return SurfaceMaterials.ignorable(terrain) || !schematicBlockIsAir;
                else
                    //for underground level
                    return !terrain.isAir();
            case AIR:
                return terrain.isAir();
            case UNDERGROUND:
                return terrain.isSolid();
            case LIQUID:
                if (floorYValue > floorHeight) {
                    //for air level
                    return terrain.isAir();
                } else {
                    //for underwater level
                    if (schematicBlockIsLiquid)
                        return terrain == Material.WATER || terrain == Material.LAVA;
                    else
                        return true;
                }
            default:
                return false;
        }

    }
}
