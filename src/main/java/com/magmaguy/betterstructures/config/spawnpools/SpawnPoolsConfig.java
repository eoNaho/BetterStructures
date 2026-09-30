package com.magmaguy.betterstructures.config.spawnpools;

import com.magmaguy.magmacore.config.CustomConfig;
import lombok.Getter;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpawnPoolsConfig extends CustomConfig {
    @Getter
    public static final HashMap<String, SpawnPoolsConfigFields> spawnPoolConfigFields = new HashMap<>();
    private static final Pattern POOL_TEXT_PATTERN = Pattern.compile("\\[pool:\\s*([^\\]]+)\\]");

    public SpawnPoolsConfig() {
        super("spawn_pools", "com.magmaguy.betterstructures.config.spawnpools.premade", SpawnPoolsConfigFields.class);
        spawnPoolConfigFields.clear();
        for (String key : super.getCustomConfigFieldsHashMap().keySet())
            spawnPoolConfigFields.put(key, (SpawnPoolsConfigFields) super.getCustomConfigFieldsHashMap().get(key));
    }

    public static SpawnPoolsConfigFields getConfigFields(String configurationFilename) {
        return spawnPoolConfigFields.get(configurationFilename);
    }

    /** A random entry of the pool, or null when the pool has no entries. */
    public static String pickBossFilename(SpawnPoolsConfigFields pool) {
        List<String> poolStrings = pool.getPoolStrings();
        if (poolStrings == null || poolStrings.isEmpty()) return null;
        return poolStrings.get(ThreadLocalRandom.current().nextInt(poolStrings.size()));
    }

    /** The pool name inside a "[pool:name]" sign line, or null when the line has no pool marker. */
    public static String extractPoolName(String signLine) {
        Matcher matcher = POOL_TEXT_PATTERN.matcher(signLine);
        return matcher.find() ? matcher.group(1) : null;
    }
}
