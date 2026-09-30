package org.schacchi.model;

import java.util.Objects;

public class Position {
    private final int row;
    private final int col;

    // Cache common positions (8x8)
    private static final Position[][] CACHE = new Position[8][8];
    static {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                CACHE[r][c] = new Position(r, c);
            }
        }
    }

    private Position(int row, int col) {
        this.row = row;
        this.col = col;
    }

    public static Position of(int row, int col) {
        if (!isValid(row, col)) {
            throw new IllegalArgumentException("Invalid board position: (" + row + ", " + col + ")");
        }
        return CACHE[row][col];
    }

    public static boolean isValid(int row, int col) {
        return row >= 0 && row < 8 && col >= 0 && col < 8;
    }

    public int getRow() {
        return row;
    }

    public int getCol() {
        return col;
    }

    public int getRank() {
        return 8 - row;
    }

    public char getFile() {
        return (char) ('a' + col);
    }

    public String toAlgebraic() {
        return "" + getFile() + getRank();
    }

    public static Position fromAlgebraic(String notation) {
        if (notation == null || notation.length() < 2) {
            throw new IllegalArgumentException("Invalid algebraic square notation: " + notation);
        }
        char fileChar = Character.toLowerCase(notation.charAt(0));
        char rankChar = notation.charAt(1);

        if (fileChar < 'a' || fileChar > 'h' || rankChar < '1' || rankChar > '8') {
            throw new IllegalArgumentException("Out-of-bounds algebraic square notation: " + notation);
        }

        int col = fileChar - 'a';
        int row = 8 - (rankChar - '0');
        return of(row, col);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Position position = (Position) o;
        return row == position.row && col == position.col;
    }

    @Override
    public int hashCode() {
        return Objects.hash(row, col);
    }

    @Override
    public String toString() {
        return toAlgebraic();
    }
}
