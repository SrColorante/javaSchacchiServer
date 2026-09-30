package org.schacchi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.schacchi.model.ChessBoard;
import org.schacchi.model.Move;
import org.schacchi.model.Piece;
import org.schacchi.model.PieceColor;
import org.schacchi.model.PieceType;
import org.schacchi.model.Position;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test del motore di regole: generazione delle mosse (perft), regole speciali,
 * serializzazione FEN e condizioni di patta.
 */
public class ChessBoardTest {

    // ---------------------------------------------------------------- perft

    /**
     * Perft (performance test): conta i nodi dell'albero di mosse fino alla profondita' richiesta.
     * E' il test di riferimento per verificare la correttezza di un generatore di mosse,
     * perche' i valori attesi sono noti e calcolati indipendentemente.
     */
    private static long perft(ChessBoard board, int depth) {
        if (depth == 0) return 1;
        List<Move> moves = board.getAllLegalMoves(board.getTurn());
        if (depth == 1) return moves.size();
        long nodes = 0;
        for (Move m : moves) {
            ChessBoard copy = board.copy();
            if (copy.makeMove(m)) {
                nodes += perft(copy, depth - 1);
            }
        }
        return nodes;
    }

    private static void assertPerft(String fen, int depth, long expected) {
        ChessBoard board = ChessBoard.fromFen(fen);
        long actual = perft(board, depth);
        assertEquals(expected, actual,
                "Perft " + depth + " non corrisponde per la posizione: " + fen);
    }

    @Nested
    @DisplayName("Perft - generazione delle mosse")
    class PerftTests {

        @Test
        @DisplayName("Posizione iniziale")
        void perftInitialPosition() {
            String start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
            assertPerft(start, 1, 20);
            assertPerft(start, 2, 400);
            assertPerft(start, 3, 8_902);
            assertPerft(start, 4, 197_281);
        }

        @Test
        @DisplayName("Kiwipete - copre arrocco, en passant e promozione")
        void perftKiwipete() {
            String kiwipete = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";
            assertPerft(kiwipete, 1, 48);
            assertPerft(kiwipete, 2, 2_039);
            assertPerft(kiwipete, 3, 97_862);
        }

        @Test
        @DisplayName("Posizione 3 - en passant e promozioni in posizioni limite")
        void perftEdgePosition3() {
            String pos3 = "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1";
            assertPerft(pos3, 1, 14);
            assertPerft(pos3, 2, 191);
            assertPerft(pos3, 3, 2_812);
            assertPerft(pos3, 4, 43_238);
        }

        @Test
        @DisplayName("Posizione 4 - promozioni multiple")
        void perftEdgePosition4() {
            String pos4 = "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1";
            assertPerft(pos4, 1, 6);
            assertPerft(pos4, 2, 264);
            assertPerft(pos4, 3, 9_467);
        }

        @Test
        @DisplayName("Posizione 5 - promozione e cavallo che promuove")
        void perftEdgePosition5() {
            String pos5 = "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8";
            assertPerft(pos5, 1, 44);
            assertPerft(pos5, 2, 1_486);
            assertPerft(pos5, 3, 62_379);
        }
    }

    // ---------------------------------------------------------------- regole speciali

    @Nested
    @DisplayName("Regole speciali")
    class SpecialRulesTests {

        @Test
        @DisplayName("En passant non e' possibile se espone il Re")
        void enPassantCannotExposeKing() {
            // Il Re Bianco e' in linea con la Torre Nera sulla quinta di re:
            // la cattura en passant aprirebbe la colonna e sarebbe illegale.
            ChessBoard board = ChessBoard.fromFen("8/8/8/8/k1pP3R/8/8/4K3 b - d3 0 1");
            // Il Nero potrebbe catturare in d3 en passant, scoprendo il Re Bianco su a4
            assertFalse(board.isLegalMove(Move.fromUci("c4d3")),
                    "L'en passant che espone il proprio Re non deve essere legale");
        }

        @Test
        @DisplayName("En passant con cattura reale avviene")
        void enPassantRemovesCapturedPawn() {
            ChessBoard board = ChessBoard.fromFen("8/8/8/3pP3/8/8/8/4K2k w - d6 0 1");
            assertTrue(board.makeMove(Move.fromUci("e5d6")));
            assertNull(board.getPiece(Position.fromAlgebraic("d5")), "Il pedone catturato deve sparire");
            assertNotNull(board.getPiece(Position.fromAlgebraic("d6")));
            assertEquals(PieceColor.WHITE, board.getPiece(Position.fromAlgebraic("d6")).getColor());
        }

        @Test
        @DisplayName("Il pedone non puo' essere promosso a Re")
        void pawnCannotPromoteToKing() {
            ChessBoard board = ChessBoard.fromFen("8/P7/8/8/8/8/8/4K2k w - - 0 1");
            assertFalse(board.isLegalMove(Move.fromUci("a7a8k")), "Promuovere a Re e' illegale");
            assertTrue(board.isLegalMove(Move.fromUci("a7a8q")));
            assertTrue(board.isLegalMove(Move.fromUci("a7a8n")));
            assertTrue(board.isLegalMove(Move.fromUci("a7a8r")));
            assertTrue(board.isLegalMove(Move.fromUci("a7a8b")));
        }

        @Test
        @DisplayName("Le quattro promozioni generano mosse distinte")
        void promotionGeneratesFourMoves() {
            ChessBoard board = ChessBoard.fromFen("8/P7/8/8/8/8/8/4K2k w - - 0 1");
            List<Move> promotions = board.getPseudoLegalMoves(Position.fromAlgebraic("a7"));
            assertEquals(4, promotions.size());
        }

        @Test
        @DisplayName("Promozione senza pezzo specificato diventa Donna")
        void promotionDefaultsToQueen() {
            ChessBoard board = ChessBoard.fromFen("8/P7/8/8/8/8/8/4K2k w - - 0 1");
            assertTrue(board.makeMove(Move.fromUci("a7a8")));
            assertEquals(PieceType.QUEEN, board.getPiece(Position.fromAlgebraic("a8")).getType());
        }

        @Test
        @DisplayName("Arrocco non consentito se attraversa una casella attaccata")
        void castlingThroughAttackedSquare() {
            // Torre Nera su f8, colonna f libera: controlla f1, che il Re Bianco
            // dovrebbe attraversare passando da e1 a g1.
            ChessBoard board = ChessBoard.fromFen("4kr2/8/8/8/8/8/8/4K2R w K - 0 1");
            assertFalse(board.isLegalMove(Move.fromUci("e1g1")),
                    "Il Re non deve poter attraversare f1 sotto attacco della Torre Nera");
            assertTrue(board.isLegalMove(Move.fromUci("e1d1")),
                    "Il Re puo' comunque spostarsi su una casella sicura");
        }

        @Test
        @DisplayName("Arrocco non consentito se il Re e' in scacco")
        void castlingWhileInCheck() {
            // Torre Nera in e8 sulla colonna del Re Bianco: scacco, quindi niente arrocco.
            ChessBoard board = ChessBoard.fromFen("4r1k1/8/8/8/8/8/8/R3K2R w KQ - 0 1");
            assertTrue(board.isKingInCheck(PieceColor.WHITE));
            assertFalse(board.isLegalMove(Move.fromUci("e1g1")), "Non si arrocca in scacco");
            assertFalse(board.isLegalMove(Move.fromUci("e1c1")), "Non si arrocca in scacco");
        }

        @Test
        @DisplayName("Arrocco consuma i diritti e la Torre raggiunge la casella corretta")
        void castlingMovesRookAndConsumesRights() {
            ChessBoard board = ChessBoard.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
            assertTrue(board.makeMove(Move.fromUci("e1g1")));
            assertEquals(PieceType.KING, board.getPiece(Position.fromAlgebraic("g1")).getType());
            assertEquals(PieceType.ROOK, board.getPiece(Position.fromAlgebraic("f1")).getType());
            assertNull(board.getPiece(Position.fromAlgebraic("e1")));
            assertNull(board.getPiece(Position.fromAlgebraic("h1")));
            // Nessun diritto residuo per il Bianco
            assertFalse(board.getFenCastlingRights().contains("K"));
            assertFalse(board.getFenCastlingRights().contains("Q"));
        }

        @Test
        @DisplayName("Arrocco lungo sposta Torre in d1 e Re in c1")
        void queensideCastling() {
            ChessBoard board = ChessBoard.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
            assertTrue(board.makeMove(Move.fromUci("e1c1")));
            assertEquals(PieceType.KING, board.getPiece(Position.fromAlgebraic("c1")).getType());
            assertEquals(PieceType.ROOK, board.getPiece(Position.fromAlgebraic("d1")).getType());
            assertNull(board.getPiece(Position.fromAlgebraic("a1")));
        }

        @Test
        @DisplayName("Diritto di arrocco ereditato dal FEN senza Re sulla casella iniziale non genera mosse")
        void castlingRequiresKingOnHomeSquare() {
            // Il Re Bianco e' su d1 ma il FEN dichiara il diritto 'K':
            // non deve essere possibile generare un "arrocco" che teletrasporti il Re.
            ChessBoard board = ChessBoard.fromFen("4k3/8/8/8/8/8/8/3K3R w K - 0 1");
            List<Move> kingMoves = board.getPseudoLegalMoves(Position.fromAlgebraic("d1"));
            for (Move m : kingMoves) {
                assertFalse(m.getTo().getCol() - m.getFrom().getCol() == 2 && m.getTo().getRow() == 7,
                        "Non deve essere generato un pseudo-arrocco da una posizione incoerente");
            }
        }

        @Test
        @DisplayName("La Torre di arrocco deve essere del colore del Re")
        void castlingRequiresOwnColoredRook() {
            // Torre Nera su h1, diritto Bianco dichiarato: nessun arrocco del Bianco.
            ChessBoard board = ChessBoard.fromFen("4k3/8/8/8/8/8/8/4K2k w K - 0 1");
            assertFalse(board.isLegalMove(Move.fromUci("e1g1")),
                    "Non si puo' arroccare con una Torre di colore avverso");
        }
    }

    // ---------------------------------------------------------------- FEN

    @Nested
    @DisplayName("Notazione FEN")
    class FenTests {

        @Test
        @DisplayName("Round-trip della posizione iniziale")
        void fenRoundTripInitial() {
            String start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
            assertEquals(start, ChessBoard.fromFen(start).toFen());
        }

        @Test
        @DisplayName("Round-trip su posizioni arbitrarie")
        void fenRoundTripVarious() {
            String[] fens = {
                    "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
                    "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
                    "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
                    "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
                    "4k3/8/8/8/8/8/4P3/4K3 b - - 13 47",
            };
            for (String fen : fens) {
                assertEquals(fen, ChessBoard.fromFen(fen).toFen(), "Round-trip fallito per: " + fen);
            }
        }

        @Test
        @DisplayName("Tutti i campi FEN vengono ripresi correttamente")
        void fenReadsAllFields() {
            ChessBoard board = ChessBoard.fromFen("r3k2r/8/8/8/8/8/8/R3K2R b Kq e6 7 42");
            assertEquals(PieceColor.BLACK, board.getTurn());
            assertEquals(Position.fromAlgebraic("e6"), board.getEnPassantTarget());
            assertEquals(7, board.getHalfmoveClock());
            assertEquals(42, board.getFullmoveNumber());
            assertEquals("Kq", board.getFenCastlingRights());
        }

        @Test
        @DisplayName("loadFromFen non lancia eccezioni e segnala il fallimento")
        void loadFenReturnsFalseOnGarbage() {
            ChessBoard board = new ChessBoard();
            assertFalse(board.loadFen("spazzatura totale"));
            assertFalse(board.loadFen(null));
            assertFalse(board.loadFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq"));
            // La scacchiera deve restare quella iniziale dopo un caricamento fallito
            assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", board.toFen());
        }

        @Test
        @DisplayName("Un FEN malformato non lascia la scacchiera parzialmente popolata")
        void loadFenIsAtomic() {
            // Caso critico: la disposizione pezzi viene decodificata per prima, quindi
            // un suo errore deve comunque lasciare intatta la scacchiera.
            ChessBoard board = ChessBoard.fromFen("8/8/8/8/8/8/8/R3K2R w KQ - 3 9");
            String before = board.toFen();

            assertFalse(board.loadFen("8/8/8/8/8/8/8/QQQQQQQQQ w - - 0 1"), "Rango da 9 pezzi: deve fallire");
            assertEquals(before, board.toFen(), "La scacchiera deve restare invariata dopo un FEN non valido");

            assertFalse(board.loadFen("9/8/8/8/8/8/8/8 w - - 0 1"), "Rango da 9 caselle: deve fallire");
            assertEquals(before, board.toFen());

            assertFalse(board.loadFen("8/8/8/8/8/8/8/8 w - - abc 1"), "Halfmove non numerico: deve fallire");
            assertEquals(before, board.toFen());

            assertFalse(board.loadFen("8/8/8/8/8/8/8/8 w - - 0 0"), "Fullmove 0 non valido: deve fallire");
            assertEquals(before, board.toFen());

            assertFalse(board.loadFen("8/8/8/8/8/8/8/8 w - - 0 1extra"), "Campo extra non numerico: deve fallire");
            assertEquals(before, board.toFen());

            // Dopo i fallimenti un FEN valido deve comunque caricarsi
            assertTrue(board.loadFen("8/8/8/8/8/8/4K3/4k3 w - - 0 1"));
            assertEquals("8/8/8/8/8/8/4K3/4k3 w - - 0 1", board.toFen());
        }

        @Test
        @DisplayName("FEN malformati vengono rifiutati")
        void invalidFenIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> ChessBoard.fromFen(""));
            assertThrows(IllegalArgumentException.class, () -> ChessBoard.fromFen("rnbqkbnr/pppppppp w KQkq - 0 1"));
            assertThrows(IllegalArgumentException.class, () -> ChessBoard.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP w KQkq - 0 1"));
            assertThrows(IllegalArgumentException.class, () -> ChessBoard.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR x KQkq - 0 1"));
            assertThrows(IllegalArgumentException.class, () -> ChessBoard.fromFen("rnbqkbnr/pppppppp/9/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"));
            assertThrows(IllegalArgumentException.class, () -> ChessBoard.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq z9 0 1"));
        }

        @Test
        @DisplayName("Una posizione caricata da FEN genera le stesse mosse")
        void fenLoadedPositionGeneratesCorrectMoves() {
            // Re Bianco su e1 bloccato dal proprio pedone in e2:
            // 4 mosse di Re (d1, f1, d2, f2) + 2 mosse di pedone (e3, e4).
            ChessBoard board = ChessBoard.fromFen("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1");
            assertEquals(6, board.getAllLegalMoves(PieceColor.WHITE).size(),
                    "Re Bianco con pedone in e2 ha 4 mosse di Re e 2 di pedone");
        }
    }

    // ---------------------------------------------------------------- patta

    @Nested
    @DisplayName("Condizioni di patta")
    class DrawTests {

        @Test
        @DisplayName("Re contro Re: materiale insufficiente")
        void bareKingsInsufficient() {
            assertTrue(ChessBoard.fromFen("4k3/8/8/8/8/8/8/4K3 w - - 0 1").isInsufficientMaterial());
        }

        @Test
        @DisplayName("Re e Cavallo: materiale insufficiente")
        void kingAndKnightInsufficient() {
            assertTrue(ChessBoard.fromFen("4k3/8/8/8/8/8/8/3NK3 w - - 0 1").isInsufficientMaterial());
        }

        @Test
        @DisplayName("Re e due Cavalli: materiale insufficiente")
        void kingAndTwoKnightsInsufficient() {
            assertTrue(ChessBoard.fromFen("4k3/8/8/8/8/8/8/3NKN2 w - - 0 1").isInsufficientMaterial());
        }

        @Test
        @DisplayName("Re e Alfiere: materiale insufficiente")
        void kingAndBishopInsufficient() {
            assertTrue(ChessBoard.fromFen("4k3/8/8/8/8/8/8/3BK3 w - - 0 1").isInsufficientMaterial());
        }

        @Test
        @DisplayName("Due Alfieri su colori diversi: materiale sufficiente")
        void twoBishopsOppositeColorsSufficient() {
            assertFalse(ChessBoard.fromFen("4k3/8/8/8/8/8/8/2B1KB2 w - - 0 1").isInsufficientMaterial());
        }

        @Test
        @DisplayName("Tre Alfieri: materiale sufficiente")
        void threeBishopsSufficient() {
            assertFalse(ChessBoard.fromFen("4k3/8/8/8/8/8/8/2B1KB1B w - - 0 1").isInsufficientMaterial());
        }

        @Test
        @DisplayName("Alfieri su caselle dello stesso colore: materiale insufficiente")
        void bishopsSameSquareColorInsufficient() {
            // c1 (r=7,c=2) -> (7+2)%2=1 ; f1 (r=7,c=5) -> (7+5)%2=0 : colori diversi
            assertFalse(ChessBoard.fromFen("4k3/8/8/8/8/8/8/2B1KB2 w - - 0 1").isInsufficientMaterial());
            // d1 (r=7,c=3) -> 0 ; h1 (r=7,c=7) -> 0 : stesso colore
            assertTrue(ChessBoard.fromFen("4k3/8/8/8/8/8/8/3B1K1B w - - 0 1").isInsufficientMaterial());
        }

        @Test
        @DisplayName("La presenza di un pedone rende il materiale sufficiente")
        void pawnIsSufficientMaterial() {
            assertFalse(ChessBoard.fromFen("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1").isInsufficientMaterial());
        }

        @Test
        @DisplayName("Regola delle 50 mosse")
        void fiftyMoveRule() {
            ChessBoard board = ChessBoard.fromFen("4k3/8/8/8/8/8/4P3/4K3 w - - 99 60");
            assertFalse(board.isFiftyMoveDraw());
            board = ChessBoard.fromFen("4k3/8/8/8/8/8/4P3/4K3 w - - 100 60");
            assertTrue(board.isFiftyMoveDraw());
        }

        @Test
        @DisplayName("Il halfmove clock si azzera dopo cattura e mossa di pedone")
        void halfmoveClockResets() {
            ChessBoard board = ChessBoard.fromFen("4k3/8/8/3p4/4P3/8/8/4K3 w - - 40 60");
            assertEquals(40, board.getHalfmoveClock());
            // Mossa di pedone: reset
            assertTrue(board.makeMove(Move.fromUci("e4d5")));
            assertEquals(0, board.getHalfmoveClock());
        }

        /**
         * Ciclo di Cavalli che riporta alla posizione iniziale IDENTICA, diritti di
         * arrocco compresi. Serve un ciclo che non muova Re e Torri: spostare una
         * Torre da a1/h1 (o il Re) consuma il diritto di arrocco corrispondente e
         * quindi la posizione risultante non e' più legally-equivalente a quella iniziale.
         */
        private static final String REPETITION_FEN = "rn2k2r/8/8/8/8/8/8/RN2K2R w KQkq - 0 1";
        private static final String[] REPETITION_CYCLE = {"b1c3", "b8c6", "c3b1", "c6b8"};

        @Test
        @DisplayName("Ripetizione: stessa posizione per tre volte")
        void threefoldRepetition() {
            ChessBoard board = ChessBoard.fromFen(REPETITION_FEN);
            assertEquals(1, board.getRepetitionCount());
            assertFalse(board.isThreefoldRepetition());

            for (int i = 0; i < 2; i++) {
                for (String uci : REPETITION_CYCLE) {
                    assertTrue(board.makeMove(Move.fromUci(uci)), "Mossa ciclica fallita: " + uci);
                }
            }
            assertEquals(positionKey(REPETITION_FEN), board.getPositionKey(),
                    "La posizione (pezzi, turno, arrocco, en passant) deve essere tornata a quella iniziale");
            assertEquals(3, board.getRepetitionCount(), "La posizione iniziale deve comparire 3 volte");
            assertTrue(board.isThreefoldRepetition());
        }

        /**
         * I primi quattro campi FEN, cioe' la posizione ai fini della ripetizione.
         * I contatori (halfmove/fullmove) sono esclusi per definizione.
         */
        private static String positionKey(String fen) {
            return String.join(" ", java.util.Arrays.copyOf(fen.split("\\s+"), 4));
        }

        @Test
        @DisplayName("Ripetizione non viene falsata dalla simulazione delle mosse")
        void repetitionNotPollutedBySimulation() {
            ChessBoard board = ChessBoard.fromFen("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1");
            // Generare tutte le mosse legali simula molte posizioni su copie.
            board.getAllLegalMoves(PieceColor.WHITE);
            board.getAllLegalMoves(PieceColor.BLACK);
            assertEquals(1, board.getRepetitionCount(),
                    "La generazione delle mosse non deve incrementare il conteggio ripetizioni");
            assertFalse(board.isThreefoldRepetition());
        }

        @Test
        @DisplayName("Una sola occorrenza non e' ripetizione")
        void singleOccurrenceIsNotRepetition() {
            ChessBoard board = ChessBoard.fromFen(REPETITION_FEN);
            for (String uci : REPETITION_CYCLE) {
                assertTrue(board.makeMove(Move.fromUci(uci)));
            }
            assertEquals(2, board.getRepetitionCount());
            assertFalse(board.isThreefoldRepetition());
        }

        @Test
        @DisplayName("Spostare una Torre consuma il diritto e impedisce la ripetizione")
        void rookMovePreventsRepetition() {
            ChessBoard board = ChessBoard.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
            // Le Torri escono e rientrano sulla stessa casella: la disposizione finale
            // e' identica, ma i diritti di arrocco sono irrecuperabilmente persi,
            // quindi la posizione NON e' legally-equivalente a quella iniziale.
            for (String uci : new String[]{"h1h2", "a8a7", "h2h1", "a7a8"}) {
                assertTrue(board.makeMove(Move.fromUci(uci)), "Mossa fallita: " + uci);
            }
            assertEquals("r3k2r/8/8/8/8/8/8/R3K2R w Qk - 4 3", board.toFen(),
                    "Le Torri sono tornate a posto ma i diritti 'K' e 'q' sono andati persi");
            assertEquals(1, board.getRepetitionCount(),
                    "La perdita dei diritti di arrocco rende la posizione diversa");
            assertFalse(board.isThreefoldRepetition());
        }
    }

    // ---------------------------------------------------------------- copia

    @Nested
    @DisplayName("Copia della scacchiera")
    class CopyTests {

        @Test
        @DisplayName("La copia e' indipendente dall'originale")
        void copyIsIndependent() {
            ChessBoard original = new ChessBoard();
            ChessBoard copy = original.copy();
            assertTrue(copy.makeMove(Move.fromUci("e2e4")));
            assertNotNull(original.getPiece(Position.fromAlgebraic("e2")), "L'originale non deve cambiare");
            assertNull(original.getPiece(Position.fromAlgebraic("e4")));
        }

        @Test
        @DisplayName("La copia conserva lo stato di en passant e i contatori")
        void copyPreservesState() {
            ChessBoard original = ChessBoard.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 5 20");
            ChessBoard copy = original.copy();
            assertEquals(original.getEnPassantTarget(), copy.getEnPassantTarget());
            assertEquals(original.getHalfmoveClock(), copy.getHalfmoveClock());
            assertEquals(original.getFullmoveNumber(), copy.getFullmoveNumber());
            assertEquals(original.toFen(), copy.toFen());
        }
    }

    @Test
    @DisplayName("Nessuna mossa puo' lasciare il Re sotto scacco")
    void noLegalMoveLeavesKingInCheck() {
        String[] fens = {
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
                "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
        };
        for (String fen : fens) {
            ChessBoard board = ChessBoard.fromFen(fen);
            for (Move m : board.getAllLegalMoves(board.getTurn())) {
                ChessBoard copy = board.copy();
                assertTrue(copy.makeMove(m));
                assertFalse(copy.isKingInCheck(board.getTurn()),
                        "Mossa " + m.toUci() + " lascia il Re in scacco nella posizione " + fen);
            }
        }
    }
}
