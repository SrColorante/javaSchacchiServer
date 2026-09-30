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
}
