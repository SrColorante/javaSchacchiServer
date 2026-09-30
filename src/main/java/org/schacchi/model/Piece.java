package org.schacchi.model;

import java.util.Objects;

public class Piece {
    private final PieceType type;
    private final PieceColor color;

    public Piece(PieceType type, PieceColor color) {
        this.type = Objects.requireNonNull(type, "PieceType cannot be null");
        this.color = Objects.requireNonNull(color, "PieceColor cannot be null");
    }

    public PieceType getType() {
        return type;
    }

    public PieceColor getColor() {
        return color;
    }

    public char getFenChar() {
        char c = type.getSymbol();
        return color == PieceColor.WHITE ? Character.toUpperCase(c) : Character.toLowerCase(c);
    }

    public static Piece fromFenChar(char c) {
        PieceType type = PieceType.fromChar(c);
        if (type == null) return null;
        PieceColor color = Character.isUpperCase(c) ? PieceColor.WHITE : PieceColor.BLACK;
        return new Piece(type, color);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Piece piece = (Piece) o;
        return type == piece.type && color == piece.color;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, color);
    }

    @Override
    public String toString() {
        return String.valueOf(getFenChar());
    }
}
