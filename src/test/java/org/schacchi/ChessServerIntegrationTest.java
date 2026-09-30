package org.schacchi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.schacchi.server.Server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test di integrazione end-to-end: avvia un server reale su una porta libera e
 * gioca partite vere attraverso socket TCP, verificando il protocollo e le regole.
 *
 * <p>Il server gira in modalita' headless: Swing non viene toccato, quindi i test
 * sono eseguibili anche su macchina senza display.
 */
public class ChessServerIntegrationTest {

    private static final long TIMEOUT_MS = 5000;

    private Server server;
    private Path accountsFile;
    private final List<TestClient> clients = new ArrayList<>();

    /**
     * Password sufficientemente lunga per la registrazione (minimo 8 caratteri).
     * I test precedenti usavano "password123", che ora è troppo corta.
     */
    private static final String PW = "password123";

    @Test
    @DisplayName("Diritto di accesso: EXPORT_DATA restituisce i dati personali")
    void exportDataReturnsPersonalData() throws IOException {
        TestClient client = connect();
        client.await("CONNECTED");
        client.send("REGISTER Esporta " + PW + " 30");
        assertNotNull(client.await("REGISTER_OK Esporta"));

        client.send("EXPORT_DATA");
        String usernameLine = client.await("EXPORT_LINE username:");
        assertTrue(usernameLine.contains("Esporta"), "Deve comparire lo username, ottenuto: " + usernameLine);
        client.await("EXPORT_LINE elo:");
        client.await("EXPORT_LINE amici:");
        String ageLine = client.await("EXPORT_LINE eta_dichiarata:");
        assertTrue(ageLine.contains("30"), "Deve comparire l'eta' dichiarata, ottenuto: " + ageLine);
        assertTrue(client.sawMessageStartingWith("EXPORT_END"), "L'export deve terminare con EXPORT_END");

        // La traccia della password non deve mai essere mostrata
        assertFalse(String.join("\n", client.messages()).contains("passwordHash"),
                "L'esportazione non deve contenere la traccia della password");
    }

    @Test
    @DisplayName("Diritto di cancellazione: DELETE_ACCOUNT rimuove l'account e le amicizie")
    void deleteAccountRemovesData() throws IOException {
        TestClient alice = connect();
        TestClient bob = connect();
        alice.await("CONNECTED");
        bob.await("CONNECTED");

        alice.send("REGISTER AliceDel " + PW);
        alice.await("REGISTER_OK AliceDel");
        bob.send("REGISTER BobDel " + PW);
        bob.await("REGISTER_OK BobDel");

        // Diventano amici
        alice.send("FRIEND_ADD BobDel");
        alice.await("FRIEND_ADDED BobDel");
        // Le amicizie devono essere reciproche: await() attende e fallisce da sé
        bob.send("FRIENDS");
        bob.await("FRIEND AliceDel");

        // Cancellazione con password sbagliata: rifiutata
        alice.send("DELETE_ACCOUNT wrongpass");
        alice.awaitErrorContaining("Password non corretta");

        // Cancellazione con password corretta
        alice.send("DELETE_ACCOUNT " + PW);
        String deleted = alice.await("DELETE_OK");
        assertTrue(deleted.contains("AliceDel"), "Il messaggio deve confermare la cancellazione: " + deleted);

        // Non si può più fare login
        TestClient fresh = connect();
        fresh.await("CONNECTED");
        fresh.send("LOGIN AliceDel " + PW);
        fresh.awaitErrorContaining("Credenziali non valide");

        // Il nome non deve più comparire nella lista amici di Bob. Bob aveva già
        // chiesto FRIENDS quando Alice esisteva ancora, quindi il numero di
        // risposte va contato DOPO l'invio e deve crescere: altrimenti si leggerebbe
        // la lista vecchia, che conteneva ancora il nome cancellato.
        int before = bob.countMessages("FRIENDS_LIST");
        bob.send("FRIENDS");
        bob.awaitCount("FRIENDS_LIST", before + 1);
        assertEquals("FRIENDS_LIST 0", bob.nthMessage("FRIENDS_LIST", before + 1),
                "Dopo la cancellazione la lista amici di Bob deve essere vuota");
    }

    @Test
    @DisplayName("Cancellazione: il riferimento residuo sparisce anche a nomeDiverso")
    void deleteRemovesResidualFriendReference() {
        // Regressione: la rimozione confrontava i nomi con equals() esatto, ma gli
        // account sono indicizzati per chiave minuscola mentre le liste amici
        // conservano le maiuscole originali. Con nomi di maiuscole diverse il
        // riferimento residuo sopravviveva alla cancellazione.
        org.schacchi.server.AccountManager manager =
                new org.schacchi.server.AccountManager(null, 1000);

        manager.register("MarioRossi", "password123");
        manager.register("LuigiVerdi", "password123");
        assertTrue(manager.addFriend("MarioRossi", "LuigiVerdi"), "L'amicizia deve essere creata");

        assertTrue(manager.deleteAccount("LuigiVerdi"));
        assertFalse(manager.hasFriend("MarioRossi", "LuigiVerdi"),
                "Il riferimento all'utente cancellato deve sparire dalla lista amici altrui");
    }

    @Test
    @DisplayName("La registrazione sotto i 13 anni è rifiutata")
    void registrationUnder13IsRejected() throws IOException {
        TestClient client = connect();
        client.await("CONNECTED");
        client.send("REGISTER Bambino " + PW + " 10");
        client.awaitErrorContaining("almeno 13 anni");
    }

    @Test
    @DisplayName("Tra 13 e 15 anni l'avviso sul consenso genitoriale viene inviato")
    void minorRegistrationGetsParentalConsentNotice() throws IOException {
        TestClient client = connect();
        client.await("CONNECTED");
        client.send("REGISTER Ragazzo " + PW + " 14");
        String ok = client.await("REGISTER_OK Ragazzo");
        assertTrue(ok.contains("genitore"),
                "Sotto i 16 anni deve essere avvisato del consenso genitoriale, ottenuto: " + ok);
    }

    @Test
    @DisplayName("Da 16 anni nessun avviso sul consenso genitoriale")
    void adultRegistrationHasNoParentalNotice() throws IOException {
        TestClient client = connect();
        client.await("CONNECTED");
        client.send("REGISTER Adulto " + PW + " 20");
        String ok = client.await("REGISTER_OK Adulto");
        assertFalse(ok.contains("genitore"),
                "Un adulto non deve ricevere l'avviso sui minori, ottenuto: " + ok);
    }

    @Test
    @DisplayName("Il file account non contiene la password in chiaro né tracce deboli")
    void accountsFileDoesNotStorePlaintextPasswords() throws IOException {
        TestClient client = connect();
        client.await("CONNECTED");
        client.send("REGISTER sicuro " + PW);
        assertNotNull(client.await("REGISTER_OK sicuro"));

        String content = Files.readString(accountsFile);
        assertFalse(content.contains(PW), "La password non deve mai essere scritta in chiaro su disco");

        // Formato: username:salt:iterazioni:hash:w:l:d:elo:eta:amici
        // split con limite -1: altrimenti il campo "amici" vuoto verrebbe perso
        String[] fields = content.trim().split(":", -1);
        assertEquals(10, fields.length, "Formato della riga account non atteso: " + content.trim());
        assertEquals("sicuro", fields[0]);
        assertEquals(32, fields[1].length(), "Il sale deve essere di 16 byte in esadecimale");

        int iterations = Integer.parseInt(fields[2]);
        assertTrue(iterations >= 100_000,
                "Il fattore di lavoro PBKDF2 deve essere alto, ottenuto: " + iterations);
        assertEquals(64, fields[3].length(), "La traccia PBKDF2-SHA256 deve essere di 32 byte in esadecimale");
    }
    /**
     * Client di test minimale: invia righe di comando e raccoglie le risposte.
     */
    private static class TestClient implements AutoCloseable {
        private final Socket socket;
        private final PrintWriter out;
        private final BufferedReader in;
        private final List<String> received = new CopyOnWriteArrayList<>();

        TestClient(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            // Timeout di lettura disabilitato: un SocketTimeoutException ucciderebbe il
            // thread lettore e il client perderebbe i messaggi successivi. E' la await()
            // ad avere il timeout, lato test.
            socket.setSoTimeout(0);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            // Thread dedicato: il protocollo e' asincrono, quindi i messaggi vanno
            // accumulati continuamente per non perdere nulla tra un'asserzione e l'altra.
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        received.add(line);
                    }
                } catch (IOException ignored) {
                    // connessione chiusa: normale a fine test
                }
            }, "test-client-reader");
            reader.setDaemon(true);
            reader.start();
        }

        void send(String command) {
            out.println(command);
        }

        List<String> messages() {
            return new ArrayList<>(received);
        }

        /**
         * Attende che il server invii un messaggio che inizia con il prefisso dato.
         *
         * @return il messaggio completo
         */
        String await(String prefix) {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                for (String msg : received) {
                    if (msg.startsWith(prefix)) return msg;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("Test interrotto mentre si attendeva: " + prefix);
                }
            }
            fail("Timeout (attesi " + TIMEOUT_MS + "ms) in attesa di un messaggio che iniziasse con \""
                    + prefix + "\". Messaggi ricevuti: " + received);
            return null;
        }

        /** Attende che arrivi un ERROR contenente il testo indicato. */
        void awaitErrorContaining(String text) {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (sawErrorContaining(text)) return;
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("Test interrotto mentre si attendeva un ERROR contenente: " + text);
                }
            }
            fail("Timeout in attesa di un ERROR contenente \"" + text + "\". Messaggi ricevuti: " + received);
        }

        boolean sawMessageStartingWith(String prefix) {
            return received.stream().anyMatch(m -> m.startsWith(prefix));
        }

        /** @return quanti messaggi iniziati con il prefisso sono arrivati finora */
        int countMessages(String prefix) {
            return (int) received.stream().filter(m -> m.startsWith(prefix)).count();
        }

        /** Attende che i messaggi col prefisso siano almeno {@code atLeast}. */
        void awaitCount(String prefix, int atLeast) {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (countMessages(prefix) >= atLeast) return;
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("Test interrotto");
                }
            }
            fail("Timeout in attesa di almeno " + atLeast + " messaggi \"" + prefix
                    + "\", arrivati " + countMessages(prefix) + ". Ricevuti: " + received);
        }

        /** @return l'ennesimo messaggio che inizia con il prefisso (1-based) */
        String nthMessage(String prefix, int n) {
            List<String> matching = received.stream().filter(m -> m.startsWith(prefix)).toList();
            if (matching.size() < n) {
                fail("Attesi " + n + " messaggi \"" + prefix + "\", trovati " + matching.size()
                        + ". Ricevuti: " + received);
            }
            return matching.get(n - 1);
        }

        boolean sawErrorContaining(String text) {
            return received.stream().anyMatch(m -> m.startsWith("ERROR") && m.contains(text));
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    @BeforeEach
    void startServer() throws IOException {
        // accountsFile temporaneo: i test non devono scrivere sull'accounts.txt di produzione.
        accountsFile = Files.createTempFile("schacchi-test-accounts", ".txt");
        Files.deleteIfExists(accountsFile);

        // Porta 0 = il sistema ne assegna una libera, evitando conflitti in CI.
        server = Server.installInstance(new Server(0, accountsFile.toString()));
        server.startAsync();

        // Attende che il ServerSocket sia effettivamente in ascolto.
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (server.getPort() == 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Test interrotto durante l'avvio del server");
            }
        }
        assertTrue(server.isRunning(), "Il server non si e' avviato");
    }

    @AfterEach
    void stopServer() {
        for (TestClient client : clients) {
            client.close();
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

    private TestClient connect() throws IOException {
        TestClient client = new TestClient(server.getPort());
        clients.add(client);
        return client;
    }

    @Test
    @DisplayName("Benvenuto e PING/PONG")
    void welcomeAndPing() throws IOException {
        TestClient client = connect();
        assertTrue(client.await("CONNECTED").contains("Welcome to Chess Server"));
        assertNotNull(client.await("YOUR_NAME"));

        client.send("PING");
        assertEquals("PONG", client.await("PONG"));
    }

    @Test
    @DisplayName("Partita completa fino a scacco matto (matto del deficient)")
    void fullGameToCheckmate() throws IOException {
        TestClient white = connect();
        TestClient black = connect();
        white.await("CONNECTED");
        black.await("CONNECTED");

        white.send("NAME Alice");
        black.send("NAME Bob");
        white.await("NAME_CHANGED Alice");
        black.await("NAME_CHANGED Bob");

        white.send("CREATE Partita Test");
        String roomCreated = white.await("ROOM_CREATED");
        String roomId = roomCreated.split("\\s+")[1];
        assertTrue(roomCreated.contains("WHITE"), "L'host deve giocare con il Bianco");

        black.send("JOIN " + roomId);
        assertTrue(white.await("GAME_START").contains("WHITE Bob"),
                "Il Bianco deve ricevere GAME_START con il nome dell'avversario");
        assertTrue(black.await("GAME_START").contains("BLACK Alice"),
                "Il Nero deve ricevere GAME_START con il nome dell'avversario");

        // 1. f3 e5  2. g4 Qh4#  -> scacco matto
        white.send("MOVE f2f3");
        assertTrue(white.await("MOVE_OK f2f3").contains("MOVE_OK"));
        assertTrue(black.await("OPPONENT_MOVE f2f3").contains("f2f3"));

        black.send("MOVE e7e5");
        black.await("MOVE_OK e7e5");
        white.await("OPPONENT_MOVE e7e5");

        white.send("MOVE g2g4");
        white.await("MOVE_OK g2g4");
        black.await("OPPONENT_MOVE g2g4");

        black.send("MOVE d8h4");
        String moveOk = black.await("MOVE_OK d8h4");

        // Il FEN occupa tutti i campi successivi alla mossa.
        // Dopo una mossa del Nero tocca al Bianco e il contatore mosse e' avanzato.
        String[] moveTokens = moveOk.split("\\s+");
        String fen = String.join(" ", java.util.Arrays.copyOfRange(moveTokens, 2, moveTokens.length));
        assertTrue(fen.startsWith("rnb1kbnr"), "FEN atteso con la Donna in h4, ottenuto: " + fen);
        assertTrue(fen.contains(" w "), "Dopo la mossa del Nero tocca al Bianco: " + fen);
        assertEquals("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3", fen);

        String gameOver = white.await("GAME_OVER");
        // Nel matto del deficient il Nero dà matto: è lui il vincitore
        assertTrue(gameOver.contains("BLACK_WON"), "Deve vincere il Nero, ottenuto: " + gameOver);
        assertTrue(gameOver.contains("CHECKMATE"), "Il motivo deve essere CHECKMATE: " + gameOver);
    }

    @Test
    @DisplayName("Il server rifiuta mossa illegale e mossa fuori turno")
    void illegalMoveAndWrongTurnAreRejected() throws IOException {
        TestClient white = connect();
        TestClient black = connect();
        white.await("CONNECTED");
        black.await("CONNECTED");

        white.send("CREATE Sala");
        String roomId = white.await("ROOM_CREATED").split("\\s+")[1];
        black.send("JOIN " + roomId);
        white.await("GAME_START");
        black.await("GAME_START");

        // Mossa illegale: il pedone non può fare tre caselle
        white.send("MOVE e2e5");
        assertTrue(white.await("ERROR").contains("Mossa illegale"),
                "Il server deve rifiutare una mossa illegale");

        // Mossa fuori turno: tocca al Bianco, il Nero prova a muovere
        black.send("MOVE e7e5");
        assertTrue(black.await("ERROR").contains("Non e' il tuo turno"),
                "Il server deve rifiutare una mossa fuori turno");

        // Formato non valido
        white.send("MOVE xyz");
        assertTrue(white.sawMessageStartingWith("ERROR"), "Il formato non valido deve generare un ERROR");

        // La partita prosegue normalmente dopo i rifiuti
        white.send("MOVE e2e4");
        assertTrue(white.await("MOVE_OK e2e4").startsWith("MOVE_OK e2e4"));
    }

    @Test
    @DisplayName("Resa: l'avversario vince e le statistiche vengono aggiornate")
    void resignationUpdatesStats() throws IOException {
        TestClient white = connect();
        TestClient black = connect();
        white.await("CONNECTED");
        black.await("CONNECTED");

        white.send("NAME Resigner");
        black.send("NAME Winner");
        white.await("NAME_CHANGED Resigner");
        black.await("NAME_CHANGED Winner");

        // Registrazione di entrambi gli account
        white.send("REGISTER Resigner password123");
        assertNotNull(white.await("REGISTER_OK Resigner"));
        black.send("REGISTER Winner password123");
        assertNotNull(black.await("REGISTER_OK Winner"));

        white.send("CREATE Sala");
        String roomId = white.await("ROOM_CREATED").split("\\s+")[1];
        black.send("JOIN " + roomId);
        white.await("GAME_START");
        black.await("GAME_START");

        white.send("MOVE e2e4");
        white.await("MOVE_OK e2e4");
        black.await("OPPONENT_MOVE e2e4");

        white.send("RESIGN");
        String gameOver = black.await("GAME_OVER");
        assertTrue(gameOver.contains("BLACK_WON"), "Il Nero deve vincere: " + gameOver);
        assertTrue(gameOver.contains("RESIGNATION"), "Il motivo deve essere RESIGNATION: " + gameOver);

        // Questo è il bug corretto: prima la resa non registrava nulla
        black.send("STATS");
        String stats = black.await("STATS Winner");
        assertTrue(stats.contains("W:1"), "La vittoria per resa deve essere registrata, ottenuto: " + stats);
        assertTrue(stats.contains("ELO:1215"), "L'ELO deve essere salito di 15, ottenuto: " + stats);

        white.send("STATS");
        String loserStats = white.await("STATS Resigner");
        assertTrue(loserStats.contains("L:1"), "La sconfitta per resa deve essere registrata, ottenuto: " + loserStats);
    }

    @Test
    @DisplayName("Abbandono: chi resta vince per forfeit e le statistiche si aggiornano")
    void disconnectForfeitsAndRecordsResult() throws IOException {
        TestClient white = connect();
        TestClient black = connect();
        white.await("CONNECTED");
        black.await("CONNECTED");

        white.send("NAME quitter");
        black.send("NAME rester");
        white.await("NAME_CHANGED quitter");
        black.await("NAME_CHANGED rester");

        black.send("REGISTER rester password123");
        assertNotNull(black.await("REGISTER_OK rester"));

        white.send("CREATE Sala");
        String roomId = white.await("ROOM_CREATED").split("\\s+")[1];
        black.send("JOIN " + roomId);
        white.await("GAME_START");
        black.await("GAME_START");

        // Il Bianco abbandona: il Nero deve vincere per forfeit
        white.send("QUIT");

        String gameOver = black.await("GAME_OVER");
        assertTrue(gameOver.contains("BLACK_WON"), "Il Nero deve vincere per forfeit: " + gameOver);
        assertTrue(gameOver.contains("FORFEIT"), "Il motivo deve essere FORFEIT: " + gameOver);
        assertTrue(black.sawMessageStartingWith("OPPONENT_DISCONNECTED"),
                "Il giocatore rimasto deve essere avvisato della disconnessione");

        black.send("STATS");
        assertTrue(black.await("STATS rester").contains("W:1"),
                "La vittoria per abbandono deve essere registrata");
    }

    @Test
    @DisplayName("Patta concordata da proposta e accettazione")
    void drawOfferAndAccept() throws IOException {
        TestClient white = connect();
        TestClient black = connect();
        white.await("CONNECTED");
        black.await("CONNECTED");

        white.send("CREATE Sala");
        String roomId = white.await("ROOM_CREATED").split("\\s+")[1];
        black.send("JOIN " + roomId);
        white.await("GAME_START");
        black.await("GAME_START");

        white.send("MOVE e2e4");
        white.await("MOVE_OK e2e4");
        black.await("OPPONENT_MOVE e2e4");

        white.send("DRAW_OFFER");
        assertNotNull(black.await("DRAW_OFFER"), "L'avversario deve ricevere l'offerta di patta");

        // Chi ha proposto non può accettare la propria offerta
        white.send("DRAW_ACCEPT");
        assertTrue(white.await("ERROR").contains("proposto tu la patta"),
                "Non si puo' accettare la propria offerta di patta");

        black.send("DRAW_ACCEPT");
        String gameOver = white.await("GAME_OVER");
        assertTrue(gameOver.contains("DRAW"), "Il risultato deve essere patta: " + gameOver);
        assertTrue(gameOver.contains("AGREEMENT"), "Il motivo deve essere AGREEMENT: " + gameOver);
    }

    @Test
    @DisplayName("Rifiuto della patta")
    void drawDecline() throws IOException {
        TestClient white = connect();
        TestClient black = connect();
        white.await("CONNECTED");
        black.await("CONNECTED");

        white.send("CREATE Sala");
        String roomId = white.await("ROOM_CREATED").split("\\s+")[1];
        black.send("JOIN " + roomId);
        white.await("GAME_START");
        black.await("GAME_START");

        white.send("DRAW_OFFER");
        black.await("DRAW_OFFER");
        black.send("DRAW_DECLINE");
        assertNotNull(white.await("DRAW_DECLINED"), "Chi ha proposto deve essere avvisato del rifiuto");

        // Dopo il rifiuto la partita continua
        white.send("MOVE e2e4");
        assertTrue(white.await("MOVE_OK e2e4").startsWith("MOVE_OK"));
    }

    @Test
    @DisplayName("Stallo: partita patta per mancanza di mosse")
    void stalemateEndsAsDraw() throws IOException {
        TestClient white = connect();
        TestClient black = connect();
        white.await("CONNECTED");
        black.await("CONNECTED");

        white.send("CREATE Sala");
        String roomId = white.await("ROOM_CREATED").split("\\s+")[1];
        black.send("JOIN " + roomId);
        white.await("GAME_START");
        black.await("GAME_START");

        // Sequenza nota che porta allo stalemate:
        // 1. e3 a5 2. Qh5 Ra6 3. Qxa5 h5 4. Qxc7 Rah6 5. h4 f6 6. Qxd7+ Kf7
        // 7. Qxb7 Qd3 8. Qxb8 Qh7 9. Qxc8 Kg6 10. Qe6 -> stalemate
        String[] moves = {
                "e2e3", "a7a5",
                "d1h5", "a8a6",
                "h5a5", "h7h5",
                "a5c7", "a6h6",
                "h2h4", "f7f6",
                "c7d7", "e8f7",
                "d7b7", "d8d3",
                "b7b8", "d3h7",
                "b8c8", "f7g6",
                "c8e6"
        };
        for (int i = 0; i < moves.length; i++) {
            TestClient player = (i % 2 == 0) ? white : black;
            player.send("MOVE " + moves[i]);
            TestClient other = (i % 2 == 0) ? black : white;
            if (i < moves.length - 1) {
                player.await("MOVE_OK " + moves[i]);
                other.await("OPPONENT_MOVE " + moves[i]);
            }
        }

        String gameOver = white.await("GAME_OVER");
        assertTrue(gameOver.contains("DRAW"), "Deve essere patta: " + gameOver);
        assertTrue(gameOver.contains("STALEMATE"), "Il motivo deve essere STALEMATE: " + gameOver);
    }

    @Test
    @DisplayName("Comandi di matchmaking e elenco stanze")
    void roomListingAndQuickMatch() throws IOException {
        TestClient host = connect();
        TestClient guest = connect();
        host.await("CONNECTED");
        guest.await("CONNECTED");

        host.send("CREATE Stanza Visibile");
        host.await("ROOM_CREATED");

        // Il secondo client vede la stanza nell'elenco
        guest.send("LIST");
        String listHeader = guest.await("ROOM_LIST");
        assertTrue(listHeader.startsWith("ROOM_LIST 1"), "Attesa una stanza aperta, ottenuto: " + listHeader);
        String roomLine = guest.await("ROOM ");
        assertTrue(roomLine.contains("HOST:Player_"), "La stanza deve mostrare l'host, ottenuto: " + roomLine);

        // Matchmaking: il primo client entra in coda, il secondo viene abbinato
        host.send("LEAVE");
        TestClient q1 = connect();
        TestClient q2 = connect();
        q1.await("CONNECTED");
        q2.await("CONNECTED");

        q1.send("PLAY");
        assertNotNull(q1.await("INFO In attesa"), "Il primo client deve entrare in coda");

        q2.send("PLAY");
        assertTrue(q1.await("GAME_START").contains("WHITE"), "Il primo in coda deve ricevere il Bianco");
        assertTrue(q2.await("GAME_START").contains("BLACK"), "Il secondo deve ricevere il Nero");
    }

    @Test
    @DisplayName("Il terzo client su stanza piena diventa spettatore")
    void thirdClientBecomesSpectator() throws IOException {
        TestClient white = connect();
        TestClient black = connect();
        TestClient third = connect();
        white.await("CONNECTED");
        black.await("CONNECTED");
        third.await("CONNECTED");

        white.send("CREATE Sala Piena");
        String roomId = white.await("ROOM_CREATED").split("\\s+")[1];
        black.send("JOIN " + roomId);
        white.await("GAME_START");
        black.await("GAME_START");

        white.send("MOVE e2e4");
        white.await("MOVE_OK e2e4");
        black.await("OPPONENT_MOVE e2e4");

        // Il terzo client entra in una stanza già iniziata: il server lo rende spettatore
        third.send("JOIN " + roomId);
        String spectating = third.await("SPECTATING " + roomId);
        assertTrue(spectating.contains("SPECTATING"), "Il terzo client deve diventare spettatore");

        // Lo spettatore riceve le mosse successive
        black.send("MOVE e7e5");
        black.await("MOVE_OK e7e5");
        assertNotNull(third.await("MOVE e7e5"), "Lo spettatore deve ricevere le mosse");

        // Uscire da una partita come spettatore NON deve concluderla
        third.send("LEAVE");
        third.await("INFO Hai lasciato");

        white.send("MOVE g1f3");
        assertTrue(white.await("MOVE_OK g1f3").startsWith("MOVE_OK"),
                "La partita deve proseguire dopo l'uscita dello spettatore");
    }

    @Test
    @DisplayName("Chat e BOARD riportano il FEN corretto")
    void chatAndBoardCommand() throws IOException {
        TestClient white = connect();
        TestClient black = connect();
        white.await("CONNECTED");
        black.await("CONNECTED");

        white.send("CREATE Sala");
        String roomId = white.await("ROOM_CREATED").split("\\s+")[1];
        black.send("JOIN " + roomId);
        white.await("GAME_START");
        black.await("GAME_START");

        white.send("MOVE e2e4");
        white.await("MOVE_OK e2e4");
        black.await("OPPONENT_MOVE e2e4");

        // BOARD deve rispondere con la posizione autorevole
        white.send("BOARD");
        String fenMessage = white.await("FEN ");
        assertTrue(fenMessage.contains(" b "), "Dopo e4 deve giocare il Nero: " + fenMessage);

        // La chat viene inoltrata con il mittente
        black.send("CHAT Buona partita!");
        String chat = white.await("CHAT ");
        assertTrue(chat.contains("Buona partita!"), "Il messaggio di chat deve essere inoltrato: " + chat);
    }

    @Test
    @DisplayName("Login con password corretta e rifiuto di password errata")
    void loginWithHashedPasswords() throws IOException {
        TestClient client = connect();
        client.await("CONNECTED");

        client.send("REGISTER tester password123");
        assertNotNull(client.await("REGISTER_OK tester"));

        // Username duplicato
        TestClient other = connect();
        other.await("CONNECTED");
        other.send("REGISTER tester password123");
        assertNotNull(other.await("ERROR"), "La registrazione duplicata deve fallire");

        // Password errata
        client.send("LOGIN tester wrongpass123");
        assertNotNull(client.await("ERROR"), "La password errata deve essere rifiutata");

        // Password corretta
        client.send("LOGIN tester password123");
        String loginOk = client.await("LOGIN_OK tester");
        assertTrue(loginOk.contains("ELO:1200"), "L'ELO iniziale deve essere 1200, ottenuto: " + loginOk);
    }

    @Test
    @DisplayName("Il server sopravvive a comandi sconosciuti e input malformati")
    void malformedInputIsHandled() throws IOException {
        TestClient client = connect();
        client.await("CONNECTED");

        client.send("COMANDO_INESISTENTE");
        assertTrue(client.await("ERROR").contains("non riconosciuto"),
                "Un comando sconosciuto deve generare un ERROR");

        client.send("JOIN");
        client.awaitErrorContaining("JOIN <roomId>");

        client.send("MOVE");
        client.awaitErrorContaining("Non sei in nessuna partita");

        // La connessione deve restare utilizzabile
        client.send("PING");
        assertEquals("PONG", client.await("PONG"));
    }

    @Test
    @DisplayName("Il server con il costruttore predefinito mantiene la persistenza attiva")
    void defaultServerKeepsPersistenceEnabled() {
        // Regressione: passando null come file degli account la persistenza veniva
        // disattivata del tutto e gli account non venivano mai salvati, ma i test
        // restavano veriperché i dati vivevano solo in memoria.
        Server plain = new Server(0);
        assertNotNull(plain.getAccountManager().getDataFile(),
                "Il server predefinito deve avere un file account configurato");
        assertEquals(org.schacchi.server.AccountManager.DEFAULT_DATA_FILE,
                plain.getAccountManager().getDataFile());
    }

    @Test
    @DisplayName("HELP elenca tutti i comandi implementati")
    void helpListsEveryCommand() throws IOException {
        TestClient client = connect();
        client.await("CONNECTED");
        String help = client.await("HELP");
        // I comandi che la vecchia lista ometteva
        for (String cmd : new String[]{"LOGIN", "REGISTER", "STATS", "FRIENDS", "FRIEND_ADD", "PING", "NAME"}) {
            assertTrue(help.contains(cmd), "HELP deve menzionare " + cmd + ", ottenuto: " + help);
        }
        // E quelli gia' presenti, per non aver perso nulla
        for (String cmd : new String[]{"CREATE", "JOIN", "MOVE", "RESIGN", "DRAW_OFFER", "DRAW_ACCEPT",
                "DRAW_DECLINE", "CHAT", "BOARD", "LEAVE", "QUIT"}) {
            assertTrue(help.contains(cmd), "HELP deve menzionare " + cmd + ", ottenuto: " + help);
        }
    }

    @Test
    @DisplayName("Connessioni oltre il tetto vengono rifiutate")
    void connectionLimitIsEnforced() throws IOException, InterruptedException {
        int limit = 3;
        // Tetto basso per poterlo verificare davvero senza aprire 200 socket
        Server limited = Server.installInstance(new Server(0, accountsFile.toString(), limit));
        limited.startAsync();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (limited.getPort() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        try {
            assertEquals(limit, limited.getMaxClients());
            assertTrue(limited.isRunning());

            List<TestClient> accepted = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                TestClient c = new TestClient(limited.getPort());
                clients.add(c);
                c.await("CONNECTED");
                accepted.add(c);
            }
            long waitDeadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (limited.getConnectedClients().size() < limit && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(10);
            }
            assertEquals(limit, limited.getConnectedClients().size());

            // La successiva viene rifiutata con un motivo leggibile
            try (Socket extra = new Socket("127.0.0.1", limited.getPort())) {
                extra.setSoTimeout((int) TIMEOUT_MS);
                BufferedReader in = new BufferedReader(new InputStreamReader(extra.getInputStream()));
                String line = in.readLine();
                assertNotNull(line, "Il server deve rispondere prima di chiudere");
                assertTrue(line.startsWith("ERROR"), "Il rifiuto deve essere un ERROR, ottenuto: " + line);
                assertTrue(line.contains("limite"), "Il messaggio deve spiegare il motivo, ottenuto: " + line);
            }

            // Il tetto non blocca le connessioni già accettate
            accepted.get(0).send("PING");
            assertEquals("PONG", accepted.get(0).await("PONG"));
        } finally {
            limited.stop();
        }
    }

    @Test
    @DisplayName("Un nome gia' in uso non puo' essere preso da un secondo client")
    void duplicateOnlineNameIsRejected() throws IOException {
        TestClient first = connect();
        TestClient second = connect();
        first.await("CONNECTED");
        second.await("CONNECTED");

        first.send("NAME GiocatoreUnico");
        assertTrue(first.await("NAME_CHANGED GiocatoreUnico").startsWith("NAME_CHANGED"));

        // Il secondo client non puo' rubare il nome
        second.send("NAME giocatoreunico");
        second.awaitErrorContaining("gia' in uso");

        // E non puo' nemmeno fare login con l'account corrispondente
        first.send("REGISTER giocatoreunico password123");
        first.await("REGISTER_OK");
        second.send("LOGIN giocatoreunico password123");
        second.awaitErrorContaining("gia' collegato");

        // Un nome libero invece funziona
        second.send("NAME AltroNome");
        assertTrue(second.await("NAME_CHANGED AltroNome").startsWith("NAME_CHANGED"));
    }

    @Test
    @DisplayName("Uno spettatore riceve lo storico delle mosse gia' giocate")
    void spectatorReceivesMoveHistory() throws IOException {
        TestClient white = connect();
        TestClient black = connect();
        TestClient third = connect();
        white.await("CONNECTED");
        black.await("CONNECTED");
        third.await("CONNECTED");

        white.send("CREATE Sala");
        String roomId = white.await("ROOM_CREATED").split("\\s+")[1];
        black.send("JOIN " + roomId);
        white.await("GAME_START");
        black.await("GAME_START");

        for (String uci : new String[]{"e2e4", "e7e5", "g1f3"}) {
            TestClient mover = uci.equals("e7e5") ? black : white;
            mover.send("MOVE " + uci);
            mover.await("MOVE_OK " + uci);
        }

        third.send("JOIN " + roomId);
        third.await("SPECTATING " + roomId);
        String history = third.await("MOVE_HISTORY");
        assertTrue(history.startsWith("MOVE_HISTORY e2e4 e7e5 g1f3"),
                "Lo spettatore deve ricevere tutte le mosse precedenti, ottenuto: " + history);
    }

}
