package org.schacchi.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Gestore della connessione di rete Socket TCP per il Client di scacchi.
 * Mantiene il thread in ascolto e invia notifiche asincrone ai listener.
 */
public class ClientNetwork {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private Thread readThread;
    private volatile boolean connected = false;

    private String currentUsername = "";
    private final List<ClientListener> listeners = new CopyOnWriteArrayList<>();

    // Buffer per liste stanze e amici
    private final List<RoomInfo> pendingRooms = new ArrayList<>();
    private final List<FriendInfo> pendingFriends = new ArrayList<>();

    public void addListener(ClientListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ClientListener listener) {
        listeners.remove(listener);
    }

    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }

    public String getCurrentUsername() {
        return currentUsername;
    }

    public synchronized void connect(String host, int port) {
        if (connected) return;

        new Thread(() -> {
            try {
                socket = new Socket(host, port);
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                connected = true;

                for (ClientListener l : listeners) {
                    l.onConnected();
                }

                startReading();
            } catch (IOException e) {
                connected = false;
                for (ClientListener l : listeners) {
                    l.onConnectionFailed("Impossibile connettersi al server (" + host + ":" + port + "): " + e.getMessage());
                }
            }
        }, "ChessClient-Connector").start();
    }

    private void startReading() {
        readThread = new Thread(() -> {
            try {
                String line;
                while (connected && (line = in.readLine()) != null) {
                    handleServerLine(line.trim());
                }
            } catch (IOException ignored) {
            } finally {
                disconnect();
            }
        }, "ChessClient-Receiver");
        readThread.start();
    }

    private void handleServerLine(String line) {
        if (line.isEmpty()) return;

        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toUpperCase();
        String arg = (parts.length > 1) ? parts[1].trim() : "";

        switch (cmd) {
            case "CONNECTED" -> {
                for (ClientListener l : listeners) l.onInfo("Connesso al server di gioco!");
            }
            case "LOGIN_OK" -> {
                // Formato: LOGIN_OK <username> ELO:<elo> W:<w> L:<l> D:<d>
                String[] loginParts = arg.split("\\s+");
                if (loginParts.length == 0) break;
                this.currentUsername = loginParts[0];
                int elo = 1200, wins = 0, losses = 0, draws = 0;
                for (int i = 1; i < loginParts.length; i++) {
                    String p = loginParts[i];
                    // Parsing difensivo: un campo malformato non deve far terminare
                    // il thread di lettura (e con esso l'intera connessione).
                    try {
                        if (p.startsWith("ELO:")) elo = Integer.parseInt(p.substring(4));
                        else if (p.startsWith("W:")) wins = Integer.parseInt(p.substring(2));
                        else if (p.startsWith("L:")) losses = Integer.parseInt(p.substring(2));
                        else if (p.startsWith("D:")) draws = Integer.parseInt(p.substring(2));
                    } catch (NumberFormatException ignored) {}
                }
                for (ClientListener l : listeners) l.onLoginSuccess(currentUsername, elo, wins, losses, draws);
            }
            case "REGISTER_OK" -> {
                // Il messaggio può contenere un avviso sull'art. 8 GDPR dopo il nome
                String[] regParts = arg.split("\\s+");
                this.currentUsername = regParts[0];
                for (ClientListener l : listeners) l.onRegisterSuccess(regParts[0]);
            }
            case "LOGOUT_OK" -> {
                for (ClientListener l : listeners) l.onLogoutSuccess();
            }
            case "EXPORT_LINE" -> {
                for (ClientListener l : listeners) l.onDataExported(arg);
            }
            case "EXPORT_END" -> {
                for (ClientListener l : listeners) l.onDataExportComplete(arg);
            }
            case "EXPORT_EMPTY" -> {
                for (ClientListener l : listeners) l.onDataExportEmpty(arg);
            }
            case "DELETE_OK" -> {
                for (ClientListener l : listeners) l.onAccountDeleted(arg);
            }
            case "NAME_CHANGED", "YOUR_NAME" -> {
                this.currentUsername = arg;
            }
            case "ROOM_CREATED" -> {
                // Formato: ROOM_CREATED <id> <color> <name>
                String[] rParts = arg.split("\\s+", 3);
                String rId = rParts[0];
                String color = rParts.length > 1 ? rParts[1] : "WHITE";
                String rName = rParts.length > 2 ? rParts[2] : rId;
                for (ClientListener l : listeners) l.onRoomCreated(rId, color, rName);
            }
            case "GAME_START" -> {
                // Formato: GAME_START <roomId> <color> <opponent>
                String[] gParts = arg.split("\\s+");
                String rId = gParts[0];
                String color = gParts.length > 1 ? gParts[1] : "WHITE";
                String opponent = gParts.length > 2 ? gParts[2] : "Avversario";
                for (ClientListener l : listeners) l.onGameStart(rId, color, opponent);
            }
            case "MOVE_OK" -> {
                // Formato: MOVE_OK <uci> <fen>
                String[] mParts = arg.split("\\s+", 2);
                String uci = mParts[0];
                String fen = mParts.length > 1 ? mParts[1] : "";
                for (ClientListener l : listeners) l.onMoveExecuted(uci, fen, true);
            }
            case "OPPONENT_MOVE" -> {
                // Formato: OPPONENT_MOVE <uci> <fen>
                String[] mParts = arg.split("\\s+", 2);
                String uci = mParts[0];
                String fen = mParts.length > 1 ? mParts[1] : "";
                for (ClientListener l : listeners) l.onMoveExecuted(uci, fen, false);
            }
            case "FEN" -> {
                // Posizione autorevole richiesta esplicitamente (comando BOARD/FEN)
                for (ClientListener l : listeners) l.onBoardSynced(arg.trim(), false);
            }
            case "SPECTATING" -> {
                // La stanza era gia' piena: questo client guarda la partita.
                // Formato: SPECTATING <roomId> <fen>
                String[] sParts = arg.split("\\s+", 2);
                String roomId = sParts[0];
                String fen = sParts.length > 1 ? sParts[1].trim() : "";
                for (ClientListener l : listeners) l.onSpectating(roomId, fen);
            }
            case "MOVE_HISTORY" -> {
                // Cronologia delle mosse inviate a uno spettatore che entra a meta' partita
                List<String> moves = new ArrayList<>();
                for (String uci : arg.trim().split("\\s+")) {
                    if (!uci.isBlank()) moves.add(uci);
                }
                for (ClientListener l : listeners) l.onMoveHistoryReceived(moves);
            }
            case "CHECK" -> {
                for (ClientListener l : listeners) l.onCheck(arg);
            }
            case "GAME_OVER" -> {
                // Formato: GAME_OVER <WINNER_RESULT> <REASON>
                String[] goParts = arg.split("\\s+", 2);
                String winner = goParts[0];
                String reason = goParts.length > 1 ? goParts[1] : "";
                for (ClientListener l : listeners) l.onGameOver(winner, reason);
            }
            case "ROOM_LIST" -> {
                pendingRooms.clear();
                // Notifica subito: se non ci sono stanze la tabella va svuotata
                // lo stesso, altrimenti resterebbero voci obsolete in lobby.
                for (ClientListener l : listeners) l.onRoomListUpdated(new ArrayList<>(pendingRooms));
            }
            case "ROOM" -> {
                // Formato: ROOM <id> <name> HOST:<host>
                String[] rParts = arg.split("\\s+");
                String rId = rParts[0];
                String rHost = "Unknown";
                StringBuilder rName = new StringBuilder();
                for (int i = 1; i < rParts.length; i++) {
                    if (rParts[i].startsWith("HOST:")) {
                        rHost = rParts[i].substring(5);
                    } else {
                        if (rName.length() > 0) rName.append(" ");
                        rName.append(rParts[i]);
                    }
                }
                pendingRooms.add(new RoomInfo(rId, rName.toString(), rHost));
                for (ClientListener l : listeners) l.onRoomListUpdated(new ArrayList<>(pendingRooms));
            }
            case "FRIENDS_LIST" -> {
                pendingFriends.clear();
                for (ClientListener l : listeners) l.onFriendsListUpdated(new ArrayList<>(pendingFriends));
            }
            case "FRIEND" -> {
                // Formato: FRIEND <name> <ONLINE|OFFLINE>
                String[] fParts = arg.split("\\s+");
                if (fParts.length >= 2) {
                    boolean online = "ONLINE".equalsIgnoreCase(fParts[1]);
                    pendingFriends.add(new FriendInfo(fParts[0], online));
                }
            }
            case "FRIENDS_END" -> {
                for (ClientListener l : listeners) l.onFriendsListUpdated(new ArrayList<>(pendingFriends));
            }
            case "FRIEND_ADDED" -> {
                for (ClientListener l : listeners) l.onFriendAdded(arg);
                refreshFriends();
            }
            case "CHAT" -> {
                int colonIdx = arg.indexOf(':');
                if (colonIdx > 0) {
                    String sender = arg.substring(0, colonIdx).trim();
                    String msg = arg.substring(colonIdx + 1).trim();
                    for (ClientListener l : listeners) l.onChatMessage(sender, msg);
                } else {
                    for (ClientListener l : listeners) l.onChatMessage("Server", arg);
                }
            }
            case "DRAW_OFFER" -> {
                for (ClientListener l : listeners) l.onDrawOfferReceived();
            }
            case "DRAW_DECLINED" -> {
                for (ClientListener l : listeners) l.onDrawDeclined();
            }
            case "PONG" -> {
                // Risposta a PING: utile per verificare che la connessione sia viva.
                for (ClientListener l : listeners) l.onPong();
            }
            case "HELP" -> {
                for (ClientListener l : listeners) l.onHelp(arg);
            }
            case "SERVER_SHUTDOWN" -> {
                // Il server sta per chiudere: senza questo la disconnessione sarebbe
                // invisibile e l'utente vedrebbe solo un socket morto.
                for (ClientListener l : listeners) l.onServerShutdown(arg);
            }
            case "ERROR" -> {
                for (ClientListener l : listeners) l.onError(arg);
            }
            case "INFO" -> {
                for (ClientListener l : listeners) l.onInfo(arg);
            }
        }
    }

    public synchronized void send(String command) {
        if (out != null && connected) {
            out.println(command);
        }
    }

    public void login(String username, String password) {
        send("LOGIN " + username + " " + password);
    }

    public void register(String username, String password) {
        send("REGISTER " + username + " " + password);
    }

    /**
     * @param declaredAge età dichiarata, o 0 per non dichiararla
     */
    public void register(String username, String password, int declaredAge) {
        if (declaredAge > 0) {
            send("REGISTER " + username + " " + password + " " + declaredAge);
        } else {
            register(username, password);
        }
    }

    /** Esce dall'account restando collegati come ospite. */
    public void logout() {
        send("LOGOUT");
    }

    /** Chiede al server tutti i dati personali dell'utente (GDPR art. 15 e 20). */
    public void exportData() {
        send("EXPORT_DATA");
    }

    /** Cancella l'account (GDPR art. 17). */
    public void deleteAccount(String password) {
        send("DELETE_ACCOUNT " + password);
    }

    public void setName(String name) {
        send("NAME " + name);
    }

    public void createRoom(String roomName) {
        send("CREATE " + (roomName != null ? roomName : ""));
    }

    public void joinRoom(String roomId) {
        send("JOIN " + roomId);
    }

    public void quickMatch() {
        send("PLAY");
    }

    public void refreshRooms() {
        send("LIST");
    }

    public void sendMove(String uci) {
        send("MOVE " + uci);
    }

    public void resign() {
        send("RESIGN");
    }

    public void offerDraw() {
        send("DRAW_OFFER");
    }

    public void acceptDraw() {
        send("DRAW_ACCEPT");
    }

    public void declineDraw() {
        send("DRAW_DECLINE");
    }

    public void sendChat(String msg) {
        send("CHAT " + msg);
    }

    public void addFriend(String friendName) {
        send("FRIEND_ADD " + friendName);
    }

    public void refreshFriends() {
        send("FRIENDS");
    }

    public void leaveRoom() {
        send("LEAVE");
    }

    public synchronized void disconnect() {
        if (!connected) return;

        // QUIT va inviato PRIMA di azzerare 'connected': send() controlla quel flag,
        // quindi invertirlo prima farebbe fallire silenziosamente l'invio.
        send("QUIT");
        connected = false;

        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}

        for (ClientListener l : listeners) {
            l.onDisconnected();
        }
    }
}
