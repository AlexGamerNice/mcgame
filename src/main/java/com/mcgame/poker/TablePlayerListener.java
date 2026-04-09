package com.mcgame.poker;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class TablePlayerListener implements Listener {
    private final PokerTable table;

    public TablePlayerListener(PokerTable table) {
        this.table = table;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        table.removePlayer(event.getPlayer(), false);
    }
}
