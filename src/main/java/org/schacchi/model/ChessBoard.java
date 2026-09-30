package org.schacchi.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rappresenta la scacchiera 8x8 con tutte le regole ufficiali del gioco degli scacchi:
 * - Movimento di tutti i pezzi (Pedone, Cavallo, Alfiere, Torre, Donna, Re)
 * - Arrocco (corto e lungo)
 * - En Passant
 * - Promozione pedone
 * - Rilevamento Scacco, Scacco Matto e Stallo
 * - Regola delle 50 mosse, materiale insufficiente e ripetizione della posizione
 * - Esportazione e inizializzazione tramite FEN
 */
public class ChessBoard {
    private final Piece[][] squares = new Piece[8][8];
    private PieceColor turn = PieceColor.WHITE;

    // Numero di occorrenze di ogni posizione incontrata nella partita (per la ripetizione).
    // Si aggiorna solo in makeMove(), mai durante la simulazione delle mosse in copy().
    private final Map<String, Integer> repetitionCounts = new LinkedHashMap<>();

    // Diritti di arrocco
    private boolean whiteKingMoved = false;
    private boolean blackKingMoved = false;
    private boolean whiteRookKingsideMoved = false;
    private boolean whiteRookQueensideMoved = false;
    private boolean blackRookKingsideMoved = false;
    private boolean blackRookQueensideMoved = false;

    // Bersaglio En Passant (se il pedone ha fatto un doppio passo al turno precedente)
    private Position enPassantTarget = null;

    private int halfmoveClock = 0;
    private int fullmoveNumber = 1;

    public ChessBoard() {
        setupInitialPosition();
    }

    public void setupInitialPosition() {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                squares[r][c] = null;
            }
        }

        // Pezzi Neri (riga 0 = traversa 8)
        squares[0][0] = new Piece(PieceType.ROOK, PieceColor.BLACK);
        squares[0][1] = new Piece(PieceType.KNIGHT, PieceColor.BLACK);
        squares[0][2] = new Piece(PieceType.BISHOP, PieceColor.BLACK);
        squares[0][3] = new Piece(PieceType.QUEEN, PieceColor.BLACK);
        squares[0][4] = new Piece(PieceType.KING, PieceColor.BLACK);
        squares[0][5] = new Piece(PieceType.BISHOP, PieceColor.BLACK);
        squares[0][6] = new Piece(PieceType.KNIGHT, PieceColor.BLACK);
        squares[0][7] = new Piece(PieceType.ROOK, PieceColor.BLACK);

        // Pedoni Neri (riga 1 = traversa 7)
        for (int c = 0; c < 8; c++) {
            squares[1][c] = new Piece(PieceType.PAWN, PieceColor.BLACK);
        }

        // Pedoni Bianchi (riga 6 = traversa 2)
        for (int c = 0; c < 8; c++) {
            squares[6][c] = new Piece(PieceType.PAWN, PieceColor.WHITE);
        }

        // Pezzi Bianchi (riga 7 = traversa 1)
        squares[7][0] = new Piece(PieceType.ROOK, PieceColor.WHITE);
        squares[7][1] = new Piece(PieceType.KNIGHT, PieceColor.WHITE);
        squares[7][2] = new Piece(PieceType.BISHOP, PieceColor.WHITE);
        squares[7][3] = new Piece(PieceType.QUEEN, PieceColor.WHITE);
        squares[7][4] = new Piece(PieceType.KING, PieceColor.WHITE);
        squares[7][5] = new Piece(PieceType.BISHOP, PieceColor.WHITE);
        squares[7][6] = new Piece(PieceType.KNIGHT, PieceColor.WHITE);
        squares[7][7] = new Piece(PieceType.ROOK, PieceColor.WHITE);

        turn = PieceColor.WHITE;
        whiteKingMoved = false;
        blackKingMoved = false;
        whiteRookKingsideMoved = false;
        whiteRookQueensideMoved = false;
        blackRookKingsideMoved = false;
        blackRookQueensideMoved = false;
        enPassantTarget = null;
        halfmoveClock = 0;
        fullmoveNumber = 1;
        repetitionCounts.clear();
        repetitionCounts.merge(getPositionKey(), 1, Integer::sum);
    }

    public Piece getPiece(Position pos) {
        if (pos == null) return null;
        return squares[pos.getRow()][pos.getCol()];
    }

    public Piece getPiece(int row, int col) {
        if (!Position.isValid(row, col)) return null;
        return squares[row][col];
    }

    public void setPiece(Position pos, Piece piece) {
        squares[pos.getRow()][pos.getCol()] = piece;
    }

    public PieceColor getTurn() {
        return turn;
    }

    public Position getEnPassantTarget() {
        return enPassantTarget;
    }

    public int getFullmoveNumber() {
        return fullmoveNumber;
    }

    public int getHalfmoveClock() {
        return halfmoveClock;
    }

    /**
     * Identificatore canonico della posizione corrente, usato per rilevare le ripetizioni.
     * Comprende i quattro campi FEN che determinano la posizione legally-equivalente:
     * disposizione dei pezzi, colore di turno, diritti di arrocco e bersaglio en passant.
     */
    public String getPositionKey() {
        return toFenPiecePlacement() + (turn == PieceColor.WHITE ? " w " : " b ")
                + getFenCastlingRights() + " "
                + (enPassantTarget != null ? enPassantTarget.toAlgebraic() : "-");
    }

    /**
     * Quante volte si e' verificata la posizione corrente dall'inizio della partita.
     */
    public int getRepetitionCount() {
        return repetitionCounts.getOrDefault(getPositionKey(), 0);
    }

    /**
     * Regola delle 50 mosse: nessuna cattura ne' mossa di pedone per 50 mosse consecutive.
     * La partita viene dichiarata patta automaticamente dal server.
     */
    public boolean isFiftyMoveDraw() {
        return halfmoveClock >= 100;
    }

    /**
     * Ripetizione: la stessa posizione (pezzi, turno, arrocco, en passant) si e' verificata almeno 3 volte.
     */
    public boolean isThreefoldRepetition() {
        return getRepetitionCount() >= 3;
    }

    /**
     * Materiale insufficiente a dare scacco-matte con qualsiasi sequenza di mosse:
     * Re contro Re, Re+pedone non, Re con un solo Alfiere o Cavallo, Re con due Cavalli,
     * e Re contro Re con soli Alfieri tutti su caselle dello stesso colore.
     */
    public boolean isInsufficientMaterial() {
        int knights = 0, bishops = 0;
        int bishopSquareColor = -1;
        boolean allBishopsSameColor = true;

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = squares[r][c];
                if (p == null || p.getType() == PieceType.KING) continue;
                switch (p.getType()) {
                    case PAWN, ROOK, QUEEN -> {
                        return false; // da solo e' materiale sufficiente
                    }
                    case KNIGHT -> knights++;
                    case BISHOP -> {
                        bishops++;
                        int sqColor = (r + c) % 2;
                        if (bishopSquareColor == -1) {
                            bishopSquareColor = sqColor;
                        } else if (bishopSquareColor != sqColor) {
                            allBishopsSameColor = false;
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        // Solo Re, oppure Re con un unico pezzo minore
        if (knights + bishops <= 1) return true;
        // Re e due Cavalli: il mate non e' forzabile
        if (knights == 2 && bishops == 0) return true;
        // Solo Alfieri: patta se sono tutti su caselle dello stesso colore
        return knights == 0 && bishops >= 2 && allBishopsSameColor;
    }

    /**
     * Crea una copia esatta della scacchiera per simulare mosse senza alterare lo stato reale.
     */
    public ChessBoard copy() {
        ChessBoard copy = new ChessBoard();
        for (int r = 0; r < 8; r++) {
            System.arraycopy(this.squares[r], 0, copy.squares[r], 0, 8);
        }
        copy.turn = this.turn;
        copy.whiteKingMoved = this.whiteKingMoved;
        copy.blackKingMoved = this.blackKingMoved;
        copy.whiteRookKingsideMoved = this.whiteRookKingsideMoved;
        copy.whiteRookQueensideMoved = this.whiteRookQueensideMoved;
        copy.blackRookKingsideMoved = this.blackRookKingsideMoved;
        copy.blackRookQueensideMoved = this.blackRookQueensideMoved;
        copy.enPassantTarget = this.enPassantTarget;
        copy.halfmoveClock = this.halfmoveClock;
        copy.fullmoveNumber = this.fullmoveNumber;
        // La cronologia delle ripetizioni non viene ereditata: la copia serve solo a
        // simulare una singola mossa, e registrarne la posizione falserebbe il conteggio.
        copy.repetitionCounts.clear();
        copy.repetitionCounts.merge(copy.getPositionKey(), 1, Integer::sum);
        return copy;
    }

    public Position findKing(PieceColor color) {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = squares[r][c];
                if (p != null && p.getType() == PieceType.KING && p.getColor() == color) {
                    return Position.of(r, c);
                }
            }
        }
        return null;
    }

    public boolean isKingInCheck(PieceColor color) {
        Position kingPos = findKing(color);
        if (kingPos == null) return false;
        return isSquareAttacked(kingPos, color.opposite());
    }

    public boolean isSquareAttacked(Position target, PieceColor byColor) {
        int tr = target.getRow();
        int tc = target.getCol();

        // 1. Attacco da pedoni avversari
        int pawnAttackRow = (byColor == PieceColor.WHITE) ? tr + 1 : tr - 1;
        if (pawnAttackRow >= 0 && pawnAttackRow < 8) {
            for (int dc : new int[]{-1, 1}) {
                int col = tc + dc;
                if (col >= 0 && col < 8) {
                    Piece p = squares[pawnAttackRow][col];
                    if (p != null && p.getColor() == byColor && p.getType() == PieceType.PAWN) {
                        return true;
                    }
                }
            }
        }

        // 2. Attacco da cavalli
        int[][] knightOffsets = {
            {-2, -1}, {-2, 1}, {-1, -2}, {-1, 2},
            {1, -2}, {1, 2}, {2, -1}, {2, 1}
        };
        for (int[] offset : knightOffsets) {
            int nr = tr + offset[0];
            int nc = tc + offset[1];
            if (Position.isValid(nr, nc)) {
                Piece p = squares[nr][nc];
                if (p != null && p.getColor() == byColor && p.getType() == PieceType.KNIGHT) {
                    return true;
                }
            }
        }

        // 3. Attacco da Re avversario (caselle adiacenti)
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) continue;
                int nr = tr + dr;
                int nc = tc + dc;
                if (Position.isValid(nr, nc)) {
                    Piece p = squares[nr][nc];
                    if (p != null && p.getColor() == byColor && p.getType() == PieceType.KING) {
                        return true;
                    }
                }
            }
        }

        // 4. Raggi ortogonali (Torre o Donna)
        int[][] orthogonalDirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] dir : orthogonalDirs) {
            int r = tr + dir[0];
            int c = tc + dir[1];
            while (Position.isValid(r, c)) {
                Piece p = squares[r][c];
                if (p != null) {
                    if (p.getColor() == byColor && (p.getType() == PieceType.ROOK || p.getType() == PieceType.QUEEN)) {
                        return true;
                    }
                    break; // bloccato da un pezzo
                }
                r += dir[0];
                c += dir[1];
            }
        }

        // 5. Raggi diagonali (Alfiere o Donna)
        int[][] diagonalDirs = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
        for (int[] dir : diagonalDirs) {
            int r = tr + dir[0];
            int c = tc + dir[1];
            while (Position.isValid(r, c)) {
                Piece p = squares[r][c];
                if (p != null) {
                    if (p.getColor() == byColor && (p.getType() == PieceType.BISHOP || p.getType() == PieceType.QUEEN)) {
                        return true;
                    }
                    break; // bloccato da un pezzo
                }
                r += dir[0];
                c += dir[1];
            }
        }

        return false;
    }

    public List<Move> getPseudoLegalMoves(Position from) {
        List<Move> moves = new ArrayList<>();
        Piece piece = getPiece(from);
        if (piece == null) return moves;

        PieceColor color = piece.getColor();
        int fr = from.getRow();
        int fc = from.getCol();

        switch (piece.getType()) {
            case PAWN -> {
                int dir = (color == PieceColor.WHITE) ? -1 : 1;
                int startRow = (color == PieceColor.WHITE) ? 6 : 1;
                int promoRow = (color == PieceColor.WHITE) ? 0 : 7;

                // Passo avanti di 1
                int forwardRow = fr + dir;
                if (Position.isValid(forwardRow, fc) && squares[forwardRow][fc] == null) {
                    Position to = Position.of(forwardRow, fc);
                    if (forwardRow == promoRow) {
                        for (PieceType promo : new PieceType[]{PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT}) {
                            moves.add(new Move(from, to, promo));
                        }
                    } else {
                        moves.add(new Move(from, to));
                        // Passo avanti di 2
                        int doubleForwardRow = fr + 2 * dir;
                        if (fr == startRow && squares[doubleForwardRow][fc] == null) {
                            moves.add(new Move(from, Position.of(doubleForwardRow, fc)));
                        }
                    }
                }

                // Catture diagonali
                for (int dc : new int[]{-1, 1}) {
                    int col = fc + dc;
                    if (Position.isValid(forwardRow, col)) {
                        Position to = Position.of(forwardRow, col);
                        Piece target = squares[forwardRow][col];
                        // Cattura standard
                        if (target != null && target.getColor() != color) {
                            if (forwardRow == promoRow) {
                                for (PieceType promo : new PieceType[]{PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT}) {
                                    moves.add(new Move(from, to, promo));
                                }
                            } else {
                                moves.add(new Move(from, to));
                            }
                        }
                        // En Passant
                        else if (enPassantTarget != null && to.equals(enPassantTarget)) {
                            moves.add(new Move(from, to));
                        }
                    }
                }
            }
            case KNIGHT -> {
                int[][] knightOffsets = {
                    {-2, -1}, {-2, 1}, {-1, -2}, {-1, 2},
                    {1, -2}, {1, 2}, {2, -1}, {2, 1}
                };
                for (int[] offset : knightOffsets) {
                    int r = fr + offset[0];
                    int c = fc + offset[1];
                    if (Position.isValid(r, c)) {
                        Piece target = squares[r][c];
                        if (target == null || target.getColor() != color) {
                            moves.add(new Move(from, Position.of(r, c)));
                        }
                    }
                }
            }
            case BISHOP -> addRayMoves(moves, from, color, new int[][]{{-1, -1}, {-1, 1}, {1, -1}, {1, 1}});
            case ROOK -> addRayMoves(moves, from, color, new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}});
            case QUEEN -> {
                addRayMoves(moves, from, color, new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}});
                addRayMoves(moves, from, color, new int[][]{{-1, -1}, {-1, 1}, {1, -1}, {1, 1}});
            }
            case KING -> {
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        if (dr == 0 && dc == 0) continue;
                        int r = fr + dr;
                        int c = fc + dc;
                        if (Position.isValid(r, c)) {
                            Piece target = squares[r][c];
                            if (target == null || target.getColor() != color) {
                                moves.add(new Move(from, Position.of(r, c)));
                            }
                        }
                    }
                }

                addCastlingMoves(moves, from, color);
            }
        }

        return moves;
    }

    /**
     * Genera le mosse di arrocco verificando tutte le condizioni regolamentari:
     * il Re deve essere ancora sulla casella di partenza, la Torre deve essere del
     * colore giusto sulla sua casella, le caselle di attraversamento e di arrivo
     * devono essere vuote e il Re non deve essere in scacco né attraversare caselle
     * attaccate.
     */
    private void addCastlingMoves(List<Move> moves, Position from, PieceColor color) {
        if (isKingInCheck(color)) return;
        if (!kingOnHomeSquare(color)) return;

        boolean isWhite = (color == PieceColor.WHITE);
        int homeRow = isWhite ? 7 : 0;
        PieceColor opponent = color.opposite();

        // Arrocco corto: Re e1/e8 -> g1/g8, Torre h1/h8 -> f1/f8
        boolean shortAllowed = isWhite ? !whiteRookKingsideMoved : !blackRookKingsideMoved;
        if (shortAllowed
                && isOwnRookAt(homeRow, 7, color)
                && squares[homeRow][5] == null
                && squares[homeRow][6] == null
                && !isSquareAttacked(Position.of(homeRow, 5), opponent)
                && !isSquareAttacked(Position.of(homeRow, 6), opponent)) {
            moves.add(new Move(from, Position.of(homeRow, 6)));
        }

        // Arrocco lungo: Re e1/e8 -> c1/c8, Torre a1/a8 -> d1/d8
        boolean longAllowed = isWhite ? !whiteRookQueensideMoved : !blackRookQueensideMoved;
        if (longAllowed
                && isOwnRookAt(homeRow, 0, color)
                && squares[homeRow][1] == null
                && squares[homeRow][2] == null
                && squares[homeRow][3] == null
                && !isSquareAttacked(Position.of(homeRow, 3), opponent)
                && !isSquareAttacked(Position.of(homeRow, 2), opponent)) {
            moves.add(new Move(from, Position.of(homeRow, 2)));
        }
    }

    /**
     * Il Re e' sulla casella di partenza (e1 per il Bianco, e8 per il Nero).
     * Necessario perche' i diritti di arrocco siano significativi: senza questo controllo
     * un diritto ereditato da un FEN malformato genererebbe un "arrocco" che teletrasporta il Re.
     */
    private boolean kingOnHomeSquare(PieceColor color) {
        return kingOn(squares, color);
    }

    private boolean isOwnRookAt(int row, int col, PieceColor color) {
        return rookAt(squares, row, col, color);
    }

    private void addRayMoves(List<Move> moves, Position from, PieceColor color, int[][] directions) {
        for (int[] dir : directions) {
            int r = from.getRow() + dir[0];
            int c = from.getCol() + dir[1];
            while (Position.isValid(r, c)) {
                Piece target = squares[r][c];
                if (target == null) {
                    moves.add(new Move(from, Position.of(r, c)));
                } else {
                    if (target.getColor() != color) {
                        moves.add(new Move(from, Position.of(r, c)));
                    }
                    break;
                }
                r += dir[0];
                c += dir[1];
            }
        }
    }

    /**
     * Verifica se una mossa è legale rispettando le regole e garantendo che il Re non rimanga sotto scacco.
     */
    public boolean isLegalMove(Move move) {
        if (move == null) return false;
        Piece piece = getPiece(move.getFrom());
        if (piece == null || piece.getColor() != turn) {
            return false;
        }

        // Un pedone non puo' essere promosso a Re: altrimenti si avrebbero due Re.
        if (move.getPromotion() == PieceType.KING) {
            return false;
        }

        List<Move> pseudoLegalMoves = getPseudoLegalMoves(move.getFrom());
        boolean matchesPseudo = false;
        for (Move plm : pseudoLegalMoves) {
            if (plm.getFrom().equals(move.getFrom()) && plm.getTo().equals(move.getTo())) {
                // Se c'è promozione, deve combaciare (oppure se non specificata dal client si assume DONNA)
                if (plm.getPromotion() != null) {
                    if (move.getPromotion() == null || move.getPromotion() == plm.getPromotion()) {
                        matchesPseudo = true;
                        break;
                    }
                } else {
                    matchesPseudo = true;
                    break;
                }
            }
        }

        if (!matchesPseudo) {
            return false;
        }

        // Simula la mossa su una copia per verificare che il Re del giocatore non sia sotto scacco
        ChessBoard boardCopy = this.copy();
        boardCopy.applyMoveInternal(move);
        return !boardCopy.isKingInCheck(this.turn);
    }

    /**
     * Applica internamente la mossa aggiornando la scacchiera, en passant, arrocco e turno.
     */
    protected void applyMoveInternal(Move move) {
        Position from = move.getFrom();
        Position to = move.getTo();
        Piece movingPiece = getPiece(from);

        if (movingPiece == null) return;

        // Reset o aggiornamento En Passant
        Position nextEnPassantTarget = null;

        // Gestione pedoni
        if (movingPiece.getType() == PieceType.PAWN) {
            // Mossa di 2 passi -> imposta en passant target
            if (Math.abs(to.getRow() - from.getRow()) == 2) {
                nextEnPassantTarget = Position.of((from.getRow() + to.getRow()) / 2, from.getCol());
            }
            // Cattura En Passant
            else if (enPassantTarget != null && to.equals(enPassantTarget)) {
                // Rimuovi il pedone avversario catturato
                squares[from.getRow()][to.getCol()] = null;
            }
            // Promozione
            int promoRow = (movingPiece.getColor() == PieceColor.WHITE) ? 0 : 7;
            if (to.getRow() == promoRow) {
                PieceType promoType = move.getPromotion() != null ? move.getPromotion() : PieceType.QUEEN;
                movingPiece = new Piece(promoType, movingPiece.getColor());
            }
            halfmoveClock = 0;
        } else if (squares[to.getRow()][to.getCol()] != null) {
            halfmoveClock = 0;
        } else {
            halfmoveClock++;
        }

        // Gestione Arrocco (Re si muove di 2 caselle orizzontali)
        if (movingPiece.getType() == PieceType.KING) {
            if (from.getCol() == 4 && to.getCol() == 6) {
                // Arrocco corto: sposta la torre da col 7 a col 5
                Piece rook = squares[from.getRow()][7];
                squares[from.getRow()][7] = null;
                squares[from.getRow()][5] = rook;
            } else if (from.getCol() == 4 && to.getCol() == 2) {
                // Arrocco lungo: sposta la torre da col 0 a col 3
                Piece rook = squares[from.getRow()][0];
                squares[from.getRow()][0] = null;
                squares[from.getRow()][3] = rook;
            }

            if (movingPiece.getColor() == PieceColor.WHITE) {
                whiteKingMoved = true;
            } else {
                blackKingMoved = true;
            }
        }

        // Se si muove una torre o viene catturata in un angolo, invalida l'arrocco
        if (from.getRow() == 7 && from.getCol() == 7) whiteRookKingsideMoved = true;
        if (from.getRow() == 7 && from.getCol() == 0) whiteRookQueensideMoved = true;
        if (from.getRow() == 0 && from.getCol() == 7) blackRookKingsideMoved = true;
        if (from.getRow() == 0 && from.getCol() == 0) blackRookQueensideMoved = true;

        if (to.getRow() == 7 && to.getCol() == 7) whiteRookKingsideMoved = true;
        if (to.getRow() == 7 && to.getCol() == 0) whiteRookQueensideMoved = true;
        if (to.getRow() == 0 && to.getCol() == 7) blackRookKingsideMoved = true;
        if (to.getRow() == 0 && to.getCol() == 0) blackRookQueensideMoved = true;

        // Sposta il pezzo
        squares[from.getRow()][from.getCol()] = null;
        squares[to.getRow()][to.getCol()] = movingPiece;
        enPassantTarget = nextEnPassantTarget;

        if (turn == PieceColor.BLACK) {
            fullmoveNumber++;
        }
        turn = turn.opposite();
    }

    /**
     * Esegue la mossa se valida. Ritorna true se la mossa è stata eseguita con successo.
     */
    public synchronized boolean makeMove(Move move) {
        if (!isLegalMove(move)) {
            return false;
        }

        // Se è una promozione e non è specificata, default a QUEEN
        Piece moving = getPiece(move.getFrom());
        Move moveWithPromo = move;
        if (moving.getType() == PieceType.PAWN) {
            int promoRow = (moving.getColor() == PieceColor.WHITE) ? 0 : 7;
            if (move.getTo().getRow() == promoRow && move.getPromotion() == null) {
                moveWithPromo = new Move(move.getFrom(), move.getTo(), PieceType.QUEEN);
            }
        }

        applyMoveInternal(moveWithPromo);

        // La posizione risultante viene registrata qui e non in applyMoveInternal():
        // applyMoveInternal() e' usata anche per simulare mosse, che non devono
        // influenzare il conteggio delle ripetizioni.
        repetitionCounts.merge(getPositionKey(), 1, Integer::sum);
        return true;
    }

    public List<Move> getAllLegalMoves(PieceColor color) {
        List<Move> legalMoves = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece piece = squares[r][c];
                if (piece != null && piece.getColor() == color) {
                    Position from = Position.of(r, c);
                    List<Move> pseudoMoves = getPseudoLegalMoves(from);
                    for (Move m : pseudoMoves) {
                        ChessBoard copy = this.copy();
                        copy.applyMoveInternal(m);
                        if (!copy.isKingInCheck(color)) {
                            legalMoves.add(m);
                        }
                    }
                }
            }
        }
        return legalMoves;
    }

    public boolean isCheckmate() {
        return isKingInCheck(turn) && getAllLegalMoves(turn).isEmpty();
    }

    public boolean isStalemate() {
        return !isKingInCheck(turn) && getAllLegalMoves(turn).isEmpty();
    }

    /**
     * Crea una nuova scacchiera a partire da una stringa FEN.
     * I diritti di arrocco sono conservati fedelmente come nel FEN: la loro
     * applicabilita' viene comunque verificata in fase di generazione mosse.
     *
     * @throws IllegalArgumentException se il FEN non e' valido
     */
    public static ChessBoard fromFen(String fen) {
        if (fen == null || fen.isBlank()) {
            throw new IllegalArgumentException("FEN string cannot be null or empty");
        }
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 4) {
            throw new IllegalArgumentException("Invalid FEN: expected at least 4 fields, got " + parts.length + " in \"" + fen + "\"");
        }

        ChessBoard board = new ChessBoard();
        board.loadFen(parts);
        return board;
    }

    /**
     * Carica una posizione FEN su questa istanza, azzerando lo stato di partita.
     *
     * @return true se il caricamento e' riuscito
     */
    public boolean loadFen(String fen) {
        if (fen == null) return false;
        try {
            loadFen(fen.trim().split("\\s+"));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void loadFen(String[] fields) {
        if (fields.length < 4) {
            throw new IllegalArgumentException("Invalid FEN: expected at least 4 fields, got " + fields.length);
        }

        // Tutto viene validato e costruito in strutture locali e solo alla fine copiato
        // nei campi dell'istanza: se il FEN e' malformato la scacchiera resta
        // esattamente com'era, invece di trovarsi azzerata o parzialmente popolata.
        Piece[][] parsedPlacement = parsePiecePlacement(fields[0]);
        PieceColor parsedTurn = parseTurn(fields[1]);
        Position parsedEnPassant = parseEnPassant(fields[3]);
        int parsedHalfmove = parseNonNegative(fields.length > 4 ? fields[4] : "0", "halfmove clock");
        int parsedFullmove = parseNonNegative(fields.length > 5 ? fields[5] : "1", "fullmove number");
        if (parsedFullmove < 1) {
            throw new IllegalArgumentException("Invalid FEN: fullmove number must be >= 1");
        }

        // I diritti di arrocco sono ricalcolati sulla disposizione appena validata:
        // un diritto dichiarato nel FEN senza Re sulla casella iniziale o senza la Torre
        // corrispondente viene scartato, perche' non potrebbe generare un arrocco valido.
        String rights = fields[2];
        boolean newWhiteKingMoved = !kingOn(parsedPlacement, PieceColor.WHITE);
        boolean newBlackKingMoved = !kingOn(parsedPlacement, PieceColor.BLACK);
        boolean newWhiteRookKingside = !(rights.indexOf('K') >= 0 && rookAt(parsedPlacement, 7, 7, PieceColor.WHITE));
        boolean newWhiteRookQueenside = !(rights.indexOf('Q') >= 0 && rookAt(parsedPlacement, 7, 0, PieceColor.WHITE));
        boolean newBlackRookKingside = !(rights.indexOf('k') >= 0 && rookAt(parsedPlacement, 0, 7, PieceColor.BLACK));
        boolean newBlackRookQueenside = !(rights.indexOf('q') >= 0 && rookAt(parsedPlacement, 0, 0, PieceColor.BLACK));

        // --- commit: da qui in poi non può più fallire ---
        for (int r = 0; r < 8; r++) {
            System.arraycopy(parsedPlacement[r], 0, squares[r], 0, 8);
        }
        this.turn = parsedTurn;
        this.enPassantTarget = parsedEnPassant;
        this.halfmoveClock = parsedHalfmove;
        this.fullmoveNumber = parsedFullmove;
        this.whiteKingMoved = newWhiteKingMoved;
        this.blackKingMoved = newBlackKingMoved;
        this.whiteRookKingsideMoved = newWhiteRookKingside;
        this.whiteRookQueensideMoved = newWhiteRookQueenside;
        this.blackRookKingsideMoved = newBlackRookKingside;
        this.blackRookQueensideMoved = newBlackRookQueenside;

        // La posizione appena caricata e' la prima occorrenza nella cronologia.
        repetitionCounts.clear();
        repetitionCounts.merge(getPositionKey(), 1, Integer::sum);
    }

    /**
     * Decodifica il primo campo FEN in una nuova matrice 8x8, senza toccare lo stato
     * della scacchiera corrente.
     */
    private static Piece[][] parsePiecePlacement(String boardField) {
        Piece[][] placement = new Piece[8][8];

        String[] ranks = boardField.split("/", -1);
        if (ranks.length != 8) {
            throw new IllegalArgumentException("Invalid FEN: expected 8 ranks, got " + ranks.length);
        }
        for (int r = 0; r < 8; r++) {
            int col = 0;
            for (int i = 0; i < ranks[r].length(); i++) {
                char ch = ranks[r].charAt(i);
                if (Character.isDigit(ch)) {
                    int run = ch - '0';
                    if (run == 0) {
                        throw new IllegalArgumentException("Invalid FEN: zero-length empty run in rank " + (8 - r));
                    }
                    col += run;
                } else {
                    Piece p = Piece.fromFenChar(ch);
                    if (p == null) {
                        throw new IllegalArgumentException("Invalid FEN: unknown piece '" + ch + "'");
                    }
                    if (col >= 8) {
                        throw new IllegalArgumentException("Invalid FEN: rank " + (8 - r) + " overflows 8 squares");
                    }
                    placement[r][col] = p;
                    col++;
                }
            }
            if (col != 8) {
                throw new IllegalArgumentException("Invalid FEN: rank " + (8 - r)
                        + " describes " + col + " squares, expected 8");
            }
        }
        return placement;
    }

    private static PieceColor parseTurn(String field) {
        if ("w".equalsIgnoreCase(field)) return PieceColor.WHITE;
        if ("b".equalsIgnoreCase(field)) return PieceColor.BLACK;
        throw new IllegalArgumentException("Invalid FEN: side to move must be 'w' or 'b', got \"" + field + "\"");
    }

    private static Position parseEnPassant(String field) {
        if ("-".equals(field)) return null;
        try {
            return Position.fromAlgebraic(field);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid FEN: bad en passant square \"" + field + "\"");
        }
    }

    private static boolean kingOn(Piece[][] board, PieceColor color) {
        int row = (color == PieceColor.WHITE) ? 7 : 0;
        Piece king = board[row][4];
        return king != null && king.getType() == PieceType.KING && king.getColor() == color;
    }

    private static boolean rookAt(Piece[][] board, int row, int col, PieceColor color) {
        Piece p = board[row][col];
        return p != null && p.getType() == PieceType.ROOK && p.getColor() == color;
    }

    private static int parseNonNegative(String value, String what) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException("Invalid FEN: " + what + " cannot be negative, got " + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid FEN: " + what + " is not a number: \"" + value + "\"");
        }
    }

    /**
     * Stringa dei diritti di arrocco in formato FEN ("KQkq"), oppure "-" se assenti.
     * Un diritto e' riportato solo se il Re e' ancora sulla casella di partenza.
     */
    public String getFenCastlingRights() {
        StringBuilder sb = new StringBuilder();
        if (!whiteKingMoved && kingOnHomeSquare(PieceColor.WHITE)) {
            if (!whiteRookKingsideMoved) sb.append('K');
            if (!whiteRookQueensideMoved) sb.append('Q');
        }
        if (!blackKingMoved && kingOnHomeSquare(PieceColor.BLACK)) {
            if (!blackRookKingsideMoved) sb.append('k');
            if (!blackRookQueensideMoved) sb.append('q');
        }
        return sb.length() > 0 ? sb.toString() : "-";
    }

    /**
     * Serializza la posizione corrente in notazione Forsyth-Edwards (FEN).
     */
    public String toFen() {
        return toFenPiecePlacement() + (turn == PieceColor.WHITE ? " w " : " b ")
                + getFenCastlingRights() + " "
                + (enPassantTarget != null ? enPassantTarget.toAlgebraic() : "-") + " "
                + halfmoveClock + " " + fullmoveNumber;
    }

    /**
     * Primo campo FEN: disposizione dei pezzi compressa con i numeri delle caselle vuote.
     */
    private String toFenPiecePlacement() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            int emptyCount = 0;
            for (int c = 0; c < 8; c++) {
                Piece p = squares[r][c];
                if (p == null) {
                    emptyCount++;
                } else {
                    if (emptyCount > 0) {
                        sb.append(emptyCount);
                        emptyCount = 0;
                    }
                    sb.append(p.getFenChar());
                }
            }
            if (emptyCount > 0) {
                sb.append(emptyCount);
            }
            if (r < 7) {
                sb.append('/');
            }
        }
        return sb.toString();
    }

    /**
     * Render ASCII semplice per debug o visualizzazione terminale.
     */
    public String toAsciiBoard() {
        StringBuilder sb = new StringBuilder();
        sb.append("  +-----------------+\n");
        for (int r = 0; r < 8; r++) {
            sb.append(8 - r).append(" | ");
            for (int c = 0; c < 8; c++) {
                Piece p = squares[r][c];
                sb.append(p != null ? p.getFenChar() : '.').append(' ');
            }
            sb.append("|\n");
        }
        sb.append("  +-----------------+\n");
        sb.append("    a b c d e f g h\n");
        return sb.toString();
    }
}
