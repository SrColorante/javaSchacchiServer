package org.schacchi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.schacchi.model.ChessBoard;
import org.schacchi.server.ConnectionHandler;
import org.schacchi.server.GameSession;
import org.schacchi.server.Server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifica le patte automatiche FIDE applicate dal server.
 *
 * <p>Queste posizioni non sono raggiungibili via protocollo pubblico (nessun comando
 * permette di caricare un FEN a meta' partita), quindi la partita viene costruita
 * direttamente con {@link GameSession} passing da una posizione FEN iniziale.
 *
 * <p>Ogni giocatore e' un {@link ConnectionHandler} collegato a una vera coppia di
 * socket, cosi' da esercitare lo stesso codice di invio messaggi del server di produzione.
 * Le mosse vengono iniettate chiamando {@code processMove}, esattamente come fa
 * {@code ConnectionHandler.handleCommand} quando riceve un comando MOVE dal client.
 */
public class GameSessionDrawTest {

    private static final long TIMEOUT_MS = 5000;

    private ServerSocket pairListener;
    private Path accountsFile;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();

    /**
     * Un giocatore: handler lato server, piu' i messaggi ricevuti sul lato client.
     */
    private class Player {
        final ConnectionHandler handler;
        final List<String> received = new CopyOnWriteArrayList<>();

        Player() throws IOException {
            Socket clientSide = new Socket("127.0.0.1", pairListener.getLocalPort());
            Socket serverSide = pairListener.accept();
            clientSide.setSoTimeout(0);
            serverSide.setSoTimeout(0);
            sockets.add(clientSide);
            sockets.add(serverSide);

            handler = new ConnectionHandler(serverSide);
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSide.getInputStream()))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        received.add(line);
                    }
                } catch (IOException ignored) {
                    // socket chiuso: normale a fine test
                }
            }, "draw-test-client");
            reader.setDaemon(true);
            reader.start();

            // Avvia il ciclo di vita dell'handler lato server
            Thread serverThread = new Thread(handler, "draw-test-server");
            serverThread.setDaemon(true);
            serverThread.start();
        }

        String awaitMessage(String prefix) {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                for (String m : received) {
                    if (m.startsWith(prefix)) return m;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("Test interrotto");
                }
            }
            fail("Timeout in attesa di \"" + prefix + "\". Messaggi ricevuti: " + received);
            return null;
        }

        boolean sawMessageStartingWith(String prefix) {
            return received.stream().anyMatch(m -> m.startsWith(prefix));
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        accountsFile = Files.createTempFile("schacchi-draw-test", ".txt");
        Files.deleteIfExists(accountsFile);
        // Il server non viene avviato: serve solo lo stato globale che GameSession usa
        // per log e statistiche. Il file account temporaneo evita di sporcare il progetto.
        Server.installInstance(new Server(0, accountsFile.toString()));
        pairListener = new ServerSocket(0);
    }

    @AfterEach
    void tearDown() {
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        sockets.clear();
        try {
            pairListener.close();
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(accountsFile);
        } catch (IOException ignored) {
        }
    }

    private GameSession startSession(String id, String fen, Player white, Player black) {
        GameSession session = new GameSession(id, id, null, fen);
        session.addPlayer(white.handler); // Bianco
        session.addPlayer(black.handler); // Nero
        return session;
    }

    @Test
    @DisplayName("Materiale insufficiente: Re contro Re dichiara patta")
    void insufficientMaterialDraws() throws IOException {
        Player white = new Player();
        Player black = new Player();

        GameSession session = startSession("draw-insufficient",
                "4k3/8/8/8/8/8/8/4K3 w - - 0 1", white, black);

        // Una normale mossa di Re: il materiale resta insufficiente
        session.processMove(white.handler, "e1d1");

        String gameOver = white.awaitMessage("GAME_OVER");
        assertTrue(gameOver.contains("DRAW"), "Deve essere patta, ottenuto: " + gameOver);
        assertTrue(gameOver.contains("INSUFFICIENT_MATERIAL"),
                "Il motivo deve essere il materiale insufficiente, ottenuto: " + gameOver);
        assertEquals(GameSession.GameStatus.FINISHED, session.getStatus());
    }

    @Test
    @DisplayName("Re con un solo Cavallo: materiale insufficiente")
    void kingAndKnightIsInsufficientMaterial() throws IOException {
        Player white = new Player();
        Player black = new Player();

        GameSession session = startSession("draw-knight",
                "4k3/8/8/8/8/8/8/3NK3 w - - 0 1", white, black);

        session.processMove(white.handler, "d1c3");

        String gameOver = white.awaitMessage("GAME_OVER");
        assertTrue(gameOver.contains("INSUFFICIENT_MATERIAL"),
                "Re e Cavallo non possono dare scacco-matte: " + gameOver);
    }

    @Test
    @DisplayName("Ripetizione per tre volte dichiara patta")
    void threefoldRepetitionDraws() throws IOException {
        Player white = new Player();
        Player black = new Player();

        // Cavalli che tornano indietro senza muovere Re o Torri: la posizione
        // si ripete identica anche nei diritti di arrocco
        GameSession session = startSession("draw-repetition",
                "rn2k2r/8/8/8/8/8/8/RN2K2R w KQkq - 0 1", white, black);

        String[] cycle = {"b1c3", "b8c6", "c3b1", "c6b8"};
        for (int repetition = 0; repetition < 2; repetition++) {
            for (int i = 0; i < cycle.length; i++) {
                ConnectionHandler mover = (i % 2 == 0) ? white.handler : black.handler;
                session.processMove(mover, cycle[i]);
            }
        }

        String gameOver = white.awaitMessage("GAME_OVER");
        assertTrue(gameOver.contains("DRAW"), "Deve essere patta, ottenuto: " + gameOver);
        assertTrue(gameOver.contains("THREEFOLD_REPETITION"),
                "Il motivo deve essere la ripetizione, ottenuto: " + gameOver);
    }

    @Test
    @DisplayName("Regola delle 50 mosse dichiara patta")
    void fiftyMoveRuleDraws() throws IOException {
        Player white = new Player();
        Player black = new Player();

        // halfmoveClock a 99: una sola mossa lo porta a 100.
        // Serve materiale sufficiente (una Torre), altrimenti scatterebbe prima
        // la regola del materiale insufficiente.
        GameSession session = startSession("draw-fifty",
                "4k3/8/8/8/8/8/8/R3K3 w - - 99 80", white, black);

        session.processMove(white.handler, "e1d1");

        String gameOver = white.awaitMessage("GAME_OVER");
        assertTrue(gameOver.contains("DRAW"), "Deve essere patta, ottenuto: " + gameOver);
        assertTrue(gameOver.contains("FIFTY_MOVE_RULE"),
                "Il motivo deve essere la regola delle 50 mosse, ottenuto: " + gameOver);
    }

    @Test
    @DisplayName("Lo scacco matto ha la precedenza sulla regola delle 50 mosse")
    void checkmateBeatsFiftyMoveRule() throws IOException {
        Player white = new Player();
        Player black = new Player();

        // halfmoveClock a 99 e la mossa del Nero da' scacco matto.
        // Secondo l'art. 9.6.2 FIDE la partita e' vinta, non patta: il controllo
        // dello scacco matto deve quindi precedere quello delle patte automatiche.
        // Re Bianco in h8, intrappolato: Ra1-a8# copre g8 con la fila, h7 con la
        // Torre h1 e g7 con l'Alfiere f6. Il Bianco non puo' ne catturare ne bloccare.
        GameSession session = startSession("draw-mate-precedence",
                "7K/8/5b2/8/4k3/8/8/r6r b - - 99 80", white, black);

        session.processMove(black.handler, "a1a8"); // mossa del Nero: halfmoveClock -> 100 E scacco matto

        String gameOver = white.awaitMessage("GAME_OVER");
        assertTrue(gameOver.contains("BLACK_WON CHECKMATE"),
                "Lo scacco matto deve prevalere sulla regola delle 50 mosse, ottenuto: " + gameOver);
        assertFalse(gameOver.contains("FIFTY_MOVE_RULE"),
                "Non deve essere dichiarata patta per 50 mosse quando c'e' scacco matto: " + gameOver);
    }

    @Test
    @DisplayName("Lo stallo viene rilevato dopo una mossa")
    void stalemateIsDetectedAfterMove() throws IOException {
        Player white = new Player();
        Player black = new Player();

        // Re Nero in a8. Dopo Qd4-b6: a7 e b8 sono controllati (Regina e Re Bianco in c7),
        // a8 non e' in scacco e il Nero non ha mosse => stallo.
        GameSession session = startSession("draw-stalemate",
                "k7/2K5/8/8/3Q4/8/8/8 w - - 5 40", white, black);

        session.processMove(white.handler, "d4b6");

        String gameOver = white.awaitMessage("GAME_OVER");
        assertTrue(gameOver.contains("DRAW STALEMATE"),
                "Deve essere patta per stallo, ottenuto: " + gameOver);
        assertEquals(GameSession.GameStatus.FINISHED, session.getStatus());
    }

    @Test
    @DisplayName("FEN iniziale non valido: si riparte dalla disposizione iniziale")
    void invalidInitialFenFallsBackToStartPosition() {
        GameSession session = new GameSession("draw-badfen", "FEN rotto", null, "non-e-un-fen");
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                session.getBoard().toFen(),
                "Con un FEN non valido si deve ripartire dalla disposizione iniziale");
        assertEquals(GameSession.GameStatus.WAITING_FOR_OPPONENT, session.getStatus());
    }

    @Test
    @DisplayName("FEN iniziale valido: la partita parte da quella posizione")
    void validInitialFenIsUsed() {
        GameSession session = new GameSession("draw-goodfen", "Posizione custom", null,
                "8/8/8/8/8/5k2/6P1/6K1 w - - 0 1");
        assertEquals("8/8/8/8/8/5k2/6P1/6K1 w - - 0 1", session.getBoard().toFen());
        assertEquals(1, ChessBoard.fromFen(session.getBoard().toFen()).getRepetitionCount(),
                "La posizione caricata è la prima occorrenza");
    }
}
