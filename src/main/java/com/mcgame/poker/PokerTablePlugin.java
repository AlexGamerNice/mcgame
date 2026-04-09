package com.mcgame.poker;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class PokerTablePlugin extends JavaPlugin implements TabCompleter {
    private static final List<String> PLAYER_SUBCOMMANDS = List.of("leave", "start", "value", "help");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("setlocation", "reload");
    private PokerTable pokerTable;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadDefaults();
        ChipValue.loadFromConfig(getConfig(), getLogger());
        Location configuredLocation = getConfig().getLocation("table.location");
        pokerTable = new PokerTable(this, configuredLocation);
        getServer().getPluginManager().registerEvents(new TablePlayerListener(pokerTable), this);
        getLogger().info("PokerTable enabled.");
    }

    @Override
    public void onDisable() {
        if (pokerTable != null) {
            pokerTable.stopGame("Server shutting down.");
        }
    }

    private void reloadPokerConfig() {
        reloadConfig();
        loadDefaults();
        ChipValue.loadFromConfig(getConfig(), getLogger());
    }

    private void loadDefaults() {
        if (!getConfig().isSet("table.small_blind")) {
            getConfig().set("table.small_blind", 16);
        }
        if (!getConfig().isSet("table.big_blind")) {
            getConfig().set("table.big_blind", 32);
        }
        if (!getConfig().isSet("table.min_players")) {
            getConfig().set("table.min_players", 2);
        }
        for (ChipValue chip : ChipValue.values()) {
            if (!getConfig().isSet(chip.configPath())) {
                getConfig().set(chip.configPath(), chip.defaultValue());
            }
        }
        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("table")) {
            return false;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /table.");
            return true;
        }

        if (args.length == 0) {
            handleJoin(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "leave" -> {
                pokerTable.removePlayer(player, true);
                return true;
            }
            case "start" -> {
                pokerTable.startRound(player);
                return true;
            }
            case "value" -> {
                int value = ChipValue.totalFromInventory(player.getInventory());
                player.sendMessage("Total chip value in your inventory: " + value + " credits.");
                return true;
            }
            case "setlocation" -> {
                if (!player.hasPermission("pokertable.admin")) {
                    player.sendMessage("You do not have permission to set the table location.");
                    return true;
                }
                getConfig().set("table.location", player.getLocation());
                saveConfig();
                pokerTable.setTableLocation(player.getLocation());
                player.sendMessage("Poker table location set to your current position.");
                return true;
            }
            case "reload" -> {
                if (!player.hasPermission("pokertable.admin")) {
                    player.sendMessage("You do not have permission to reload PokerTable.");
                    return true;
                }
                reloadPokerConfig();
                pokerTable.setTableLocation(getConfig().getLocation("table.location"));
                player.sendMessage("PokerTable configuration reloaded.");
                return true;
            }
            case "help" -> {
                sendHelp(player);
                return true;
            }
            default -> {
                sendHelp(player);
                return true;
            }
        }
    }

    private void handleJoin(Player player) {
        if (pokerTable.getTableLocation() != null) {
            player.teleportAsync(pokerTable.getTableLocation());
        }
        pokerTable.addPlayer(player);
    }

    private void sendHelp(Player player) {
        List<String> lines = new ArrayList<>();
        lines.add("/table - teleport/join the poker table");
        lines.add("/table leave - leave the table");
        lines.add("/table start - start a round (if enough players)");
        lines.add("/table value - show your total chip value");
        if (player.hasPermission("pokertable.admin")) {
            lines.add("/table setlocation - set table join location");
            lines.add("/table reload - reload plugin config");
        }
        for (String line : lines) {
            player.sendMessage(line);
        }
        player.sendMessage("Chip values:");
        for (ChipValue chipValue : ChipValue.values()) {
            player.sendMessage("- " + chipValue.display());
        }
    }

    public int getSmallBlind() {
        return Math.max(1, getConfig().getInt("table.small_blind", 16));
    }

    public int getBigBlind() {
        return Math.max(2, getConfig().getInt("table.big_blind", 32));
    }

    public int getMinimumPlayers() {
        return Math.max(2, getConfig().getInt("table.min_players", 2));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("table")) {
            return Collections.emptyList();
        }
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }
        if (args.length != 1) {
            return Collections.emptyList();
        }

        String partial = args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>(PLAYER_SUBCOMMANDS);
        if (player.hasPermission("pokertable.admin")) {
            options.addAll(ADMIN_SUBCOMMANDS);
        }

        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(partial)) {
                matches.add(option);
            }
        }
        matches.sort(String::compareTo);
        return matches;
    }
}
