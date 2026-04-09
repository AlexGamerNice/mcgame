package com.mcgame.poker;

public enum PokerRank {
    TWO(2),
    THREE(3),
    FOUR(4),
    FIVE(5),
    SIX(6),
    SEVEN(7),
    EIGHT(8),
    NINE(9),
    TEN(10),
    JACK(11),
    QUEEN(12),
    KING(13),
    ACE(14);

    private final int value;

    PokerRank(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public String shortName() {
        return switch (this) {
            case TEN -> "T";
            case JACK -> "J";
            case QUEEN -> "Q";
            case KING -> "K";
            case ACE -> "A";
            default -> String.valueOf(value);
        };
    }
}
