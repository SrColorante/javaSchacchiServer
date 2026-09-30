package org.schacchi;

import org.junit.jupiter.api.Test;
import org.schacchi.model.*;
import org.schacchi.server.GameSession;
import org.schacchi.server.SessionManager;

import static org.junit.jupiter.api.Assertions.*;

public class ChessServerTest {

    @Test
    public void testInitialPosition() {
        ChessBoard board = new ChessBoard();
        assertEquals(PieceColor.WHITE, board.getTurn());
        assertNotNull(board.getPiece(Position.fromAlgebraic("e1")));
        assertEquals(PieceType.KING, board.getPiece(Position.fromAlgebraic("e1")).getType());
        assertEquals(PieceColor.WHITE, board.getPiece(Position.fromAlgebraic("e1")).getColor());

        assertNotNull(board.getPiece(Position.fromAlgebraic("e8")));
        assertEquals(PieceType.KING, board.getPiece(Position.fromAlgebraic("e8")).getType());
        assertEquals(PieceColor.BLACK, board.getPiece(Position.fromAlgebraic("e8")).getColor());

        assertFalse(board.isKingInCheck(PieceColor.WHITE));
        assertFalse(board.isKingInCheck(PieceColor.BLACK));
        assertFalse(board.isCheckmate());
        assertFalse(board.isStalemate());
    }

    @Test
    public void testLegalAndIllegalMoves() {
        ChessBoard board = new ChessBoard();

        // Mossa legale: e2e4
        Move e2e4 = Move.fromUci("e2e4");
        assertTrue(board.isLegalMove(e2e4));
        assertTrue(board.makeMove(e2e4));
        assertEquals(PieceColor.BLACK, board.getTurn());

        // Mossa illegale: e7e5 di 3 caselle non consentita
        Move invalid = Move.fromUci("e7e4");
        assertFalse(board.isLegalMove(invalid));

        // Mossa legale per il nero: e7e5
        Move e7e5 = Move.fromUci("e7e5");
        assertTrue(board.isLegalMove(e7e5));
        assertTrue(board.makeMove(e7e5));
        assertEquals(PieceColor.WHITE, board.getTurn());
    }

    @Test
    public void testFoolsMateCheckmate() {
        ChessBoard board = new ChessBoard();

        // 1. f3 e5
        assertTrue(board.makeMove(Move.fromUci("f2f3")));
        assertTrue(board.makeMove(Move.fromUci("e7e5")));

        // 2. g4 Qh4#
        assertTrue(board.makeMove(Move.fromUci("g2g4")));
        assertTrue(board.makeMove(Move.fromUci("d8h4")));

        // Il Bianco deve essere sotto scacco e in Scacco Matto
        assertTrue(board.isKingInCheck(PieceColor.WHITE));
        assertTrue(board.isCheckmate());
        assertEquals(0, board.getAllLegalMoves(PieceColor.WHITE).size());
    }

    @Test
    public void testCastling() {
        ChessBoard board = new ChessBoard();
        // Liberiamo la diagonale per l'arrocco corto bianco: e4, e5, Nf3, Nc6, Bc4, Bc5, 0-0
        board.makeMove(Move.fromUci("e2e4"));
        board.makeMove(Move.fromUci("e7e5"));
        board.makeMove(Move.fromUci("g1f3"));
        board.makeMove(Move.fromUci("b8c6"));
        board.makeMove(Move.fromUci("f1c4"));
        board.makeMove(Move.fromUci("f8c5"));

        // Arrocco corto bianco: Re e1 -> g1
        Move castleShort = Move.fromUci("e1g1");
        assertTrue(board.isLegalMove(castleShort));
        assertTrue(board.makeMove(castleShort));

        // Verifica che il Re sia su g1 e la Torre su f1
        assertEquals(PieceType.KING, board.getPiece(Position.fromAlgebraic("g1")).getType());
        assertEquals(PieceType.ROOK, board.getPiece(Position.fromAlgebraic("f1")).getType());
        assertNull(board.getPiece(Position.fromAlgebraic("e1")));
        assertNull(board.getPiece(Position.fromAlgebraic("h1")));
    }

    @Test
    public void testEnPassant() {
        ChessBoard board = new ChessBoard();
        board.makeMove(Move.fromUci("e2e4"));
        board.makeMove(Move.fromUci("a7a6"));
        board.makeMove(Move.fromUci("e4e5"));
        // Il nero avanza il pedone d7 di due passi fino a d5
        board.makeMove(Move.fromUci("d7d5"));

        assertEquals(Position.fromAlgebraic("d6"), board.getEnPassantTarget());

        // Il bianco cattura en passant: e5d6
        Move enPassant = Move.fromUci("e5d6");
        assertTrue(board.isLegalMove(enPassant));
        assertTrue(board.makeMove(enPassant));

        // Il pedone nero in d5 deve essere stato rimosso
        assertNull(board.getPiece(Position.fromAlgebraic("d5")));
        assertNotNull(board.getPiece(Position.fromAlgebraic("d6")));
        assertEquals(PieceColor.WHITE, board.getPiece(Position.fromAlgebraic("d6")).getColor());
    }

    @Test
    public void testPawnPromotion() {
        ChessBoard board = new ChessBoard();
        // Svuotiamo la casella a8 e piazziamo un pedone bianco in a7 per testare la promozione
        board.setPiece(Position.fromAlgebraic("a8"), null);
        board.setPiece(Position.fromAlgebraic("a7"), new Piece(PieceType.PAWN, PieceColor.WHITE));

        Move promoQueen = Move.fromUci("a7a8q");
        assertTrue(board.isLegalMove(promoQueen));
        assertTrue(board.makeMove(promoQueen));

        Piece promoted = board.getPiece(Position.fromAlgebraic("a8"));
        assertNotNull(promoted);
        assertEquals(PieceType.QUEEN, promoted.getType());
        assertEquals(PieceColor.WHITE, promoted.getColor());
    }

    @Test
    public void testMultipleSessionsManagement() {
        SessionManager manager = new SessionManager();

        // Crea stanza 1
        GameSession s1 = manager.createSession("room_custom_1", "Torneo A", null);
        assertNotNull(s1);
        assertEquals("room_custom_1", s1.getSessionId());

        // Crea stanza 2
        GameSession s2 = manager.createSession(null, "Partita Amichevole", null);
        assertNotNull(s2);
        assertTrue(s2.getSessionId().startsWith("room_"));

        assertNotEquals(s1.getSessionId(), s2.getSessionId());
        assertEquals(2, manager.getAllSessions().size());
        assertEquals(2, manager.getOpenSessions().size());
    }

    public static void main(String[] args) {
        ChessServerTest test = new ChessServerTest();
        test.testInitialPosition();
        test.testLegalAndIllegalMoves();
        test.testFoolsMateCheckmate();
        test.testCastling();
        test.testEnPassant();
        test.testPawnPromotion();
        test.testMultipleSessionsManagement();
        System.out.println("TUTTI I TEST ESEGUITI CON SUCCESSO!");
    }
}
