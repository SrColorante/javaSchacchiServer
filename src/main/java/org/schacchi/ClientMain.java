package org.schacchi;

import org.schacchi.client.*;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Applicazione Desktop Client per il gioco degli Scacchi Online (game.cristianrenosto.party).
 * Gestisce l'intero ciclo di vita:
 * 1. Login / Registrazione Account / Accesso Ospite
 * 2. Lobby con elenco stanze, matchmaking rapido, creazione stanza e lista amici
 * 3. Partita interattiva con scacchiera, chat, storico mosse e regole FIDE
 */
public class ClientMain extends JFrame implements ClientListener {
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardsPanel = new JPanel(cardLayout);

    private final ClientNetwork network = new ClientNetwork();

    private LoginPanel loginPanel;
    private LobbyPanel lobbyPanel;
    private GameViewPanel gameViewPanel;

    private static final String CARD_LOGIN = "LOGIN";
    private static final String CARD_LOBBY = "LOBBY";
    private static final String CARD_GAME = "GAME";

    public ClientMain() {
        super("Scacchi Online - [game.cristianrenosto.party]");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(980, 720);
        setMinimumSize(new Dimension(800, 600));
        setLocationRelativeTo(null);

        network.addListener(this);

        initUI();
    }

    private void initUI() {
        loginPanel = new LoginPanel(network, () -> showCard(CARD_LOBBY));
        lobbyPanel = new LobbyPanel(network);
        gameViewPanel = new GameViewPanel(network, () -> {
            showCard(CARD_LOBBY);
            network.refreshRooms();
            network.refreshFriends();
        });

        cardsPanel.add(loginPanel, CARD_LOGIN);
        cardsPanel.add(lobbyPanel, CARD_LOBBY);
        cardsPanel.add(gameViewPanel, CARD_GAME);

        add(cardsPanel, BorderLayout.CENTER);
        showCard(CARD_LOGIN);

        // Chiusura pulita della connessione alla chiusura della finestra
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                network.disconnect();
            }
        });
    }

    private void showCard(String cardName) {
        SwingUtilities.invokeLater(() -> cardLayout.show(cardsPanel, cardName));
    }

    // --- Implementazione ClientListener ---

    @Override
    public void onConnected() {
        SwingUtilities.invokeLater(() -> loginPanel.setStatusMessage("Connesso al server!", false));
    }

    @Override
    public void onDisconnected() {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, "Connessione con il server persa.", "Disconnesso", JOptionPane.WARNING_MESSAGE);
            showCard(CARD_LOGIN);
        });
    }

    @Override
    public void onConnectionFailed(String error) {
        SwingUtilities.invokeLater(() -> loginPanel.setStatusMessage(error, true));
    }

    @Override
    public void onLoginSuccess(String username, int elo, int wins, int losses, int draws) {
        SwingUtilities.invokeLater(() -> {
            lobbyPanel.updateUserInfo(username, elo, wins, losses, draws);
            showCard(CARD_LOBBY);
            network.refreshRooms();
            network.refreshFriends();
        });
    }

    @Override
    public void onRegisterSuccess(String username) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, "Account '" + username + "' registrato con successo!\nOra puoi effettuare l'accesso.", "Registrazione Completata", JOptionPane.INFORMATION_MESSAGE);
            loginPanel.setStatusMessage("Account creato! Effettua il login.", false);
        });
    }

    @Override
    public void onRoomCreated(String roomId, String color, String roomName) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this,
                    "Stanza creata con successo!\n" +
                    "Nome: " + roomName + "\n" +
                    "ID Stanza da condividere con l'amico: " + roomId + "\n" +
                    "In attesa del secondo giocatore...",
                    "Stanza Creata", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    @Override
    public void onGameStart(String roomId, String yourColor, String opponentName) {
        SwingUtilities.invokeLater(() -> {
            gameViewPanel.startNewGame(roomId, yourColor, opponentName);
            showCard(CARD_GAME);
        });
    }

    @Override
    public void onMoveExecuted(String uciMove, String fen, boolean isMine) {
        SwingUtilities.invokeLater(() -> gameViewPanel.onMoveExecuted(uciMove, fen, isMine));
    }

    @Override
    public void onCheck(String colorInCheck) {
        SwingUtilities.invokeLater(() -> {
            gameViewPanel.appendChat("[SERVER]: Scacco al Re " + colorInCheck + "!");
        });
    }

    @Override
    public void onGameOver(String winner, String reason) {
        SwingUtilities.invokeLater(() -> gameViewPanel.onGameOver(winner, reason));
    }

    @Override
    public void onSpectating(String roomId, String fen) {
        SwingUtilities.invokeLater(() -> {
            gameViewPanel.startSpectating(roomId, fen);
            showCard(CARD_GAME);
        });
    }

    @Override
    public void onBoardSynced(String fen, boolean isMine) {
        SwingUtilities.invokeLater(() -> gameViewPanel.onBoardSynced(fen, isMine));
    }

    @Override
    public void onRoomListUpdated(List<RoomInfo> rooms) {
        SwingUtilities.invokeLater(() -> lobbyPanel.updateRoomList(rooms));
    }

    @Override
    public void onFriendsListUpdated(List<FriendInfo> friends) {
        SwingUtilities.invokeLater(() -> lobbyPanel.updateFriendsList(friends));
    }

    @Override
    public void onFriendAdded(String friendName) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, "Utente '" + friendName + "' aggiunto agli amici!", "Amico Aggiunto", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    @Override
    public void onChatMessage(String sender, String message) {
        SwingUtilities.invokeLater(() -> gameViewPanel.appendChat(sender + ": " + message));
    }

    @Override
    public void onDrawOfferReceived() {
        SwingUtilities.invokeLater(() -> {
            int res = JOptionPane.showConfirmDialog(this, "L'avversario ti ha proposto una patta.\nAccetti?", "Offerta di Patta", JOptionPane.YES_NO_OPTION);
            if (res == JOptionPane.YES_OPTION) {
                network.acceptDraw();
            } else {
                network.declineDraw();
            }
        });
    }

    @Override
    public void onDrawDeclined() {
        SwingUtilities.invokeLater(() -> {
            gameViewPanel.appendChat("[SERVER]: L'avversario ha rifiutato l'offerta di patta.");
        });
    }

    @Override
    public void onError(String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, errorMessage, "Errore", JOptionPane.ERROR_MESSAGE);
        });
    }

    @Override
    public void onInfo(String infoMessage) {
        SwingUtilities.invokeLater(() -> {
            if (gameViewPanel != null && cardsPanel.getComponent(2).isVisible()) {
                gameViewPanel.appendChat("[INFO]: " + infoMessage);
            }
        });
    }

    public static void main(String[] args) {
        // Look & Feel moderno di sistema
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            ClientMain client = new ClientMain();
            client.setVisible(true);
        });
    }
}
