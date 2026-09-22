package com.magmaguy.betterstructures.util;

import java.io.File;
import java.util.List;

public final class SchematicFileUtils {
    private SchematicFileUtils() {
    }

    public static void scanDirectoryForSchematics(File file, List<File> schematicFiles) {
        java.util.List<java.nio.file.Path> disabledRoots = new java.util.ArrayList<>();
        for (var content : com.magmaguy.betterstructures.config.contentpackages.ContentPackageConfig.getContentPackages().values()) {
            if (content.isEnabled()) continue;
            String base = content.getContentPackageType()
                    == com.magmaguy.betterstructures.config.contentpackages.ContentPackageConfigFields.ContentPackageType.MODULAR
                    ? "modules" : "schematics";
            java.nio.file.Path root = com.magmaguy.betterstructures.MetadataHandler.PLUGIN.getDataFolder()
                    .toPath().toAbsolutePath().normalize().resolve(base);
            String folder = content.getFolderName();
            if (folder == null || folder.isBlank()) continue;
            java.nio.file.Path packageRoot = root.resolve(folder).normalize();
            if (packageRoot.startsWith(root) && !packageRoot.equals(root)) disabledRoots.add(packageRoot);
        }
        scanEnabledDirectory(file, schematicFiles, disabledRoots);
    }

    private static void scanEnabledDirectory(File file, List<File> schematicFiles, java.util.List<java.nio.file.Path> disabledRoots) {
        java.nio.file.Path source = file.toPath().toAbsolutePath().normalize();
        for (java.nio.file.Path disabledRoot : disabledRoots) if (source.startsWith(disabledRoot)) return;
        if (file.getName().endsWith(".schem")) {
            schematicFiles.add(file);
        } else if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new java.io.UncheckedIOException(
                    new java.io.IOException("Could not enumerate schematic directory: " + file));
            for (File iteratedFile : children) scanEnabledDirectory(iteratedFile, schematicFiles, disabledRoots);
        }
    }

    public static String convertFromSchematicFilename(String schematicFilename) {
        return schematicFilename.replace(".schem", ".yml");
    }

    public static String convertFromConfigurationFilename(String configurationFilename) {
        return configurationFilename.replace(".yml", ".schem");
    }
}
