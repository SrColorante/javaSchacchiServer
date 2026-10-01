package org.schacchi.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server di Scacchi multithreaded (Singleton).
 * Gestisce le connessioni TCP dei client, coordina le stanze di gioco
 * e agisce da ponte imparziale e garante delle regole per le sessioni di scacchi.
 * Progettato per l'hosting su game.cristianrenosto.party.
 */
public class Server {
    private static Server instance;

    public static final int DEFAULT_PORT = 12345;

    /**
     * Tetto di connessioni contemporanee. Ogni connessione consuma un thread del pool
     * per tutta la sua durata, quindi il limite protegge da un flood di connessioni
     * che, con un pool illimitato, consumerebbe memoria e thread senza controllo.
     * Sovrascrivibile con la variabile d'ambiente {@code MAX_CLIENTS}.
     */
    public static final int DEFAULT_MAX_CLIENTS = 200;
    private final int maxClients;

    /** Timeout di inattivita' sui socket dei client: 0 per disabilitarlo. */
    public static final int DEFAULT_SOCKET_TIMEOUT_MS = 0;
    private final int socketTimeoutMs;

    /** Porta richiesta. Diventa la porta effettiva dopo l'associazione del socket. */
    private int port;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    private volatile ExecutorService clientPool;
    private final SessionManager sessionManager;
    private final AccountManager accountManager;
    private final Set<ConnectionHandler> connectedClients;
    private final List<ServerListener> listeners;

    /** Eco dei log su stdout: disattivabile da chi si registra come listener. */
    private volatile boolean echoLogToStdout = true;

    /**
     * Costruttore privato (Singleton).
     */
    public Server(int port) {
        // Il percorso predefinito mantiene la persistenza attiva: passare null
        // disattiverebbe del tutto il salvataggio degli account.
        this(port, AccountManager.DEFAULT_DATA_FILE, 0);
    }

    /**
     * @param port         porta su cui mettersi in ascolto; 0 per una porta libera scelta dal sistema
     * @param accountsFile percorso del file degli account; null per non salvare su disco
     */
    public Server(int port, String accountsFile) {
        this(port, accountsFile, 0);
    }

    /**
     * @param port         porta su cui mettersi in ascolto; 0 per una porta libera scelta dal sistema
     * @param accountsFile percorso del file degli account; null per non salvare su disco
     * @param maxClients   tetto di connessioni contemporanee; 0 per usare l'impostazione
     *                     predefinita o la variabile d'ambiente {@code MAX_CLIENTS}
     */
    public Server(int port, String accountsFile, int maxClients) {
        this.port = port;
        this.maxClients = maxClients > 0 ? maxClients : readPositiveEnv("MAX_CLIENTS", DEFAULT_MAX_CLIENTS);
        this.socketTimeoutMs = readNonNegativeEnv("SOCKET_TIMEOUT_MS", DEFAULT_SOCKET_TIMEOUT_MS);
        // Pool BOSSERVATO e limitato: ogni connessione occupa un thread per tutta la sua
        // durata, quindi un pool illimitato (cached) non mette alcun tetto al numero di
        // thread vivi contemporaneamente.
        this.clientPool = Executors.newFixedThreadPool(this.maxClients, runnable -> {
            Thread t = new Thread(runnable, "ChessClient");
            t.setDaemon(true);
            return t;
        });
        this.sessionManager = new SessionManager();
        this.accountManager = new AccountManager(accountsFile);
        this.connectedClients = ConcurrentHashMap.newKeySet();
        this.listeners = new CopyOnWriteArrayList<>();
    }

    private static int readPositiveEnv(String name, int fallback) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int readNonNegativeEnv(String name, int fallback) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed >= 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Restituisce l'istanza globale del Server (porta di default 12345 o configurata via variabile d'ambiente PORT).
     */
    public static synchronized Server getInstance() {
        if (instance == null) {
            int port = DEFAULT_PORT;
            String envPort = System.getenv("PORT");
            if (envPort != null) {
                try {
                    port = Integer.parseInt(envPort.trim());
                } catch (NumberFormatException ignored) {}
            }
            instance = new Server(port);
        }
        return instance;
    }

    /**
     * Inizializza o restituisce l'istanza con porta personalizzata.
     */
    public static synchronized Server getInstance(int port) {
        if (instance == null) {
            instance = new Server(port);
        }
        return instance;
    }

    /**
     * Installa l'istanza globale, scartando quella precedente se esiste.
     *
     * <p>Usata dai test (porta libera, file account temporaneo) e dall'entry point
     * {@code org.schacchi.Main}, che costruisce il server con porta e file account
     * letti dagli argomenti invece che dall'ambiente.
     *
     * <p>Deve essere chiamata da {@code Main}: {@link ConnectionHandler} e
     * {@link SessionManager} raggiungono il server con {@link #getInstance()}, quindi
     * creare un server con il solo costruttore lascerebbe il networking con un
     * {@link SessionManager} diverso da quello che ha fatto {@code start()}.
     */
    public static synchronized Server installInstance(Server newInstance) {
        instance = newInstance;
        return instance;
    }

    /**
     * Avvia il ServerSocket e il loop di ricezione connessioni.
     */
    public void start() throws IOException {
        if (running) {
            log("Server gia' in esecuzione sulla porta " + port);
            return;
        }

        serverSocket = new ServerSocket(port);
        // Con port=0 il sistema assegna una porta libera: getPort() deve riflettere
        // quella reale, altrimenti i client non saprebbero a cosa connettersi.
        this.port = serverSocket.getLocalPort();
        running = true;

        // stop() chiude il pool dei client: se il server viene riavviato (per esempio
        // dal comando "stop" seguito da un nuovo start) ne serve uno nuovo, altrimenti
        // ogni submit() successivo verrebbe rifiutato.
        if (clientPool == null || clientPool.isShutdown() || clientPool.isTerminated()) {
            // Pool BOSSERVATO e limitato, non cached: ogni connessione occupa un
            // thread per tutta la sua durata, quindi un cached pool (illimitato)
            // permette a un flood di aprire thread senza tetto e di esaurire la memoria.
        }
        log("In ascolto sulla porta " + port + " (max " + maxClients + " connessioni simultanee)");

        for (ServerListener listener : listeners) {
            listener.onServerStarted(port);
        }

        try {
            while (running && !serverSocket.isClosed()) {
                Socket clientSocket = serverSocket.accept();
                // Gli indirizzi IP sono dato personale (GDPR art. 4(1)): non vengono
                // scritti nei log. Per la diagnostica del server si registra solo
                // l'esito della connessione, non chi si è connesso.
                if (connectedClients.size() >= maxClients) {
                    // Rifiuto esplicito invece di accodare: oltre il tetto di connessioni
                    // il client riceve un motivo e può riprovare, mentre il server non
                    // cresce in thread indefinitamente.
                    rejectOverloaded(clientSocket);
                    continue;
                }
                ConnectionHandler handler = new ConnectionHandler(clientSocket);
                clientPool.submit(handler);
            }
        } catch (IOException e) {
            if (running) {
                log("Errore nel loop di ascolto del Server: " + e.getMessage());
            }
        } finally {
            stop();
        }
    }

    /**
     * Rifiuta una connessione oltre il tetto massimo, chiudendo subito il socket.
     * Il client riceve un messaggio leggibile prima che la connessione venga chiusa.
     */
    private void rejectOverloaded(Socket clientSocket) {
        try (PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {
            out.println("ERROR Server al limite di connessioni contemporanee. Riprova piu' tardi.");
        } catch (IOException ignored) {
            // Il client potrebbe aver gia' chiuso: in tal caso non c'e' nulla da fare.
        }
        try {
            clientSocket.close();
        } catch (IOException ignored) {
        }
        log("Connessione rifiutata: raggiunto il limite di " + maxClients + " client contemporanei");
    }

    /**
     * Avvia un server in background (non bloccante).
     */
    public void startAsync() {
        Thread serverThread = new Thread(() -> {
            try {
                start();
            } catch (IOException e) {
                log("Impossibile avviare il server sulla porta " + port + ": " + e.getMessage());
            }
        }, "ChessServer-Listener");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    /**
     * Arresta il server e chiude tutte le risorse.
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;

        log("Arresto del server in corso...");

        // Disconnette tutti i client
        for (ConnectionHandler client : connectedClients) {
            client.sendMessage("SERVER_SHUTDOWN Il server sta per essere riavviato.");
            client.close();
        }
        connectedClients.clear();

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }

        clientPool.shutdownNow();

        for (ServerListener listener : listeners) {
            listener.onServerStopped();
        }
        log("Server arrestato con successo.");
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    public int getMaxClients() {
        return maxClients;
    }

    public int getSocketTimeoutMs() {
        return socketTimeoutMs;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public AccountManager getAccountManager() {
        return accountManager;
    }

    public boolean isUserOnline(String username) {
        if (username == null) return false;
        for (ConnectionHandler client : connectedClients) {
            if (username.equalsIgnoreCase(client.getUsername())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Verifica se un nome utente e' gia' in uso da una connessione attiva.
     * Serve a impedire che due client live condividano una stessa identita': i risultati
     * di partita vengono registrati per nome, quindi un nome duplicato farebbe finire
     * vittorie e sconfitte sullo stesso account senza che nessuno dei due se ne accorga.
     *
     * @param requester il client che sta chiedendo il nome, escluso dal controllo
     */
    public boolean isUsernameInUse(String username, ConnectionHandler requester) {
        if (username == null) return false;
        for (ConnectionHandler client : connectedClients) {
            if (client != requester && username.equalsIgnoreCase(client.getUsername())) {
                return true;
            }
        }
        return false;
    }

    public Set<ConnectionHandler> getConnectedClients() {
        return connectedClients;
    }

    public void registerClient(ConnectionHandler client) {
        connectedClients.add(client);
        for (ServerListener l : listeners) {
            l.onClientConnected(client);
        }
    }

    public void unregisterClient(ConnectionHandler client) {
        connectedClients.remove(client);
        for (ServerListener l : listeners) {
            l.onClientDisconnected(client);
        }
    }

    public void broadcast(String message) {
        for (ConnectionHandler client : connectedClients) {
            client.sendMessage(message);
        }
    }

    public void addListener(ServerListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ServerListener listener) {
        listeners.remove(listener);
    }

    public void notifySessionCreated(GameSession session) {
        log("[Session] Creata stanza: " + session.getSessionId() + " (" + session.getRoomName() + ")");
        for (ServerListener l : listeners) {
            l.onSessionCreated(session);
        }
    }

    public void notifySessionStarted(GameSession session) {
        log("[Session] Partita iniziata in " + session.getSessionId() + ": " +
            session.getWhitePlayer().getUsername() + " vs " + session.getBlackPlayer().getUsername());
        for (ServerListener l : listeners) {
            l.onSessionStarted(session);
        }
    }

    public void notifyMoveMade(GameSession session, String move, String fen) {
        log("[Move] Stanza " + session.getSessionId() + " -> " + move + " | FEN: " + fen);
        for (ServerListener l : listeners) {
            l.onMoveMade(session, move, fen);
        }
    }

    public void notifySessionEnded(GameSession session, String reason) {
        log("[Session] Partita conclusa in " + session.getSessionId() + ": " + reason);
        for (ServerListener l : listeners) {
            l.onSessionEnded(session, reason);
        }
    }

    public void log(String message) {
        if (echoLogToStdout) {
            System.out.println("[ChessServer] " + message);
        }
        for (ServerListener l : listeners) {
            l.onLog(message);
        }
    }

    /**
     * Disattiva il rispecchiamento dei log su stdout.
     *
     * <p>Quando qualcuno si registra come {@link ServerListener} per formattare i log
     * (è il caso dell'entry point {@code org.schacchi.Main}, che li stampa colorati e
     * datati) l'eco su System.out produrrebbe ogni messaggio due volte: una grezza e
     * una formattata.
     */
    public void setEchoLogToStdout(boolean enabled) {
        this.echoLogToStdout = enabled;
    }
}