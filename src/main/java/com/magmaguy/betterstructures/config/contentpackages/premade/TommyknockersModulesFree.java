package com.magmaguy.betterstructures.config.contentpackages.premade;

import com.magmaguy.betterstructures.config.contentpackages.ContentPackageConfigFields;

import java.util.List;

public class TommyknockersModulesFree extends ContentPackageConfigFields {
    public TommyknockersModulesFree() {
        super("tommyknockers_modules_free",
                true,
                "&2Tommyknockers Modules Free",
                List.of("&fA modular Tommyknocker mine for BetterStructures, guarded by EliteMobs bosses."),
                "https://nightbreak.io/plugin/betterstructures/#tommyknockers-modules-free",
                "Betterstructures Tommyknockers Free Pack");
        setContentPackageType(ContentPackageType.MODULAR);
        setNightbreakSlug("tommyknockers-modules-free");
    }
}
