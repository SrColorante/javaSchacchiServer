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

    /**
     * Testo di aiuto inviato all'avvio con il comando {@code HELP}.
     * Elenca tutti i comandi effettivamente implementati: la lista precedente ometteva
     * REGISTER, LOGIN, STATS, FRIENDS, FRIEND_ADD e PING.
     */
    private static final String HELP_TEXT = String.join(
            " | ",
            "LOGIN <utente> <password>",
            "REGISTER <utente> <password> [eta]",
            "LOGOUT",
            "STATS",
            "NAME <nome>",
            "FRIENDS",
            "FRIEND_ADD <utente>",
            "CREATE [nomeStanza]",
            "JOIN <idStanza>",
            "PLAY (matchmaking)",
            "LIST",
            "MOVE <uci> (es. e2e4, e7e8q)",
            "BOARD (FEN)",
            "CHAT <messaggio>",
            "DRAW_OFFER",
            "DRAW_ACCEPT",
            "DRAW_DECLINE",
            "RESIGN",
            "LEAVE",
            "EXPORT_DATA (dati personali)",
            "DELETE_ACCOUNT <password>",
            "PING",
            "QUIT");

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
            // Timeout di inattivita': senza, una connessione rimasta aperta ma muta
            // (mezza aperta, rete caduta silenziosamente) occupa per sempre un thread
            // del pool, che e' a dimensione fissa. Un valore di 0 disabilita il timeout.
            int timeoutMs = Server.getInstance().getSocketTimeoutMs();
            if (timeoutMs > 0) {
                clientSocket.setSoTimeout(timeoutMs);
            }

            out = new PrintWriter(clientSocket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

            Server.getInstance().registerClient(this);

            // Messaggio di benvenuto con specifiche del server e comandi supportati
            sendMessage("CONNECTED Welcome to Chess Server [game.cristianrenosto.party]");
            sendMessage("YOUR_NAME " + username);
            sendMessage("HELP " + HELP_TEXT);

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
                // Sintassi: REGISTER <utente> <password> [età]
                // L'età è opzionale ma, se dichiarata, viene verificata contro l'art. 8 GDPR.
                String[] creds = argument.split("\\s+");
                if (creds.length >= 2) {
                    int declaredAge = 0;
                    if (creds.length >= 3) {
                        try {
                            declaredAge = Integer.parseInt(creds[2]);
                        } catch (NumberFormatException e) {
                            sendMessage("ERROR Età non valida: deve essere un numero intero.");
                            return;
                        }
                    }
                    if (declaredAge != 0 && declaredAge < AccountManager.MIN_ALLOWED_AGE) {
                        sendMessage("ERROR Registrazione non consentita: il servizio è riservato a chi ha almeno "
                                + AccountManager.MIN_ALLOWED_AGE + " anni.");
                        return;
                    }
                    boolean ok = Server.getInstance().getAccountManager()
                            .register(creds[0], creds[1], declaredAge);
                    if (ok) {
                        // Solo ora il nome diventa proprio: registrarlo prima dell'OK
                        // esporrebbe gli altri client a un nome che potrebbe non essere
                        // stato assegnato.
                        this.username = Server.getInstance().getAccountManager()
                                .getAccount(creds[0]).getUsername();
                        // Sotto i 16 anni il consenso deve essere autorizzato da un genitore:
                        // l'avviso rende esplicito il punto senza impedire la registrazione,
                        // che per l'art. 8 è lecita con autorizzazione del titolare della
                        // responsabilità genitoriale.
                        String notice = AccountManager.isSelfConsentingAge(declaredAge)
                                ? ""
                                : " Attenzione: con eta' dichiarata inferiore a "
                                    + AccountManager.MIN_SELF_CONSENT_AGE
                                    + " anni l'uso e' subordinato al consenso di un genitore.";
                        sendMessage("REGISTER_OK " + this.username + notice);
                        Server.getInstance().log("Nuovo account registrato: " + this.username);
                    } else if (Server.getInstance().getAccountManager().isUsernameTaken(creds[0])) {
                        sendMessage("ERROR Username gia' registrato. Scegline un altro o accedi con LOGIN.");
                    } else {
                        sendMessage("ERROR Registrazione fallita. Username minimo 3 caratteri, password minimo 8.");
                    }
                } else {
                    sendMessage("ERROR Sintassi errata: REGISTER <username> <password> [eta]");
                }
            }

            case "LOGOUT" -> {
                // Consente di uscire da un account restando collegati: senza questo,
                // l'unico modo per non avere più l'identità in uso era chiudere il socket.
                if (Server.getInstance().getAccountManager().getAccount(this.username) != null) {
                    this.username = "Guest_" + CLIENT_COUNTER.getAndIncrement();
                    sendMessage("LOGOUT_OK Sei ora un ospite: l'account non è più in uso su questa connessione.");
                } else {
                    sendMessage("INFO Sei già un ospite: nessun account da chiudere.");
                }
            }

            case "LOGIN" -> {
                String[] creds = argument.split("\\s+");
                if (creds.length >= 2) {
                    AccountManager.Account acc = Server.getInstance().getAccountManager().authenticate(creds[0], creds[1]);
                    if (acc != null && Server.getInstance().isUsernameInUse(acc.getUsername(), this)) {
                        // Un account gia' in uso su un'altra connessione non puo' giocare:
                        // altrimenti i risultati andrebbero a un'identita' condivisa.
                        sendMessage("ERROR L'account \"" + acc.getUsername()
                                + "\" e' gia' collegato da un'altra sessione. Esci da quella o usa un altro account.");
                    } else if (acc != null) {
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
                    } else if (Server.getInstance().isUsernameInUse(clean, this)) {
                        sendMessage("ERROR Il nome \"" + clean + "\" e' gia' in uso da un altro giocatore collegato.");
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

            case "EXPORT_DATA" -> handleExportData(argument);

            case "DELETE_ACCOUNT" -> handleDeleteAccount(argument);

            case "PING" -> sendMessage("PONG");

            case "HELP" -> sendMessage("HELP " + HELP_TEXT);

            case "QUIT", "EXIT" -> close();

            default -> sendMessage("ERROR Comando non riconosciuto: " + command + ". Digita HELP per la lista comandi.");
        }
    }

    /**
     * GDPR art. 15 e 20 — diritto di accesso e portabilità: restituisce tutti i
     * dati personali dell'utente. La traccia della password non viene inclusa:
     * non è dato dell'interessato, ma segreto tecnico del sistema.
     */
    private void handleExportData(String argument) {
        AccountManager manager = Server.getInstance().getAccountManager();
        if (manager.getAccount(this.username) == null) {
            sendMessage("EXPORT_EMPTY Sei un ospite: il server non conserva dati su di te. "
                    + "Nickname corrente: " + this.username);
            return;
        }
        List<String> lines = manager.exportAccount(this.username);
        for (String line : lines) {
            sendMessage("EXPORT_LINE " + line);
        }
        sendMessage("EXPORT_END Dati personali disponibili per " + this.username
                + ". Per la privacy: vedi docs/privacy.md");
    }

    /**
     * GDPR art. 17 — diritto di cancellazione. La password viene richiesta per
     * evitare che una sessione lasciata aperta su un computer condiviso permetta
     * a un terzo di cancellare l'account altrui.
     */
    private void handleDeleteAccount(String argument) {
        if (argument.isEmpty()) {
            sendMessage("ERROR Per cancellare l'account devi confermare la password: DELETE_ACCOUNT <password>");
            return;
        }
        AccountManager manager = Server.getInstance().getAccountManager();
        AccountManager.Account acc = manager.getAccount(this.username);
        if (acc == null) {
            sendMessage("INFO Sei un ospite: non ci sono dati da cancellare sul server.");
            return;
        }
        if (manager.authenticate(this.username, argument) == null) {
            sendMessage("ERROR Password non corretta: account non cancellato.");
            return;
        }

        String deletedName = acc.getUsername();
        if (!manager.deleteAccount(this.username)) {
            sendMessage("ERROR Cancellazione non riuscita.");
            return;
        }

        // La connessione resta valida ma il client non è più identificabile:
        // il nome generico impedisce che i risultati di partite successive
        // finiscano su un account appena cancellato.
        this.username = "Guest_" + CLIENT_COUNTER.getAndIncrement();
        sendMessage("DELETE_OK Account \"" + deletedName + "\" cancellato: dati, statistiche e amicizie rimossi.");
        sendMessage("INFO Ora sei un ospite. I risultati delle prossime partite non verranno registrati.");
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

        // L'ordine è essenziale: prima il SOCKET, poi lo stream di uscita.
        // Chiudere il BufferedReader mentre un altro thread è fermo in readLine()
        // blocca per sempre: readLine() trattiene il lock interno del reader e non
        // ritorna finché il socket non è chiuso, quindi close() aspetterebbe un evento
        // che solo close() può provocare. Chiudendo il socket, readLine() riceve EOF
        // e rilascia il lock.
        try {
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }
        } catch (IOException ignored) {
        }

        try {
            if (out != null) out.close();
        } catch (RuntimeException ignored) {
        }

        // 'in' non viene chiuso qui: lo usa solo il thread di lettura, che termina da
        // solo alla ricezione di EOF, e chiuderlo richiederebbe di contendere il suo lock.
    }
}