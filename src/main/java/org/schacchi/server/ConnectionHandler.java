package org.schacchi.server;

import org.schacchi.model.PieceColor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gestisce la connessione individuale di ciascun client connesso al server di scacchi.
 * Permette di creare stanze, unirsi a sessioni esistenti, fare matchmaking rapido
 * e scambiare mosse e chat all'interno di una GameSession.
 */
public class ConnectionHandler implements Runnable {
    private static final AtomicInteger CLIENT_COUNTER = new AtomicInteger(1);

    private final Socket clientSocket;
    private PrintWriter out;
    private BufferedReader in;

    private String username;
    // volatile: letto e scritto sia dal thread che legge il socket sia dai thread
    // che gestiscono la partita, senza alcun happens-before esplicito.
    private volatile GameSession currentSession;
    private volatile PieceColor assignedColor;
    private volatile boolean running = true;

    /** Garantisce che close() venga eseguito una sola volta, senza prendere lock. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ConnectionHandler(Socket socket) {
        this.clientSocket = socket;
        this.username = "Player_" + CLIENT_COUNTER.getAndIncrement();
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public GameSession getCurrentSession() {
        return currentSession;
    }

    public void setCurrentSession(GameSession currentSession) {
        this.currentSession = currentSession;
    }

    public PieceColor getAssignedColor() {
        return assignedColor;
    }

    public void setAssignedColor(PieceColor assignedColor) {
        this.assignedColor = assignedColor;
    }

    public boolean isConnected() {
        return running && clientSocket != null && !clientSocket.isClosed();
    }

    /**
     * Invia un messaggio al client.
     *
     * <p>Volutamente NON sincronizzato su questo oggetto: {@link PrintWriter} e' gia'
     * thread-safe di per se', e prendere qui il monitor di ConnectionHandler creerebbe
     * un'inversione di ordine dei lock. Infatti {@code GameSession.processMove} e'
     * synchronized sulla sessione e chiama {@code sendMessage}: se anche sendMessage
     * prendesse il monitor dell'handler, si potrebbe formare un ciclo
     * handler -> sessione (in {@link #close()}) e sessione -> handler (in processMove),
     * bloccando per sempre sia i turni sia {@code Server.stop()}.
     */
    public void sendMessage(String message) {
        PrintWriter writer = this.out;
        if (writer != null) {
            writer.println(message);
        }
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(clientSocket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

            Server.getInstance().registerClient(this);

            // Messaggio di benvenuto con specifiche del server e comandi supportati
            sendMessage("CONNECTED Welcome to Chess Server [game.cristianrenosto.party]");
            sendMessage("YOUR_NAME " + username);
            sendMessage("HELP Comandi disponibili: NAME <nome>, CREATE [stanza], JOIN <id>, PLAY, LIST, MOVE <uci>, RESIGN, DRAW_OFFER, DRAW_ACCEPT, DRAW_DECLINE, CHAT <msg>, BOARD, LEAVE, QUIT");

            String inputLine;
            while (running && (inputLine = in.readLine()) != null) {
                String trimmed = inputLine.trim();
                if (trimmed.isEmpty()) continue;
                handleCommand(trimmed);
            }
        } catch (IOException e) {
            // Disconnessione normale o perdita di socket
        } finally {
            close();
        }
    }

    private void handleCommand(String line) {
        String[] parts = line.split("\\s+", 2);
        String command = parts[0].toUpperCase();
        String argument = (parts.length > 1) ? parts[1].trim() : "";

        switch (command) {
            case "REGISTER" -> {
                String[] creds = argument.split("\\s+");
                if (creds.length >= 2) {
                    boolean ok = Server.getInstance().getAccountManager().register(creds[0], creds[1]);
                    if (ok) {
                        this.username = creds[0];
                        sendMessage("REGISTER_OK " + this.username);
                        Server.getInstance().log("Nuovo account registrato: " + this.username);
                    } else {
                        sendMessage("ERROR Registrazione fallita. Username gia' esistente o password troppo corta (min 3 caratteri).");
                    }
                } else {
                    sendMessage("ERROR Sintassi errata: REGISTER <username> <password>");
                }
            }

            case "LOGIN" -> {
                String[] creds = argument.split("\\s+");
                if (creds.length >= 2) {
                    AccountManager.Account acc = Server.getInstance().getAccountManager().authenticate(creds[0], creds[1]);
                    if (acc != null) {
                        this.username = acc.getUsername();
                        sendMessage("LOGIN_OK " + acc.getUsername() + " ELO:" + acc.getElo() + " W:" + acc.getWins() + " L:" + acc.getLosses() + " D:" + acc.getDraws());
                        Server.getInstance().log("Login eseguito: " + this.username);
                    } else {
                        sendMessage("ERROR Credenziali non valide per il login.");
                    }
                } else {
                    sendMessage("ERROR Sintassi errata: LOGIN <username> <password>");
                }
            }

            case "FRIEND_ADD" -> {
                if (!argument.isEmpty()) {
                    boolean ok = Server.getInstance().getAccountManager().addFriend(this.username, argument);
                    if (ok) {
                        sendMessage("FRIEND_ADDED " + argument);
                        sendMessage("INFO " + argument + " e' stato aggiunto ai tuoi amici!");
                    } else {
                        sendMessage("ERROR Impossibile aggiungere amico (utente inesistente o sei tu stesso).");
                    }
                } else {
                    sendMessage("ERROR Specificare il nome dell'amico: FRIEND_ADD <nome>");
                }
            }

            case "FRIENDS" -> {
                var friends = Server.getInstance().getAccountManager().getFriends(this.username);
                sendMessage("FRIENDS_LIST " + friends.size());
                for (String f : friends) {
                    boolean online = Server.getInstance().isUserOnline(f);
                    sendMessage("FRIEND " + f + " " + (online ? "ONLINE" : "OFFLINE"));
                }
                sendMessage("FRIENDS_END");
            }

            case "STATS" -> {
                AccountManager.Account acc = Server.getInstance().getAccountManager().getAccount(this.username);
                if (acc != null) {
                    sendMessage("STATS " + acc.getUsername() + " ELO:" + acc.getElo() + " W:" + acc.getWins() + " L:" + acc.getLosses() + " D:" + acc.getDraws());
                } else {
                    sendMessage("STATS " + this.username + " ELO:1200 W:0 L:0 D:0");
                }
            }
            case "NAME" -> {
                if (!argument.isEmpty()) {
                    String clean = argument.replaceAll("[^a-zA-Z0-9_\\-]", "");
                    if (clean.isEmpty()) {
                        sendMessage("ERROR Il nome puo' contenere solo lettere, numeri, '_' e '-'.");
                    } else {
                        String oldName = this.username;
                        this.username = clean;
                        sendMessage("NAME_CHANGED " + this.username);
                        Server.getInstance().log("Client " + oldName + " rinominato in " + this.username);
                    }
                } else {
                    sendMessage("ERROR Specificare un nome: NAME <tuonome>");
                }
            }

            case "CREATE" -> {
                if (currentSession != null && currentSession.getStatus() == GameSession.GameStatus.IN_PROGRESS) {
                    sendMessage("ERROR Sei gia' in una partita attiva. Usa LEAVE o RESIGN prima.");
                    return;
                }
                // Creare una stanza sottrae il client dalla coda di matchmaking: altrimenti
                // potrebbe essere abbinato a un avversario mentre ospita gia' una partita.
                Server.getInstance().getSessionManager().removeFromMatchmaking(this);
                String roomName = argument.isEmpty() ? (username + "'s Room") : argument;
                GameSession newSession = Server.getInstance().getSessionManager().createSession(null, roomName, this);
                sendMessage("ROOM_CREATED " + newSession.getSessionId() + " WHITE " + newSession.getRoomName());
                sendMessage("INFO Stanza creata. In attesa del secondo giocatore (ID: " + newSession.getSessionId() + ")");
            }

            case "JOIN" -> {
                if (argument.isEmpty()) {
                    sendMessage("ERROR Specificare l'ID della stanza: JOIN <roomId>");
                    return;
                }
                if (currentSession != null && currentSession.getStatus() == GameSession.GameStatus.IN_PROGRESS) {
                    sendMessage("ERROR Sei gia' in una partita attiva.");
                    return;
                }
                Server.getInstance().getSessionManager().removeFromMatchmaking(this);
                GameSession joined = Server.getInstance().getSessionManager().joinSession(argument, this);
                if (joined == null) {
                    sendMessage("ERROR Stanza non trovata o partita gia' conclusa con ID: " + argument);
                }
            }

            case "PLAY", "QUICKMATCH", "MATCH" -> {
                if (currentSession != null && currentSession.getStatus() == GameSession.GameStatus.IN_PROGRESS) {
                    sendMessage("ERROR Sei gia' in una partita attiva.");
                    return;
                }
                Server.getInstance().getSessionManager().quickMatch(this);
            }

            case "LIST" -> {
                List<GameSession> open = Server.getInstance().getSessionManager().getOpenSessions();
                if (open.isEmpty()) {
                    sendMessage("ROOM_LIST 0");
                    sendMessage("INFO Nessuna stanza aperta al momento. Usa CREATE o PLAY.");
                } else {
                    sendMessage("ROOM_LIST " + open.size());
                    for (GameSession s : open) {
                        String hostName = (s.getWhitePlayer() != null) ? s.getWhitePlayer().getUsername() : "Unknown";
                        sendMessage("ROOM " + s.getSessionId() + " " + s.getRoomName() + " HOST:" + hostName);
                    }
                }
            }

            case "MOVE" -> {
                if (currentSession == null) {
                    sendMessage("ERROR Non sei in nessuna partita");
                    return;
                }
                if (argument.isEmpty()) {
                    sendMessage("ERROR Specificare la mossa in notazione UCI (es: MOVE e2e4)");
                    return;
                }
                currentSession.processMove(this, argument);
            }

            case "RESIGN" -> {
                if (currentSession != null) {
                    currentSession.processResign(this);
                } else {
                    sendMessage("ERROR Non sei in nessuna partita");
                }
            }

            case "DRAW_OFFER" -> {
                if (currentSession != null) {
                    currentSession.processDrawOffer(this);
                }
            }

            case "DRAW_ACCEPT" -> {
                if (currentSession != null) {
                    currentSession.processDrawAccept(this);
                }
            }

            case "DRAW_DECLINE" -> {
                if (currentSession != null) {
                    currentSession.processDrawDecline(this);
                }
            }

            case "CHAT" -> {
                if (currentSession != null) {
                    if (!argument.isEmpty()) {
                        currentSession.processChat(this, argument);
                    }
                } else {
                    sendMessage("ERROR Non sei in nessuna stanza per chattare");
                }
            }

            case "BOARD", "FEN" -> {
                if (currentSession != null) {
                    sendMessage("FEN " + currentSession.getBoard().toFen());
                } else {
                    sendMessage("ERROR Non sei in nessuna partita");
                }
            }

            case "LEAVE" -> {
                if (currentSession != null) {
                    // handlePlayerDisconnect distingue da solo lo spettatore dal giocatore:
                    // uscire da una partita in corso vale come abbandono ( forfeit ).
                    currentSession.handlePlayerDisconnect(this);
                    currentSession = null;
                    assignedColor = null;
                    sendMessage("INFO Hai lasciato la partita");
                }
                Server.getInstance().getSessionManager().removeFromMatchmaking(this);
            }

            case "PING" -> sendMessage("PONG");

            case "QUIT", "EXIT" -> close();

            default -> sendMessage("ERROR Comando non riconosciuto: " + command + ". Digita HELP per la lista comandi.");
        }
    }

    /**
     * Chiude la connessione in modo idempotente.
     *
     * <p>L'idempotenza e' garantita da un {@link AtomicBoolean} invece che da un metodo
     * synchronized: in questo modo nessun monitor viene trattenuto mentre si invocano i
     * metodi della {@link GameSession}, eliminando il rischio di deadlock descritto in
     * {@link #sendMessage(String)}.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // gia' chiuso: prosegue un solo chiamante
        }
        running = false;

        // Si stacca la sessione prima di notificarla, cosi' un close() concorrente
        // non la ritroverebbe piu' e non chiuderebbe due volte la stessa partita.
        GameSession session = this.currentSession;
        this.currentSession = null;
        this.assignedColor = null;

        try {
            Server.getInstance().getSessionManager().removeFromMatchmaking(this);
        } catch (RuntimeException ignored) {
            // Un problema nello stato del server non deve impedire la chiusura del socket.
        }

        if (session != null) {
            session.handlePlayerDisconnect(this);
        }

        Server.getInstance().unregisterClient(this);

        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }
        } catch (IOException ignored) {}
    }
}