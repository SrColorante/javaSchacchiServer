package org.schacchi.server;

import java.io.IOException;
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

    /** Porta richiesta. Diventa la porta effettiva dopo l'associazione del socket. */
    private int port;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    private volatile ExecutorService clientPool;
    private final SessionManager sessionManager;
    private final AccountManager accountManager;
    private final Set<ConnectionHandler> connectedClients;
    private final List<ServerListener> listeners;

    /**
     * Costruttore privato (Singleton).
     */
    public Server(int port) {
        // Il percorso predefinito mantiene la persistenza attiva: passare null
        // disattiverebbe del tutto il salvataggio degli account.
        this(port, AccountManager.DEFAULT_DATA_FILE);
    }

    /**
     * @param port         porta su cui mettersi in ascolto; 0 per una porta libera scelta dal sistema
     * @param accountsFile percorso del file degli account; null per non salvare su disco
     */
    public Server(int port, String accountsFile) {
        this.port = port;
        this.clientPool = Executors.newCachedThreadPool();
        this.sessionManager = new SessionManager();
        this.accountManager = new AccountManager(accountsFile);
        this.connectedClients = ConcurrentHashMap.newKeySet();
        this.listeners = new CopyOnWriteArrayList<>();
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
     * Installa un'istanza dedicata, bypassing il singleton.
     * Riservato ai test, che hanno bisogno di un server su porta libera e di un
     * file account temporaneo; in produzione l'istanza globale resta unica.
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

        // stop() chiude il pool dei client: se il server viene riavviato (pulsante
        // "Riavvia Server" della dashboard) ne serve uno nuovo, altrimenti ogni
        // submit() successivo verrebbe rifiutato.
        if (clientPool == null || clientPool.isShutdown() || clientPool.isTerminated()) {
            clientPool = Executors.newCachedThreadPool();
        }
        log("==================================================");
        log(" Server Scacchi avviato sulla porta " + port);
        log(" Host configurato per: game.cristianrenosto.party");
        log(" In attesa di connessioni client...");
        log("==================================================");

        for (ServerListener listener : listeners) {
            listener.onServerStarted(port);
        }

        try {
            while (running && !serverSocket.isClosed()) {
                Socket clientSocket = serverSocket.accept();
                log("Nuovo client connesso da: " + clientSocket.getRemoteSocketAddress());
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
     * Avvia il server in un thread in background (non bloccante).
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
        System.out.println("[ChessServer] " + message);
        for (ServerListener l : listeners) {
            l.onLog(message);
        }
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        Server server = Server.getInstance(port);
        try {
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}