package org.schacchi.server;

/**
 * Interfaccia per ascoltare gli eventi del Server di scacchi
 * (utilizzata per GUI, logging, monitoring o test).
 */
public interface ServerListener {
    default void onServerStarted(int port) {}
    default void onServerStopped() {}
    default void onClientConnected(ConnectionHandler client) {}
    default void onClientDisconnected(ConnectionHandler client) {}
    default void onSessionCreated(GameSession session) {}
    default void onSessionStarted(GameSession session) {}
    default void onMoveMade(GameSession session, String move, String fen) {}
    default void onSessionEnded(GameSession session, String reason) {}
    default void onLog(String message) {}
}
