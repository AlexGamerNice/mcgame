package com.mcgame.poker;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
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

    private final JavaPlugin plugin;
    private final PokerTablePlugin config;
    private final Set<UUID> seatedPlayers = new HashSet<>();
    private final Map<UUID, Integer> lastRoundContributions = new HashMap<>();

    private Location tableLocation;
    private BukkitTask pendingStartTask;
    private boolean roundRunning;
    private int pot;

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
        boolean removed = seatedPlayers.remove(player.getUniqueId());
        if (!removed) {
            player.sendMessage(ChatColor.YELLOW + "You are not seated at the table.");
            return;
        }
        if (announce) {
            broadcast(ChatColor.GRAY + player.getName() + " left the table.");
        }
        if (seatedPlayers.size() < config.getMinimumPlayers()) {
            cancelPendingRound();
        }
    }

    public void removeIfSeated(Player player) {
        boolean removed = seatedPlayers.remove(player.getUniqueId());
        if (!removed) {
            return;
        }
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
        playRound();
    }

    public void stopGame(String reason) {
        cancelPendingRound();
        if (roundRunning) {
            refundRoundContributions();
            roundRunning = false;
        }
        if (reason != null && !reason.isBlank()) {
            broadcast(ChatColor.RED + reason);
        }
    }

    private void maybeScheduleRound() {
        if (roundRunning || pendingStartTask != null) {
            return;
        }
        if (seatedPlayers.size() < config.getMinimumPlayers()) {
            return;
        }
        broadcast(ChatColor.GREEN + "Round starts in 8 seconds. Use /table start to begin now.");
        pendingStartTask = plugin.getServer().getScheduler().runTaskLater(plugin, this::playRound, START_DELAY_TICKS);
    }

    private void cancelPendingRound() {
        if (pendingStartTask != null) {
            pendingStartTask.cancel();
            pendingStartTask = null;
        }
    }

    private void playRound() {
        pendingStartTask = null;
        if (roundRunning) {
            return;
        }

        List<Player> active = onlineSeatedPlayers();
        if (active.size() < config.getMinimumPlayers()) {
            broadcast(ChatColor.RED + "Round canceled: not enough players online at table.");
            return;
        }

        roundRunning = true;
        lastRoundContributions.clear();
        pot = 0;

        int smallBlind = config.getSmallBlind();
        int bigBlind = config.getBigBlind();
        int dealerIndex = (int) (System.currentTimeMillis() % active.size());
        int smallBlindIndex = (dealerIndex + 1) % active.size();
        int bigBlindIndex = (dealerIndex + 2) % active.size();

        Set<UUID> inRound = new HashSet<>();
        for (int i = 0; i < active.size(); i++) {
            Player p = active.get(i);
            int blind = 0;
            if (i == smallBlindIndex) {
                blind = smallBlind;
            } else if (i == bigBlindIndex) {
                blind = bigBlind;
            }

            if (blind > 0) {
                if (!removeExactValue(p.getInventory(), blind)) {
                    p.sendMessage(ChatColor.RED + "You could not pay your blind and were folded.");
                    continue;
                }
                lastRoundContributions.put(p.getUniqueId(), blind);
                pot += blind;
                p.sendMessage(ChatColor.GRAY + "Blind paid: " + blind + " credits.");
            } else {
                lastRoundContributions.put(p.getUniqueId(), 0);
            }
            inRound.add(p.getUniqueId());
        }

        if (inRound.size() < config.getMinimumPlayers()) {
            broadcast(ChatColor.RED + "Round canceled: not enough players could post blinds.");
            refundRoundContributions();
            roundRunning = false;
            maybeScheduleRound();
            return;
        }

        List<PokerCard> deck = buildDeck();
        Collections.shuffle(deck);
        Map<UUID, List<PokerCard>> holeCards = new HashMap<>();
        List<PokerCard> board = new ArrayList<>();
        for (UUID uuid : inRound) {
            holeCards.put(uuid, new ArrayList<>(2));
        }

        for (int i = 0; i < 2; i++) {
            for (UUID uuid : inRound) {
                holeCards.get(uuid).add(deck.remove(0));
            }
        }
        for (int i = 0; i < 5; i++) {
            board.add(deck.remove(0));
        }

        Map<UUID, HandValue> handValues = new HashMap<>();
        for (UUID uuid : inRound) {
            List<PokerCard> seven = new ArrayList<>(holeCards.get(uuid));
            seven.addAll(board);
            HandValue value = HandValue.bestOfSeven(seven);
            handValues.put(uuid, value);
        }

        HandValue best = null;
        List<UUID> winners = new ArrayList<>();
        for (UUID uuid : inRound) {
            HandValue current = handValues.get(uuid);
            if (best == null || current.compareTo(best) > 0) {
                best = current;
                winners.clear();
                winners.add(uuid);
            } else if (current.compareTo(best) == 0) {
                winners.add(uuid);
            }
        }

        List<Player> participants = new ArrayList<>();
        for (UUID uuid : inRound) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null && p.isOnline()) {
                participants.add(p);
                List<PokerCard> cards = holeCards.get(uuid);
                p.sendMessage(ChatColor.LIGHT_PURPLE + "Your hand: " + cards.get(0) + ", " + cards.get(1));
            }
        }

        broadcast(ChatColor.AQUA + "Board: " + formatCards(board));

        if (best == null) {
            refundRoundContributions();
            roundRunning = false;
            maybeScheduleRound();
            return;
        }

        if (winners.size() > 1) {
            List<Player> winningPlayers = new ArrayList<>();
            for (UUID uuid : winners) {
                Player p = plugin.getServer().getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    winningPlayers.add(p);
                }
            }
            if (winningPlayers.isEmpty()) {
                refundRoundContributions();
                broadcast(ChatColor.RED + "All winners went offline. Pot refunded.");
                roundRunning = false;
                maybeScheduleRound();
                return;
            }

            int split = pot / winningPlayers.size();
            int remainder = pot % winningPlayers.size();
            for (int i = 0; i < winningPlayers.size(); i++) {
                int payout = split + (i == 0 ? remainder : 0);
                giveValue(winningPlayers.get(i), payout);
            }
            broadcast(ChatColor.GOLD + "Round tied (" + best.label + "). Pot split: " + pot + " credits.");
        } else {
            Player winnerPlayer = plugin.getServer().getPlayer(winners.get(0));
            if (winnerPlayer != null && winnerPlayer.isOnline()) {
                giveValue(winnerPlayer, pot);
                broadcast(ChatColor.GOLD + winnerPlayer.getName() + " wins " + pot + " credits with " + best.label + "!");
            } else {
                refundRoundContributions();
                broadcast(ChatColor.RED + "Winner went offline. Pot refunded.");
            }
        }

        roundRunning = false;
        maybeScheduleRound();
    }

    private void refundRoundContributions() {
        for (Map.Entry<UUID, Integer> entry : lastRoundContributions.entrySet()) {
            Player p = plugin.getServer().getPlayer(entry.getKey());
            if (p != null && p.isOnline() && entry.getValue() > 0) {
                giveValue(p, entry.getValue());
            }
        }
        lastRoundContributions.clear();
        pot = 0;
    }

    private List<Player> onlineSeatedPlayers() {
        List<Player> players = new ArrayList<>();
        for (UUID uuid : seatedPlayers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null && p.isOnline()) {
                players.add(p);
            }
        }
        return players;
    }

    private void broadcast(String message) {
        for (UUID uuid : seatedPlayers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.sendMessage(message);
            }
        }
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
