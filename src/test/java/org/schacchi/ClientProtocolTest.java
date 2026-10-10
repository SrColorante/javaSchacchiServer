package org.schacchi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.schacchi.client.ClientListener;
import org.schacchi.client.ClientNetwork;
import org.schacchi.server.Server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test dellato client contro il server reale.
 *
 * Gli altri test di integrazione parlano con socket grezze, quindi verificano
 * il server. Questi usano {@link ClientNetwork} e un {@link ClientListener}, cioe'
 * il percorso che un utente Swing percorre davvero: se il server manda un
 * messaggio che il client non interpreta, qui si vede e nelle socket no.
 *
 * Nessun display richiesto: Swing non viene toccato, solo il parser dei messaggi
 * e il thread di lettura.
 */
public class ClientProtocolTest {

    private static final long TIMEOUT_MS = 5000;

    private Server server;
    private Path accountsFile;
    private final List<ClientNetwork> clients = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        accountsFile = Files.createTempFile("schacchi-client-accounts", ".txt");
        Files.deleteIfExists(accountsFile);
        server = Server.installInstance(new Server(0, accountsFile.toString()));
        server.startAsync();

        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (server.getPort() == 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Test interrotto", e);
            }
        }
        assertTrue(server.isRunning(), "Il server non si e' avviato");
    }

    @AfterEach
    void stopServer() {
        for (ClientNetwork client : clients) {
            client.disconnect();
        }
        clients.clear();
        if (server != null) {
            server.stop();
        }
        try {
            Files.deleteIfExists(accountsFile);
        } catch (IOException ignored) {
        }
    }

    /** Registra gli eventi che interessano e si mette in attesa. */
    private static final class Recorder implements ClientListener {
        final CountDownLatch spectating = new CountDownLatch(1);
        final CountDownLatch anyMove = new CountDownLatch(1);
        final CountDownLatch opponentDisconnected = new CountDownLatch(1);
        final CopyOnWriteArrayList<String> moves = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<String> fens = new CopyOnWriteArrayList<>();
        String spectatingFen = null;

        @Override
        public void onSpectating(String roomId, String fen) {
            spectatingFen = fen;
            spectating.countDown();
        }

        @Override
        public void onMoveExecuted(String uciMove, String fen, boolean isMine) {
            moves.add(isMine ? "mine:" + uciMove : "other:" + uciMove);
            if (fen != null) fens.add(fen);
            anyMove.countDown();
        }

        @Override
        public void onOpponentDisconnected() {
            opponentDisconnected.countDown();
        }

        boolean awaitSpectating() throws InterruptedException {
            return spectating.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        boolean awaitAnyMove() throws InterruptedException {
            return anyMove.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        boolean awaitOpponentDisconnected() throws InterruptedException {
            return opponentDisconnected.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    @Test
    @DisplayName("Lo spettatore vede le mosse che vengono giocate dopo il suo ingresso")
    void spectatorSeesMovesAfterJoining() throws Exception {
        // Il server chiude solo al QUIT: con ClientNetwork si entra con NAME e basta.
        ClientNetwork white = newClient();
        ClientNetwork black = newClient();
        ClientNetwork spectator = newClient();

        CountDownLatch roomCreated = new CountDownLatch(1);
        final String[] roomId = new String[1];
        white.addListener(new ClientListener() {
            @Override
            public void onRoomCreated(String id, String color, String roomName) {
                roomId[0] = id;
                roomCreated.countDown();
            }
        });
        white.createRoom("Sala Spettatori");
        assertTrue(roomCreated.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "la stanza non e' stata creata");

        black.joinRoom(roomId[0]);

        Recorder rec = new Recorder();
        spectator.addListener(rec);
        // La stanza è già piena: il server rende questo client spettatore.
        spectator.joinRoom(roomId[0]);
        assertTrue(rec.awaitSpectating(), "il server non ha messo il client in modalita' spettatore");
        assertNotNull(rec.spectatingFen, "il FEN iniziale dello spettatore manca");

        // Una mossa giocata DOPO l'ingresso dello spettatore: deve arrivargli.
        white.sendMove("e2e4");
        assertTrue(rec.awaitAnyMove(),
                "Lo spettatore non riceve alcun aggiornamento: il server manda 'MOVE <uci> <fen>'"
                        + " e il client non lo interpreta. Mosse viste: " + rec.moves);
        assertTrue(rec.moves.stream().anyMatch(m -> m.contains("e2e4")),
                "la mossa e2e4 non e' arrivata allo spettatore, viste: " + rec.moves);
        assertTrue(rec.fens.stream().anyMatch(f -> f.contains("P") && !f.contains("PPPPPPP")),
                "il FEN aggiornato non e' arrivato allo spettatore, visti: " + rec.fens);
    }

    @Test
    @DisplayName("Il client resta in ascolto anche quando arriva un messaggio che non conosce")
    void unknownMessageDoesNotKillTheReader() throws Exception {
        ClientNetwork white = newClient();
        ClientNetwork black = newClient();

        CountDownLatch roomCreated = new CountDownLatch(1);
        final String[] roomId = new String[1];
        white.addListener(new ClientListener() {
            @Override
            public void onRoomCreated(String id, String color, String roomName) {
                roomId[0] = id;
                roomCreated.countDown();
            }
        });
        white.createRoom("Sala Messaggi");
        assertTrue(roomCreated.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "la stanza non e' stata creata");
        black.joinRoom(roomId[0]);

        // MOVE_OK arriva e viene interpretato: se un messaggio sconosciuto
        // precedente avesse ucciso il thread lettore, questo non si vedrebbe.
        CountDownLatch moved = new CountDownLatch(1);
        white.addListener(new ClientListener() {
            @Override
            public void onMoveExecuted(String uciMove, String fen, boolean isMine) {
                if (isMine) moved.countDown();
            }
        });
        white.sendMove("d2d4");
        assertTrue(moved.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                "il client non ha ricevuto MOVE_OK: il thread di lettura si e' interrotto");
    }

    @Test
    @DisplayName("La caduta del socket dell'avversario viene segnalata al client")
    void opponentDisconnectIsReported() throws Exception {
        ClientNetwork white = newClient();
        ClientNetwork black = newClient();

        CountDownLatch roomCreated = new CountDownLatch(1);
        final String[] roomId = new String[1];
        white.addListener(new ClientListener() {
            @Override
            public void onRoomCreated(String id, String color, String roomName) {
                roomId[0] = id;
                roomCreated.countDown();
            }
        });
        white.createRoom("Sala Caduta");
        assertTrue(roomCreated.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "la stanza non e' stata creata");
        black.joinRoom(roomId[0]);

        Recorder rec = new Recorder();
        white.addListener(rec);

        // Il Bianco chiude la socket a partita in corso: il server manda
        // OPPONENT_DISCONNECTED al Nero prima del GAME_OVER.
        black.disconnect();
        assertTrue(rec.awaitOpponentDisconnected(),
                "Il client non viene avvisato che l'avversario si e' disconnesso");
    }

    private ClientNetwork newClient() throws InterruptedException {        ClientNetwork net = new ClientNetwork();
        clients.add(net);
        CountDownLatch connected = new CountDownLatch(1);
        net.addListener(new ClientListener() {
            @Override
            public void onConnected() {
                connected.countDown();
            }
        });
        net.connect("127.0.0.1", server.getPort());
        assertTrue(connected.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "il client non si e' connesso");
        return net;
    }
}
