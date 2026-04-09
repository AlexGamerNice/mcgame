package com.mcgame.poker;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public enum ChipValue {
    IRON(Material.IRON_INGOT, 1, "Iron Ingot"),
    GOLD(Material.GOLD_INGOT, 8, "Gold Ingot"),
    EMERALD(Material.EMERALD, 64, "Emerald"),
    DIAMOND(Material.DIAMOND, 512, "Diamond"),
    NETHERITE_SCRAP(Material.NETHERITE_SCRAP, 2048, "Netherite Scrap"),
    NETHERITE_INGOT(Material.NETHERITE_INGOT, 8192, "Netherite Ingot");

    private static final List<ChipValue> ASC = Collections.unmodifiableList(Arrays.asList(values()));
    private static final List<ChipValue> DESC = Collections.unmodifiableList(
            Arrays.asList(NETHERITE_INGOT, NETHERITE_SCRAP, DIAMOND, EMERALD, GOLD, IRON)
    );

    private final Material material;
    private final int value;
    private final String displayName;

    ChipValue(Material material, int value, String displayName) {
        this.material = material;
        this.value = value;
        this.displayName = displayName;
    }

    public Material material() {
        return material;
    }

    public int value() {
        return value;
    }

    public String display() {
        return displayName + " = " + value + " credits";
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
                    total += chip.value * stack.getAmount();
                    break;
                }
            }
        }
        return total;
    }
}
