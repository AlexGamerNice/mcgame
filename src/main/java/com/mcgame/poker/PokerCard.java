package com.mcgame.poker;

public record PokerCard(PokerRank rank, PokerSuit suit) {
    @Override
    public String toString() {
        return rank.shortName() + suit.shortName();
    }
}
