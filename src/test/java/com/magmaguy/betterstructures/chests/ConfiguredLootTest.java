package com.magmaguy.betterstructures.chests;

import com.magmaguy.betterstructures.config.treasures.TreasureConfigFields;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A deterministic definition is an independent oracle for the production loot roll. */
class ConfiguredLootTest {
    @TempDir Path directory;
    private ServerMock server;

    @BeforeEach void startServer() { server = MockBukkit.mock(); }
    @AfterEach void stopServer() { MockBukkit.unmock(); }

    @Test
    void definitionControlsMaterialAmountAndEnchantmentWithoutReplacingExistingItems() throws Exception {
        TreasureConfigFields definition = load("""
                isEnabled: true
                mean: 0
                standardDeviation: 0
                items:
                  guaranteed:
                    weight: 1
                    items:
                      - material: DIAMOND
                        amount: 7
                        weight: 1
                        procedurallyGenerateEnchantments: true
                procedurallyGeneratedItemSettings:
                  DIAMOND:
                    minecraft:unbreaking:
                      minLevel: 2
                      maxLevel: 2
                      chance: 1
                """);
        Container chest = chest();
        ItemStack existing = new ItemStack(Material.EMERALD, 3);
        existing.editMeta(meta -> meta.setDisplayName("Existing player cargo"));
        chest.getSnapshotInventory().setItem(0, existing.clone());

        definition.getChestContents().rollChestContents(chest);

        assertEquals(existing, chest.getSnapshotInventory().getItem(0), "Existing cargo must not be replaced");
        List<ItemStack> generated = Arrays.stream(chest.getSnapshotInventory().getContents())
                .filter(item -> item != null && item.getType() != Material.AIR && !item.equals(existing)).toList();
        assertEquals(1, generated.size(), "Zero mean and deviation specify one guaranteed stack");
        ItemStack actual = generated.getFirst();
        assertEquals(Material.DIAMOND, actual.getType(), "The configured material must be generated");
        assertEquals(7, actual.getAmount(), "The configured fixed amount must be generated");
        assertEquals(2, actual.getEnchantmentLevel(Enchantment.UNBREAKING), "The guaranteed configured enchantment must survive generation");
        assertEquals(1, actual.getEnchantments().size(), "No unconfigured enchantment may appear");
    }

    @Test
    void emptyDefinitionLeavesExistingInventoryUnchanged() throws Exception {
        TreasureConfigFields definition = load("""
                isEnabled: true
                mean: 0
                standardDeviation: 0
                items: {}
                procedurallyGeneratedItemSettings: {}
                """);
        Container chest = chest();
        chest.getSnapshotInventory().setItem(4, new ItemStack(Material.EMERALD, 3));
        ItemStack[] before = Arrays.stream(chest.getSnapshotInventory().getContents())
                .map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);

        definition.getChestContents().rollChestContents(chest);

        assertArrayEquals(before, chest.getSnapshotInventory().getContents());
    }

    private TreasureConfigFields load(String yaml) throws Exception {
        Path file = directory.resolve("deterministic-loot.yml");
        Files.writeString(file, yaml);
        TreasureConfigFields definition = new TreasureConfigFields(file.getFileName().toString(), true);
        definition.setFile(file.toFile());
        definition.setFileConfiguration(YamlConfiguration.loadConfiguration(file.toFile()));
        definition.processConfigFields();
        return definition;
    }

    private Container chest() {
        var block = server.addSimpleWorld("loot").getBlockAt(0, 64, 0);
        block.setType(Material.CHEST);
        return (Container) block.getState();
    }
}
