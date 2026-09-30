package com.magmaguy.betterstructures.config.contentpackages.premade;

import com.magmaguy.betterstructures.config.contentpackages.ContentPackageConfigFields;

import java.util.List;

public class TommyknockersModulesPremium extends ContentPackageConfigFields {
    public TommyknockersModulesPremium() {
        super("tommyknockers_modules_premium",
                true,
                "&2Tommyknockers Modules Premium",
                List.of("&fMore Tommyknocker mine modules and five more EliteMobs bosses.",
                        "&cRequires Tommyknockers Modules Free."),
                "https://nightbreak.io/plugin/betterstructures/#tommyknockers-modules-premium",
                "Betterstructures Tommyknockers Premium Pack");
        setContentPackageType(ContentPackageType.MODULAR);
        setNightbreakSlug("tommyknockers-modules-premium");
    }
}
