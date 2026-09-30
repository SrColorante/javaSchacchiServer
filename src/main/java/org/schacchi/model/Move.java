package org.schacchi.model;

import java.util.Objects;

public class Move {
    private final Position from;
    private final Position to;
    private final PieceType promotion;

    public Move(Position from, Position to, PieceType promotion) {
        this.from = Objects.requireNonNull(from, "From position cannot be null");
        this.to = Objects.requireNonNull(to, "To position cannot be null");
        this.promotion = promotion;
    }

    public Move(Position from, Position to) {
        this(from, to, null);
    }

    public static Move of(Position from, Position to) {
        return new Move(from, to, null);
    }

    public static Move of(Position from, Position to, PieceType promotion) {
        return new Move(from, to, promotion);
    }

    public Position getFrom() {
        return from;
    }

    public Position getTo() {
        return to;
    }

    public PieceType getPromotion() {
        return promotion;
    }

    public String toUci() {
        String base = from.toAlgebraic() + to.toAlgebraic();
        if (promotion != null) {
            base += Character.toLowerCase(promotion.getSymbol());
        }
        return base;
    }

    public static Move fromUci(String uci) {
        if (uci == null) {
            throw new IllegalArgumentException("UCI move cannot be null");
        }
        // Normalize: remove dashes, spaces, e.g. "e2-e4" -> "e2e4"
        String cleaned = uci.trim().replace("-", "").replace(" ", "").toLowerCase();
        if (cleaned.length() < 4) {
            throw new IllegalArgumentException("Invalid UCI move format: " + uci);
        }

        Position from = Position.fromAlgebraic(cleaned.substring(0, 2));
        Position to = Position.fromAlgebraic(cleaned.substring(2, 4));
        PieceType promo = null;
        if (cleaned.length() >= 5) {
            promo = PieceType.fromChar(cleaned.charAt(4));
        }

        return new Move(from, to, promo);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Move move = (Move) o;
        return Objects.equals(from, move.from) &&
               Objects.equals(to, move.to) &&
               promotion == move.promotion;
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to, promotion);
    }

    @Override
    public String toString() {
        return toUci();
    }
}
