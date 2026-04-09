package com.mcgame.poker;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Player;

public class TablePlayerListener implements Listener {
    private final PokerTable table;

    public TablePlayerListener(PokerTable table) {
        this.table = table;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        table.removeIfSeated(event.getPlayer());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!table.isActionMenu(event.getView())) {
            return;
        }
        event.setCancelled(true);
        table.handleMenuClick(player, event.getRawSlot());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        table.handleMenuClose(player, event.getView());
    }
}
