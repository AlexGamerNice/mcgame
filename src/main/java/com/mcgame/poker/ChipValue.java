package com.mcgame.poker;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public enum ChipValue {
    IRON(Material.IRON_INGOT, "chips.iron_ingot", 1, "Iron Ingot"),
    GOLD(Material.GOLD_INGOT, "chips.gold_ingot", 8, "Gold Ingot"),
    EMERALD(Material.EMERALD, "chips.emerald", 64, "Emerald"),
    DIAMOND(Material.DIAMOND, "chips.diamond", 512, "Diamond"),
    NETHERITE_SCRAP(Material.NETHERITE_SCRAP, "chips.netherite_scrap", 2048, "Netherite Scrap"),
    NETHERITE_INGOT(Material.NETHERITE_INGOT, "chips.netherite_ingot", 8192, "Netherite Ingot");

    private static final List<ChipValue> ASC = Collections.unmodifiableList(Arrays.asList(values()));
    private static final List<ChipValue> DESC = Collections.unmodifiableList(
            Arrays.asList(NETHERITE_INGOT, NETHERITE_SCRAP, DIAMOND, EMERALD, GOLD, IRON)
    );
    private static final Map<ChipValue, Integer> CONFIGURED_VALUES = new EnumMap<>(ChipValue.class);

    private final Material material;
    private final String configPath;
    private final int defaultValue;
    private final String displayName;

    static {
        for (ChipValue chip : values()) {
            CONFIGURED_VALUES.put(chip, chip.defaultValue);
        }
    }

    ChipValue(Material material, String configPath, int defaultValue, String displayName) {
        this.material = material;
        this.configPath = configPath;
        this.defaultValue = defaultValue;
        this.displayName = displayName;
    }

    public Material material() {
        return material;
    }

    public int value() {
        return CONFIGURED_VALUES.getOrDefault(this, defaultValue);
    }

    public String configPath() {
        return configPath;
    }

    public int defaultValue() {
        return defaultValue;
    }

    public String display() {
        return displayName + " = " + value() + " credits";
    }

    public static List<ChipValue> ascending() {
        return ASC;
    }

    public static List<ChipValue> descending() {
        return DESC;
    }

    public static boolean isChip(Material material) {
        for (ChipValue chip : values()) {
            if (chip.material == material) {
                return true;
            }
        }
        return false;
    }

    public static int totalFromInventory(Inventory inventory) {
        int total = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack == null) {
                continue;
            }
            for (ChipValue chip : values()) {
                if (stack.getType() == chip.material) {
                    total += chip.value() * stack.getAmount();
                    break;
                }
            }
        }
        return total;
    }

    public static void loadFromConfig(FileConfiguration config, Logger logger) {
        for (ChipValue chip : values()) {
            int configured = config.getInt(chip.configPath, chip.defaultValue);
            if (configured <= 0) {
                logger.warning("Invalid chip value for " + chip.configPath
                        + ". Using default " + chip.defaultValue + ".");
                configured = chip.defaultValue;
            }
            CONFIGURED_VALUES.put(chip, configured);
        }
    }
}
