package com.mcgame.poker;

public enum PokerSuit {
    CLUBS("C"),
    DIAMONDS("D"),
    HEARTS("H"),
    SPADES("S");

    private final String shortName;

    PokerSuit(String shortName) {
        this.shortName = shortName;
    }

    public String shortName() {
        return shortName;
    }
}
