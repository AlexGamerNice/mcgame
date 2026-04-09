package com.mcgame.poker;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PokerTable {
    private static final int MAX_PLAYERS = 8;
    private static final long START_DELAY_TICKS = 20L * 8L;
    private static final long TURN_TIMEOUT_TICKS = 20L * 30L;
    private static final String ACTION_MENU_TITLE = ChatColor.DARK_GREEN + "Poker Action";

    private final JavaPlugin plugin;
    private final PokerTablePlugin config;
    private final Set<UUID> seatedPlayers = new HashSet<>();

    private final List<UUID> roundOrder = new ArrayList<>();
    private final Map<UUID, Integer> totalContributions = new HashMap<>();
    private final Map<UUID, Integer> streetContributions = new HashMap<>();
    private final Map<UUID, Integer> raiseSelections = new HashMap<>();
    private final Map<UUID, List<PokerCard>> holeCards = new HashMap<>();
    private final Set<UUID> foldedPlayers = new HashSet<>();
    private final Set<UUID> actedThisStreet = new HashSet<>();
    private final Set<UUID> programmaticClose = new HashSet<>();

    private Location tableLocation;
    private BukkitTask pendingStartTask;
    private BukkitTask turnTimeoutTask;
    private boolean roundRunning;
    private int dealerIndex = -1;
    private int actingIndex;
    private int pot;
    private int currentBet;
    private UUID awaitingActionPlayer;
    private Street street;
    private final List<PokerCard> boardCards = new ArrayList<>();

    private enum Street {
        PRE_FLOP,
        FLOP,
        TURN,
        RIVER
    }

    public PokerTable(PokerTablePlugin plugin, Location configuredLocation) {
        this.plugin = plugin;
        this.config = plugin;
        this.tableLocation = configuredLocation == null ? null : configuredLocation.clone();
    }

    public Location getTableLocation() {
        return tableLocation == null ? null : tableLocation.clone();
    }

    public void setTableLocation(Location tableLocation) {
        this.tableLocation = tableLocation == null ? null : tableLocation.clone();
    }

    public int seatedCount() {
        return seatedPlayers.size();
    }

    public boolean isActionMenu(InventoryView view) {
        return view != null && ACTION_MENU_TITLE.equals(view.getTitle());
    }

    public void addPlayer(Player player) {
        if (seatedPlayers.contains(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "You are already seated at the table.");
            return;
        }
        if (seatedPlayers.size() >= MAX_PLAYERS) {
            player.sendMessage(ChatColor.RED + "This table is full (" + MAX_PLAYERS + " max).");
            return;
        }
        seatedPlayers.add(player.getUniqueId());
        broadcast(ChatColor.GOLD + player.getName() + " joined the table.");
        maybeScheduleRound();
    }

    public void removePlayer(Player player, boolean announce) {
        UUID uuid = player.getUniqueId();
        boolean removed = seatedPlayers.remove(uuid);
        if (!removed) {
            player.sendMessage(ChatColor.YELLOW + "You are not seated at the table.");
            return;
        }
        if (announce) {
            broadcast(ChatColor.GRAY + player.getName() + " left the table.");
        }
        foldIfInRound(uuid, player.getName() + " left the table.");
        if (seatedPlayers.size() < config.getMinimumPlayers()) {
            cancelPendingRound();
        }
    }

    public void removeIfSeated(Player player) {
        UUID uuid = player.getUniqueId();
        boolean removed = seatedPlayers.remove(uuid);
        if (!removed) {
            return;
        }
        foldIfInRound(uuid, player.getName() + " disconnected.");
        if (seatedPlayers.size() < config.getMinimumPlayers()) {
            cancelPendingRound();
        }
    }

    public void startRound(Player requester) {
        if (!seatedPlayers.contains(requester.getUniqueId())) {
            requester.sendMessage(ChatColor.RED + "You must join the table first using /table.");
            return;
        }
        if (roundRunning) {
            requester.sendMessage(ChatColor.RED + "A round is already in progress.");
            return;
        }
        if (seatedPlayers.size() < config.getMinimumPlayers()) {
            requester.sendMessage(ChatColor.RED + "Need at least " + config.getMinimumPlayers() + " players.");
            return;
        }
        cancelPendingRound();
        startInteractiveRound();
    }

    public void stopGame(String reason) {
        cancelPendingRound();
        cancelTurnTimeout();
        if (roundRunning) {
            refundRoundContributions();
            roundRunning = false;
        }
        awaitingActionPlayer = null;
        if (reason != null && !reason.isBlank()) {
            broadcast(ChatColor.RED + reason);
        }
    }

    public boolean handleMenuClick(Player player, int rawSlot) {
        if (rawSlot < 0 || rawSlot > 26) {
            return true;
        }
        if (!roundRunning || awaitingActionPlayer == null) {
            return true;
        }
        UUID uuid = player.getUniqueId();
        if (!awaitingActionPlayer.equals(uuid)) {
            player.sendMessage(ChatColor.YELLOW + "It is not your turn.");
            return true;
        }

        if (rawSlot == 10) {
            handleFold(player, "You folded.");
            return true;
        }
        if (rawSlot == 12) {
            handleCheckOrCall(player);
            return true;
        }

        if (rawSlot == 19 || rawSlot == 20 || rawSlot == 21 || rawSlot == 23 || rawSlot == 24 || rawSlot == 25) {
            adjustRaise(uuid, rawSlot);
            openTurnMenu(player);
            return true;
        }

        if (rawSlot == 16) {
            handleRaise(player);
            return true;
        }
        return true;
    }

    public void handleMenuClose(Player player, InventoryView view) {
        if (!isActionMenu(view)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (programmaticClose.remove(uuid)) {
            return;
        }
        if (!roundRunning || awaitingActionPlayer == null || !awaitingActionPlayer.equals(uuid)) {
            return;
        }
        player.sendMessage(ChatColor.RED + "You closed the action menu and folded.");
        handleFold(player, "You folded.");
    }

    private void maybeScheduleRound() {
        if (roundRunning || pendingStartTask != null) {
            return;
        }
        if (seatedPlayers.size() < config.getMinimumPlayers()) {
            return;
        }
        broadcast(ChatColor.GREEN + "Round starts in 8 seconds. Use /table start to begin now.");
        pendingStartTask = plugin.getServer().getScheduler().runTaskLater(plugin, this::startInteractiveRound, START_DELAY_TICKS);
    }

    private void cancelPendingRound() {
        if (pendingStartTask != null) {
            pendingStartTask.cancel();
            pendingStartTask = null;
        }
    }

    private void startInteractiveRound() {
        pendingStartTask = null;
        if (roundRunning) {
            return;
        }

        List<Player> activePlayers = onlineSeatedPlayers();
        if (activePlayers.size() < config.getMinimumPlayers()) {
            broadcast(ChatColor.RED + "Round canceled: not enough players online at table.");
            return;
        }

        Collections.shuffle(activePlayers);
        roundOrder.clear();
        for (Player player : activePlayers) {
            roundOrder.add(player.getUniqueId());
        }
        if (roundOrder.size() < config.getMinimumPlayers()) {
            return;
        }

        roundRunning = true;
        totalContributions.clear();
        streetContributions.clear();
        foldedPlayers.clear();
        actedThisStreet.clear();
        raiseSelections.clear();
        holeCards.clear();
        boardCards.clear();
        pot = 0;
        currentBet = 0;
        awaitingActionPlayer = null;

        for (UUID uuid : roundOrder) {
            totalContributions.put(uuid, 0);
            streetContributions.put(uuid, 0);
        }

        List<PokerCard> deck = buildDeck();
        Collections.shuffle(deck);
        for (UUID uuid : roundOrder) {
            List<PokerCard> cards = new ArrayList<>(2);
            cards.add(deck.remove(0));
            cards.add(deck.remove(0));
            holeCards.put(uuid, cards);
        }
        for (int i = 0; i < 5; i++) {
            boardCards.add(deck.remove(0));
        }

        dealerIndex = (dealerIndex + 1) % roundOrder.size();
        int smallBlindIndex = nextActiveIndex(dealerIndex);
        int bigBlindIndex = nextActiveIndex(smallBlindIndex);
        if (smallBlindIndex < 0 || bigBlindIndex < 0) {
            cancelCurrentRound("Round canceled: not enough active players.");
            return;
        }

        int smallBlind = config.getSmallBlind();
        int bigBlind = config.getBigBlind();

        postBlind(smallBlindIndex, smallBlind, "small blind");
        postBlind(bigBlindIndex, bigBlind, "big blind");
        currentBet = Math.max(streetContributions.get(roundOrder.get(smallBlindIndex)), streetContributions.get(roundOrder.get(bigBlindIndex)));
        street = Street.PRE_FLOP;
        actingIndex = nextActiveIndex(bigBlindIndex);

        broadcast(ChatColor.GOLD + "New round started. Dealer: " + playerName(roundOrder.get(dealerIndex))
                + " | Blinds: " + smallBlind + "/" + bigBlind);
        for (UUID uuid : roundOrder) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) {
                List<PokerCard> cards = holeCards.get(uuid);
                player.sendMessage(ChatColor.LIGHT_PURPLE + "Your hole cards: " + cards.get(0) + ", " + cards.get(1));
            }
        }
        continueRoundFlow();
    }

    private void postBlind(int playerIndex, int amount, String blindName) {
        UUID uuid = roundOrder.get(playerIndex);
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            foldedPlayers.add(uuid);
            return;
        }
        if (!removeExactValue(player.getInventory(), amount)) {
            foldedPlayers.add(uuid);
            player.sendMessage(ChatColor.RED + "You could not pay " + blindName + " and were folded.");
            broadcast(ChatColor.RED + player.getName() + " could not post " + blindName + " and folded.");
            return;
        }

        streetContributions.put(uuid, streetContributions.get(uuid) + amount);
        totalContributions.put(uuid, totalContributions.get(uuid) + amount);
        pot += amount;
        player.sendMessage(ChatColor.GRAY + "Posted " + blindName + ": " + amount + " credits.");
    }

    private void continueRoundFlow() {
        if (!roundRunning) {
            return;
        }

        if (remainingPlayers() <= 1) {
            payoutLastStanding();
            return;
        }

        if (isBettingStreetComplete()) {
            advanceStreetOrShowdown();
            return;
        }

        int nextPlayerIndex = nextPendingActionIndex(actingIndex);
        if (nextPlayerIndex < 0) {
            advanceStreetOrShowdown();
            return;
        }

        actingIndex = nextPlayerIndex;
        UUID uuid = roundOrder.get(actingIndex);
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            foldedPlayers.add(uuid);
            actedThisStreet.add(uuid);
            continueRoundFlow();
            return;
        }

        awaitingActionPlayer = uuid;
        openTurnMenu(player);
        scheduleTurnTimeout(uuid);
    }

    private int remainingPlayers() {
        int count = 0;
        for (UUID uuid : roundOrder) {
            if (!foldedPlayers.contains(uuid)) {
                count++;
            }
        }
        return count;
    }

    private boolean isBettingStreetComplete() {
        for (UUID uuid : roundOrder) {
            if (foldedPlayers.contains(uuid)) {
                continue;
            }
            if (!actedThisStreet.contains(uuid)) {
                return false;
            }
            int paid = streetContributions.getOrDefault(uuid, 0);
            if (paid != currentBet) {
                return false;
            }
        }
        return true;
    }

    private void advanceStreetOrShowdown() {
        if (remainingPlayers() <= 1) {
            payoutLastStanding();
            return;
        }

        switch (street) {
            case PRE_FLOP -> {
                street = Street.FLOP;
                broadcast(ChatColor.AQUA + "Flop: " + formatCards(boardCards.subList(0, 3)));
            }
            case FLOP -> {
                street = Street.TURN;
                broadcast(ChatColor.AQUA + "Turn: " + boardCards.get(3));
            }
            case TURN -> {
                street = Street.RIVER;
                broadcast(ChatColor.AQUA + "River: " + boardCards.get(4));
            }
            case RIVER -> {
                showdown();
                return;
            }
        }

        currentBet = 0;
        actedThisStreet.clear();
        for (UUID uuid : roundOrder) {
            streetContributions.put(uuid, 0);
        }
        actingIndex = nextActiveIndex(dealerIndex);
        continueRoundFlow();
    }

    private void handleFold(Player player, String message) {
        UUID uuid = player.getUniqueId();
        foldedPlayers.add(uuid);
        actedThisStreet.add(uuid);
        broadcast(ChatColor.GRAY + player.getName() + " folds.");
        if (message != null && !message.isBlank()) {
            player.sendMessage(ChatColor.RED + message);
        }
        endPlayerTurn(uuid);
    }

    private void handleCheckOrCall(Player player) {
        UUID uuid = player.getUniqueId();
        int alreadyPaid = streetContributions.getOrDefault(uuid, 0);
        int callAmount = Math.max(0, currentBet - alreadyPaid);

        if (callAmount == 0) {
            actedThisStreet.add(uuid);
            broadcast(ChatColor.GRAY + player.getName() + " checks.");
            endPlayerTurn(uuid);
            return;
        }

        if (!removeExactValue(player.getInventory(), callAmount)) {
            player.sendMessage(ChatColor.RED + "You do not have enough chips to call.");
            handleFold(player, "Insufficient chips to call.");
            return;
        }

        streetContributions.put(uuid, alreadyPaid + callAmount);
        totalContributions.put(uuid, totalContributions.get(uuid) + callAmount);
        pot += callAmount;
        actedThisStreet.add(uuid);
        broadcast(ChatColor.GRAY + player.getName() + " calls " + callAmount + " credits.");
        endPlayerTurn(uuid);
    }

    private void handleRaise(Player player) {
        UUID uuid = player.getUniqueId();
        int alreadyPaid = streetContributions.getOrDefault(uuid, 0);
        int callAmount = Math.max(0, currentBet - alreadyPaid);
        int minimumRaise = getMinimumRaise();
        int raiseBy = raiseSelections.getOrDefault(uuid, minimumRaise);
        if (raiseBy < minimumRaise) {
            raiseBy = minimumRaise;
        }

        int totalCost = callAmount + raiseBy;
        if (!removeExactValue(player.getInventory(), totalCost)) {
            player.sendMessage(ChatColor.RED + "You cannot afford that raise.");
            openTurnMenu(player);
            return;
        }

        streetContributions.put(uuid, alreadyPaid + totalCost);
        totalContributions.put(uuid, totalContributions.get(uuid) + totalCost);
        pot += totalCost;
        currentBet += raiseBy;
        actedThisStreet.clear();
        actedThisStreet.add(uuid);

        broadcast(ChatColor.GOLD + player.getName() + " raises by " + raiseBy + " credits.");
        endPlayerTurn(uuid);
    }

    private void endPlayerTurn(UUID playerUuid) {
        cancelTurnTimeout();
        closeActionMenu(playerUuid);
        awaitingActionPlayer = null;

        int currentIndex = roundOrder.indexOf(playerUuid);
        if (currentIndex < 0) {
            actingIndex = 0;
        } else {
            actingIndex = nextActiveIndex(currentIndex);
        }
        continueRoundFlow();
    }

    private void openTurnMenu(Player player) {
        UUID uuid = player.getUniqueId();
        int playerStreetBet = streetContributions.getOrDefault(uuid, 0);
        int callAmount = Math.max(0, currentBet - playerStreetBet);
        int bankroll = ChipValue.totalFromInventory(player.getInventory());
        int minimumRaise = getMinimumRaise();
        int maxRaise = Math.max(0, bankroll - callAmount);
        int selection = raiseSelections.getOrDefault(uuid, minimumRaise);
        if (maxRaise >= minimumRaise) {
            selection = Math.min(Math.max(selection, minimumRaise), maxRaise);
            raiseSelections.put(uuid, selection);
        } else {
            selection = minimumRaise;
            raiseSelections.put(uuid, selection);
        }

        Inventory inv = Bukkit.createInventory(null, 27, ACTION_MENU_TITLE);
        List<PokerCard> cards = holeCards.get(uuid);
        String cardText = cards == null || cards.size() < 2 ? "?" : cards.get(0) + ", " + cards.get(1);

        inv.setItem(4, item(Material.BOOK,
                ChatColor.AQUA + "Round Info",
                ChatColor.GRAY + "Street: " + streetName(street),
                ChatColor.GRAY + "Pot: " + pot,
                ChatColor.GRAY + "Current bet: " + currentBet,
                ChatColor.GRAY + "Your street bet: " + playerStreetBet,
                ChatColor.GRAY + "Call: " + callAmount,
                ChatColor.GRAY + "Your cards: " + cardText));

        inv.setItem(10, item(Material.BARRIER, ChatColor.RED + "Fold", ChatColor.GRAY + "Give up this hand"));
        String callName = callAmount == 0 ? ChatColor.GREEN + "Check" : ChatColor.GREEN + "Call " + callAmount;
        inv.setItem(12, item(Material.LIME_DYE, callName, ChatColor.GRAY + "Match current bet"));

        inv.setItem(16, item(Material.EMERALD,
                ChatColor.GOLD + "Confirm Raise",
                ChatColor.GRAY + "Raise by: " + selection,
                ChatColor.GRAY + "Minimum raise: " + minimumRaise,
                ChatColor.GRAY + "Max raise now: " + maxRaise));

        inv.setItem(22, item(Material.SUNFLOWER, ChatColor.YELLOW + "Raise Amount: " + selection, ChatColor.GRAY + "Adjust then confirm"));

        int stepSmall = Math.max(1, config.getBigBlind() / 2);
        int stepMedium = Math.max(1, config.getBigBlind());
        int stepLarge = Math.max(1, config.getBigBlind() * 2);

        inv.setItem(19, item(Material.RED_DYE, ChatColor.RED + "-" + stepLarge));
        inv.setItem(20, item(Material.RED_DYE, ChatColor.RED + "-" + stepMedium));
        inv.setItem(21, item(Material.RED_DYE, ChatColor.RED + "-" + stepSmall));
        inv.setItem(23, item(Material.LIME_DYE, ChatColor.GREEN + "+" + stepSmall));
        inv.setItem(24, item(Material.LIME_DYE, ChatColor.GREEN + "+" + stepMedium));
        inv.setItem(25, item(Material.LIME_DYE, ChatColor.GREEN + "+" + stepLarge));

        player.openInventory(inv);
        player.sendMessage(ChatColor.YELLOW + "Your turn. Use the menu to act.");
    }

    private void adjustRaise(UUID uuid, int slot) {
        int stepSmall = Math.max(1, config.getBigBlind() / 2);
        int stepMedium = Math.max(1, config.getBigBlind());
        int stepLarge = Math.max(1, config.getBigBlind() * 2);

        int delta = switch (slot) {
            case 19 -> -stepLarge;
            case 20 -> -stepMedium;
            case 21 -> -stepSmall;
            case 23 -> stepSmall;
            case 24 -> stepMedium;
            case 25 -> stepLarge;
            default -> 0;
        };

        int selected = raiseSelections.getOrDefault(uuid, getMinimumRaise());
        int minRaise = getMinimumRaise();
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        int callAmount = Math.max(0, currentBet - streetContributions.getOrDefault(uuid, 0));
        int maxRaise = Math.max(0, ChipValue.totalFromInventory(player.getInventory()) - callAmount);
        if (maxRaise < minRaise) {
            raiseSelections.put(uuid, minRaise);
            return;
        }
        selected = Math.max(minRaise, selected + delta);
        selected = Math.min(selected, maxRaise);
        raiseSelections.put(uuid, selected);
    }

    private int getMinimumRaise() {
        return Math.max(1, config.getBigBlind());
    }

    private void scheduleTurnTimeout(UUID uuid) {
        cancelTurnTimeout();
        turnTimeoutTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!roundRunning || awaitingActionPlayer == null || !awaitingActionPlayer.equals(uuid)) {
                return;
            }
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.sendMessage(ChatColor.RED + "Turn timed out. You folded.");
                handleFold(player, "Turn timed out.");
                return;
            }
            foldIfInRound(uuid, "timed out.");
        }, TURN_TIMEOUT_TICKS);
    }

    private void cancelTurnTimeout() {
        if (turnTimeoutTask != null) {
            turnTimeoutTask.cancel();
            turnTimeoutTask = null;
        }
    }

    private void foldIfInRound(UUID uuid, String reason) {
        if (!roundRunning || !roundOrder.contains(uuid) || foldedPlayers.contains(uuid)) {
            return;
        }
        foldedPlayers.add(uuid);
        actedThisStreet.add(uuid);
        Player player = plugin.getServer().getPlayer(uuid);
        if (player != null && player.isOnline()) {
            player.sendMessage(ChatColor.RED + "You folded (" + reason + ").");
        }
        if (awaitingActionPlayer != null && awaitingActionPlayer.equals(uuid)) {
            endPlayerTurn(uuid);
        }
    }

    private void closeActionMenu(UUID uuid) {
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        InventoryView view = player.getOpenInventory();
        if (!isActionMenu(view)) {
            return;
        }
        programmaticClose.add(uuid);
        player.closeInventory();
        plugin.getServer().getScheduler().runTask(plugin, () -> programmaticClose.remove(uuid));
    }

    private int nextPendingActionIndex(int start) {
        if (roundOrder.isEmpty()) {
            return -1;
        }
        int size = roundOrder.size();
        for (int i = 0; i < size; i++) {
            int idx = (start + i) % size;
            UUID uuid = roundOrder.get(idx);
            if (foldedPlayers.contains(uuid)) {
                continue;
            }
            if (!actedThisStreet.contains(uuid)) {
                return idx;
            }
        }
        return -1;
    }

    private int nextActiveIndex(int fromIndex) {
        if (roundOrder.isEmpty()) {
            return -1;
        }
        int size = roundOrder.size();
        for (int i = 1; i <= size; i++) {
            int idx = (fromIndex + i) % size;
            UUID uuid = roundOrder.get(idx);
            if (!foldedPlayers.contains(uuid)) {
                return idx;
            }
        }
        return -1;
    }

    private void payoutLastStanding() {
        UUID winnerUuid = null;
        for (UUID uuid : roundOrder) {
            if (!foldedPlayers.contains(uuid)) {
                winnerUuid = uuid;
                break;
            }
        }
        if (winnerUuid == null) {
            cancelCurrentRound("Round ended without a winner.");
            return;
        }

        Player winner = plugin.getServer().getPlayer(winnerUuid);
        if (winner == null || !winner.isOnline()) {
            refundRoundContributions();
            cancelCurrentRound("Winner went offline. Pot refunded.");
            return;
        }

        giveValue(winner, pot);
        broadcast(ChatColor.GOLD + winner.getName() + " wins " + pot + " credits (all others folded).");
        finishRound();
    }

    private void showdown() {
        List<UUID> contenders = new ArrayList<>();
        for (UUID uuid : roundOrder) {
            if (!foldedPlayers.contains(uuid)) {
                Player player = plugin.getServer().getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    contenders.add(uuid);
                }
            }
        }

        if (contenders.isEmpty()) {
            refundRoundContributions();
            cancelCurrentRound("No online contenders at showdown. Pot refunded.");
            return;
        }

        Map<UUID, HandValue> handValues = new HashMap<>();
        for (UUID uuid : contenders) {
            List<PokerCard> seven = new ArrayList<>(holeCards.get(uuid));
            seven.addAll(boardCards);
            handValues.put(uuid, HandValue.bestOfSeven(seven));
        }

        HandValue best = null;
        List<UUID> winners = new ArrayList<>();
        for (UUID uuid : contenders) {
            HandValue current = handValues.get(uuid);
            if (best == null || current.compareTo(best) > 0) {
                best = current;
                winners.clear();
                winners.add(uuid);
            } else if (current.compareTo(best) == 0) {
                winners.add(uuid);
            }
        }

        broadcast(ChatColor.AQUA + "Board: " + formatCards(boardCards));
        for (UUID uuid : contenders) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) {
                List<PokerCard> cards = holeCards.get(uuid);
                player.sendMessage(ChatColor.LIGHT_PURPLE + "Showdown hand: " + cards.get(0) + ", " + cards.get(1)
                        + ChatColor.GRAY + " -> " + handValues.get(uuid).label);
            }
        }

        List<Player> winnerPlayers = new ArrayList<>();
        for (UUID uuid : winners) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) {
                winnerPlayers.add(player);
            }
        }

        if (winnerPlayers.isEmpty()) {
            refundRoundContributions();
            cancelCurrentRound("All winners went offline. Pot refunded.");
            return;
        }

        int split = pot / winnerPlayers.size();
        int remainder = pot % winnerPlayers.size();
        for (int i = 0; i < winnerPlayers.size(); i++) {
            int payout = split + (i == 0 ? remainder : 0);
            giveValue(winnerPlayers.get(i), payout);
        }
        broadcast(ChatColor.GOLD + "Showdown winner(s): " + joinNames(winnerPlayers) + ChatColor.GOLD
                + " with " + best.label + ". Pot: " + pot);
        finishRound();
    }

    private void cancelCurrentRound(String reason) {
        cancelTurnTimeout();
        awaitingActionPlayer = null;
        roundRunning = false;
        if (reason != null && !reason.isBlank()) {
            broadcast(ChatColor.RED + reason);
        }
        cleanupRoundState();
        maybeScheduleRound();
    }

    private void finishRound() {
        cancelTurnTimeout();
        awaitingActionPlayer = null;
        roundRunning = false;
        cleanupRoundState();
        maybeScheduleRound();
    }

    private void cleanupRoundState() {
        for (UUID uuid : new ArrayList<>(roundOrder)) {
            closeActionMenu(uuid);
        }
        totalContributions.clear();
        streetContributions.clear();
        holeCards.clear();
        foldedPlayers.clear();
        actedThisStreet.clear();
        raiseSelections.clear();
        boardCards.clear();
        roundOrder.clear();
        pot = 0;
        currentBet = 0;
        street = null;
    }

    private void refundRoundContributions() {
        for (Map.Entry<UUID, Integer> entry : totalContributions.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                giveValue(player, entry.getValue());
            }
        }
    }

    private List<Player> onlineSeatedPlayers() {
        List<Player> players = new ArrayList<>();
        for (UUID uuid : seatedPlayers) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }

    private void broadcast(String message) {
        for (UUID uuid : seatedPlayers) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        }
    }

    private String playerName(UUID uuid) {
        Player player = plugin.getServer().getPlayer(uuid);
        return player != null ? player.getName() : uuid.toString();
    }

    private String joinNames(List<Player> players) {
        List<String> names = new ArrayList<>();
        for (Player player : players) {
            names.add(player.getName());
        }
        Collections.sort(names);
        return String.join(", ", names);
    }

    private String streetName(Street value) {
        return switch (value) {
            case PRE_FLOP -> "Pre-Flop";
            case FLOP -> "Flop";
            case TURN -> "Turn";
            case RIVER -> "River";
        };
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && lore.length > 0) {
                meta.setLore(List.of(lore));
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String formatCards(List<PokerCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(cards.get(i));
        }
        return sb.toString();
    }

    private List<PokerCard> buildDeck() {
        List<PokerCard> deck = new ArrayList<>(52);
        for (PokerSuit suit : PokerSuit.values()) {
            for (PokerRank rank : PokerRank.values()) {
                deck.add(new PokerCard(rank, suit));
            }
        }
        return deck;
    }

    private boolean removeExactValue(Inventory inventory, int amount) {
        int current = ChipValue.totalFromInventory(inventory);
        if (current < amount) {
            return false;
        }
        int targetRemaining = current - amount;
        rebuildInventoryFromTotal(inventory, targetRemaining);
        return true;
    }

    private void giveValue(Player player, int value) {
        if (value <= 0) {
            return;
        }
        int total = ChipValue.totalFromInventory(player.getInventory()) + value;
        rebuildInventoryFromTotal(player.getInventory(), total);
    }

    private void rebuildInventoryFromTotal(Inventory inventory, int totalCredits) {
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack != null && ChipValue.isChip(stack.getType())) {
                contents[i] = null;
            }
        }
        inventory.setContents(contents);

        int remaining = Math.max(0, totalCredits);
        for (ChipValue chip : ChipValue.descending()) {
            int count = remaining / chip.value();
            if (count <= 0) {
                continue;
            }
            remaining -= count * chip.value();
            while (count > 0) {
                int batch = Math.min(64, count);
                inventory.addItem(new ItemStack(chip.material(), batch));
                count -= batch;
            }
        }
    }

    private static final class HandValue implements Comparable<HandValue> {
        private final int rankCategory;
        private final List<Integer> tiebreakers;
        private final String label;

        private HandValue(int rankCategory, List<Integer> tiebreakers, String label) {
            this.rankCategory = rankCategory;
            this.tiebreakers = tiebreakers;
            this.label = label;
        }

        @Override
        public int compareTo(HandValue other) {
            if (rankCategory != other.rankCategory) {
                return Integer.compare(rankCategory, other.rankCategory);
            }
            int max = Math.max(tiebreakers.size(), other.tiebreakers.size());
            for (int i = 0; i < max; i++) {
                int left = i < tiebreakers.size() ? tiebreakers.get(i) : 0;
                int right = i < other.tiebreakers.size() ? other.tiebreakers.get(i) : 0;
                if (left != right) {
                    return Integer.compare(left, right);
                }
            }
            return 0;
        }

        static HandValue bestOfSeven(List<PokerCard> cards) {
            List<List<PokerCard>> combos = new ArrayList<>(21);
            for (int a = 0; a < cards.size() - 4; a++) {
                for (int b = a + 1; b < cards.size() - 3; b++) {
                    for (int c = b + 1; c < cards.size() - 2; c++) {
                        for (int d = c + 1; d < cards.size() - 1; d++) {
                            for (int e = d + 1; e < cards.size(); e++) {
                                combos.add(List.of(cards.get(a), cards.get(b), cards.get(c), cards.get(d), cards.get(e)));
                            }
                        }
                    }
                }
            }
            HandValue best = null;
            for (List<PokerCard> combo : combos) {
                HandValue value = evaluateFive(combo);
                if (best == null || value.compareTo(best) > 0) {
                    best = value;
                }
            }
            return best;
        }

        private static HandValue evaluateFive(List<PokerCard> hand) {
            Map<Integer, Integer> counts = new HashMap<>();
            EnumSet<PokerSuit> suits = EnumSet.noneOf(PokerSuit.class);
            List<Integer> ranks = new ArrayList<>(5);
            for (PokerCard card : hand) {
                int val = card.rank().getValue();
                counts.put(val, counts.getOrDefault(val, 0) + 1);
                suits.add(card.suit());
                ranks.add(val);
            }
            ranks.sort(Integer::compareTo);

            boolean flush = suits.size() == 1;
            int straightHigh = straightHigh(ranks);
            boolean straight = straightHigh > 0;

            if (straight && flush) {
                return new HandValue(8, List.of(straightHigh), "Straight Flush");
            }
            if (counts.containsValue(4)) {
                int four = firstByCount(counts, 4);
                int kicker = firstByCount(counts, 1);
                return new HandValue(7, List.of(four, kicker), "Four of a Kind");
            }
            if (counts.containsValue(3) && counts.containsValue(2)) {
                int three = firstByCount(counts, 3);
                int pair = firstByCount(counts, 2);
                return new HandValue(6, List.of(three, pair), "Full House");
            }
            if (flush) {
                List<Integer> sorted = new ArrayList<>(ranks);
                sorted.sort(Collections.reverseOrder());
                return new HandValue(5, sorted, "Flush");
            }
            if (straight) {
                return new HandValue(4, List.of(straightHigh), "Straight");
            }
            if (counts.containsValue(3)) {
                int three = firstByCount(counts, 3);
                List<Integer> kickers = ranksByCount(counts, 1);
                return new HandValue(3, concat(List.of(three), kickers), "Three of a Kind");
            }
            int pairs = countOfCount(counts, 2);
            if (pairs == 2) {
                List<Integer> pairRanks = ranksByCount(counts, 2);
                int kicker = firstByCount(counts, 1);
                return new HandValue(2, concat(pairRanks, List.of(kicker)), "Two Pair");
            }
            if (pairs == 1) {
                int pair = firstByCount(counts, 2);
                List<Integer> kickers = ranksByCount(counts, 1);
                return new HandValue(1, concat(List.of(pair), kickers), "Pair");
            }

            List<Integer> highCard = new ArrayList<>(ranks);
            highCard.sort(Collections.reverseOrder());
            return new HandValue(0, highCard, "High Card");
        }

        private static List<Integer> concat(List<Integer> left, List<Integer> right) {
            List<Integer> out = new ArrayList<>(left);
            out.addAll(right);
            return out;
        }

        private static int straightHigh(List<Integer> ascendingRanks) {
            List<Integer> unique = new ArrayList<>();
            for (int r : ascendingRanks) {
                if (unique.isEmpty() || unique.get(unique.size() - 1) != r) {
                    unique.add(r);
                }
            }
            if (unique.size() != 5) {
                return 0;
            }
            boolean regular = true;
            for (int i = 1; i < unique.size(); i++) {
                if (unique.get(i) != unique.get(i - 1) + 1) {
                    regular = false;
                    break;
                }
            }
            if (regular) {
                return unique.get(unique.size() - 1);
            }
            if (unique.equals(List.of(2, 3, 4, 5, 14))) {
                return 5;
            }
            return 0;
        }

        private static int firstByCount(Map<Integer, Integer> counts, int wanted) {
            int best = 0;
            for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
                if (entry.getValue() == wanted && entry.getKey() > best) {
                    best = entry.getKey();
                }
            }
            return best;
        }

        private static int countOfCount(Map<Integer, Integer> counts, int wanted) {
            int c = 0;
            for (int v : counts.values()) {
                if (v == wanted) {
                    c++;
                }
            }
            return c;
        }

        private static List<Integer> ranksByCount(Map<Integer, Integer> counts, int wanted) {
            List<Integer> values = new ArrayList<>();
            for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
                if (entry.getValue() == wanted) {
                    values.add(entry.getKey());
                }
            }
            values.sort(Collections.reverseOrder());
            return values;
        }
    }
}
