package com.magmaguy.betterstructures.util;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class WeighedProbability {

    /**
     * Picks a key from the map with a probability proportional to its weight.
     *
     * @return the picked key, or null when nothing could be picked (empty map or non-positive
     * weights) - callers must handle null
     */
    public static Integer pickWeightedProbability(Map<Integer, Double> weighedValues) {

        double maximum = 0;
        for (Double weight : weighedValues.values()) {
            if (weight == null || !Double.isFinite(weight) || weight < 0) return null;
            maximum = Math.max(maximum, weight);
        }
        if (maximum == 0) return null;
        // Scaling preserves ratios without overflowing when finite weights have a huge sum.
        double totalWeight = 0;
        for (double weight : weighedValues.values()) totalWeight += weight / maximum;
        double random = ThreadLocalRandom.current().nextDouble(totalWeight);
        Integer lastPositive = null;
        for (Map.Entry<Integer, Double> entry : weighedValues.entrySet()) {
            if (entry.getValue() == 0) continue;
            lastPositive = entry.getKey();
            random -= entry.getValue() / maximum;
            if (random < 0) return entry.getKey();
        }
        return lastPositive;
    }

}
