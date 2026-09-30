package org.schacchi.client;

import java.util.List;

/**
 * Callback per gli eventi inviati dal Server di scacchi al Client.
 */
public interface ClientListener {
    default void onConnected() {}
    default void onDisconnected() {}
    default void onConnectionFailed(String error) {}

    default void onLoginSuccess(String username, int elo, int wins, int losses, int draws) {}
    default void onRegisterSuccess(String username) {}
    default void onLogoutSuccess() {}
    /** Una riga dei dati personali dell'utente (GDPR art. 15 e 20). */
    default void onDataExported(String line) {}
    default void onDataExportComplete(String summary) {}
    /** L'utente è ospite: il server non conserva dati su di lui. */
    default void onDataExportEmpty(String info) {}
    /** L'account è stato cancellato (GDPR art. 17). */
    default void onAccountDeleted(String username) {}
    default void onError(String errorMessage) {}
    default void onInfo(String infoMessage) {}

    default void onRoomCreated(String roomId, String color, String roomName) {}
    default void onGameStart(String roomId, String yourColor, String opponentName) {}
    default void onMoveExecuted(String uciMove, String fen, boolean isMine) {}
    default void onCheck(String colorInCheck) {}
    default void onGameOver(String winner, String reason) {}

    /** La stanza era piena: questo client si mette in modalita' spettatore. */
    default void onSpectating(String roomId, String fen) {}
    /** Il server ha inviato una posizione FEN autorevole da cui risincronizzare la scacchiera. */
    default void onBoardSynced(String fen, boolean isMine) {}

    default void onRoomListUpdated(List<RoomInfo> rooms) {}
    default void onFriendsListUpdated(List<FriendInfo> friends) {}
    default void onFriendAdded(String friendName) {}
    default void onChatMessage(String sender, String message) {}
    default void onDrawOfferReceived() {}
    default void onDrawDeclined() {}

    /** Risposta a PING: la connessione e' viva. */
    default void onPong() {}
    /** Storico delle mosse inviato a uno spettatore che entra a meta' partita. */
    default void onMoveHistoryReceived(List<String> moves) {}
    /** Testo di aiuto del server, ricevuto all'avvio o con il comando HELP. */
    default void onHelp(String helpText) {}
    /** Il server sta per arrestarsi: la connessione verra' chiusa. */
    default void onServerShutdown(String reason) {}
}
