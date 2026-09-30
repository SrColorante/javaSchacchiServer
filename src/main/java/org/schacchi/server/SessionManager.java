package org.schacchi.server;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gestisce l'insieme delle sessioni di gioco attive e la coda di matchmaking casuale.
 */
public class SessionManager {
    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();
    private final Queue<ConnectionHandler> matchmakingQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger roomCounter = new AtomicInteger(100);

    public GameSession createSession(String requestedRoomId, String roomName, ConnectionHandler host) {
        String sessionId = requestedRoomId;
        if (sessionId == null || sessionId.trim().isEmpty() || sessions.containsKey(sessionId)) {
            sessionId = "room_" + roomCounter.incrementAndGet();
        } else {
            sessionId = sessionId.trim();
        }

        GameSession session = new GameSession(sessionId, roomName, host);
        sessions.put(sessionId, session);
        Server.getInstance().notifySessionCreated(session);
        return session;
    }

    /**
     * Unisce un client a una stanza esistente.
     *
     * <p>Se la stanza e' in attesa, il client occupa il posto del secondo giocatore.
     * Se la partita e' gia' iniziata, il client entra come spettatore (il ruolo viene
     * deciso da {@link GameSession#addPlayer}). Solo una partita conclusa non accetta
     * nuovi arrivi.
     *
     * @return la stanza in cui il client e' entrato, oppure null se non e' piu' valida
     */
    public synchronized GameSession joinSession(String sessionId, ConnectionHandler guest) {
        if (sessionId == null) return null;
        GameSession session = sessions.get(sessionId.trim());
        if (session == null || session.getStatus() == GameSession.GameStatus.FINISHED) {
            return null;
        }
        session.addPlayer(guest);
        return session;
    }

    /**
     * Sistema di matchmaking rapido: se c'è un giocatore in attesa nella coda,
     * crea subito una sessione e abbina i due giocatori; altrimenti mette il giocatore in coda.
     */
    public synchronized GameSession quickMatch(ConnectionHandler player) {
        // Pulisce eventuali socket chiusi o disconnessi nella coda
        while (!matchmakingQueue.isEmpty()) {
            ConnectionHandler waiting = matchmakingQueue.poll();
            if (waiting != null && waiting.isConnected() && !waiting.equals(player)) {
                // Trovato un avversario disponibile!
                String matchId = "match_" + roomCounter.incrementAndGet();
                GameSession session = new GameSession(matchId, "Quick Match #" + roomCounter.get(), waiting);
                sessions.put(matchId, session);
                session.addPlayer(player);
                Server.getInstance().notifySessionCreated(session);
                return session;
            }
        }

        // Nessun avversario in attesa: inserisce in coda
        matchmakingQueue.add(player);
        player.sendMessage("INFO In attesa di un avversario nel matchmaking...");
        return null;
    }

    public void removeFromMatchmaking(ConnectionHandler player) {
        matchmakingQueue.remove(player);
    }

    public void removeSession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    public GameSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public Map<String, GameSession> getAllSessions() {
        return Collections.unmodifiableMap(sessions);
    }

    public List<GameSession> getOpenSessions() {
        List<GameSession> open = new ArrayList<>();
        for (GameSession s : sessions.values()) {
            if (s.getStatus() == GameSession.GameStatus.WAITING_FOR_OPPONENT) {
                open.add(s);
            }
        }
        return open;
    }

    public int getActiveSessionsCount() {
        int count = 0;
        for (GameSession s : sessions.values()) {
            if (s.getStatus() == GameSession.GameStatus.IN_PROGRESS) {
                count++;
            }
        }
        return count;
    }
}
