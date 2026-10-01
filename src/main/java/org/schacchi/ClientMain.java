package org.schacchi;

import org.schacchi.client.*;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Finestra del client: login, lobby e partita sono tre card sullo stesso sfondo.
 *
 * <p>Lo sfondo animato sta nel pannello radice invece che in una finestra separata:
 * cosi' un solo thread di disegno copre l'intera area e non si vede nessun bordo
 * fra una schermata e l'altra.
 */

/**
 * Applicazione Desktop Client per il gioco degli Scacchi Online (game.cristianrenosto.party).
 * Gestisce l'intero ciclo di vita:
 * 1. Login / Registrazione Account / Accesso Ospite
 * 2. Lobby con elenco stanze, matchmaking rapido, creazione stanza e lista amici
 * 3. Partita interattiva con scacchiera, chat, storico mosse e regole FIDE
 */
public class ClientMain extends JFrame implements ClientListener {
    private final CardLayout cardLayout = new CardLayout();
    private final ClientNetwork network = new ClientNetwork();

    /**
     * Non {@code final}: i campi blank final possono essere assegnati solo nel
     * costruttore o in un inizializzatore, e questo viene costruito in initUI().
     */
    private Glass.Backdrop backdrop;

    private LoginPanel loginPanel;
    private LobbyPanel lobbyPanel;
    private GameViewPanel gameViewPanel;

    private static final String CARD_LOGIN = "LOGIN";
    private static final String CARD_LOBBY = "LOBBY";
    private static final String CARD_GAME = "GAME";

    public ClientMain() {
        super("Scacchi - game.cristianrenosto.party");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1180, 780);
        setMinimumSize(new Dimension(920, 640));
        setLocationRelativeTo(null);
        getContentPane().setBackground(Glass.BG_DEEP);

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

        // Lo sfondo animato sta sotto tutte le schermate: e' il "dietro il vetro"
        // che le rende translucide.
        backdrop = new Glass.Backdrop(cardLayout);
        backdrop.add(loginPanel, CARD_LOGIN);
        backdrop.add(lobbyPanel, CARD_LOBBY);
        backdrop.add(gameViewPanel, CARD_GAME);
        backdrop.start();

        add(backdrop, BorderLayout.CENTER);
        showCard(CARD_LOGIN);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                backdrop.stop();
                network.disconnect();
            }
        });
    }

    private void showCard(String cardName) {
        SwingUtilities.invokeLater(() -> {
            cardLayout.show(backdrop, cardName);
            // Il colore di sfondo e' opaco, altrimenti la finestra resterebbe
            // grigia finche' Swing non ristampa il pannello radice.
            setBackground(Glass.BG_DEEP);
            getRootPane().repaint();
        });
    }

    // --- Implementazione ClientListener ---

    @Override
    public void onConnected() {
        SwingUtilities.invokeLater(() -> loginPanel.setStatusMessage("Connesso al server!", false));
    }

    @Override
    public void onDisconnected() {
        SwingUtilities.invokeLater(() -> {
            Dialogs.warn(this, "Disconnesso", "Connessione con il server persa.");
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
            // Con un account attivo diventano disponibili i diritti GDPR
            lobbyPanel.setAccountActionsEnabled(true);
            showCard(CARD_LOBBY);
            network.refreshRooms();
            network.refreshFriends();
        });
    }

    @Override
    public void onDataExported(String line) {
        SwingUtilities.invokeLater(() -> lobbyPanel.appendDataLine(line));
    }

    @Override
    public void onDataExportComplete(String summary) {
        SwingUtilities.invokeLater(() -> lobbyPanel.setPrivacyStatus(summary));
    }

    @Override
    public void onDataExportEmpty(String info) {
        SwingUtilities.invokeLater(() -> {
            lobbyPanel.setDataAreaText(info);
            lobbyPanel.setPrivacyStatus("Sei un ospite: il server non conserva dati su di te.");
        });
    }

    @Override
    public void onAccountDeleted(String message) {
        SwingUtilities.invokeLater(() -> {
            Dialogs.info(this, "Account cancellato", message);
            lobbyPanel.setDataAreaText("Account cancellato.\nI dati sono stati rimossi dal server.");
            lobbyPanel.setPrivacyStatus("Ora sei un ospite.");
            lobbyPanel.setAccountActionsEnabled(false);
        });
    }

    @Override
    public void onLogoutSuccess() {
        SwingUtilities.invokeLater(() -> {
            lobbyPanel.setPrivacyStatus("Sei ora un ospite. L'account non è più in uso.");
            lobbyPanel.setAccountActionsEnabled(false);
        });
    }

    @Override
    public void onRegisterSuccess(String username) {
        SwingUtilities.invokeLater(() -> {
            loginPanel.onRegistered(username);
        });
    }

    @Override
    public void onRoomCreated(String roomId, String color, String roomName) {
        SwingUtilities.invokeLater(() -> {
            // L'ID e' la cosa che l'utente deve condividere: viene mostrato
            // in grassetto e grande, non sepolto in un messaggio a capo.
            Dialogs.info(this, "Stanza creata",
                    "Nome: " + roomName + "\n\nID da condividere con l'amico:\n" + roomId
                    + "\n\nIn attesa del secondo giocatore...");
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
    public void onMoveHistoryReceived(List<String> moves) {
        SwingUtilities.invokeLater(() -> gameViewPanel.onMoveHistoryReceived(moves));
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
            Dialogs.info(this, "Amico aggiunto", " + friendName +  e' ora nella tua lista amici.");
        });
    }

    @Override
    public void onChatMessage(String sender, String message) {
        SwingUtilities.invokeLater(() -> gameViewPanel.appendChat(sender + ": " + message));
    }

    @Override
    public void onDrawOfferReceived() {
        SwingUtilities.invokeLater(() -> {
            if (Dialogs.confirm(this, "Offerta di patta", "L'avversario ti ha proposto una patta.\nAccetti?")) {
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
            Dialogs.error(this, "Errore", errorMessage);
        });
    }

    @Override
    public void onInfo(String infoMessage) {
        SwingUtilities.invokeLater(() -> {
            if (isGameViewVisible()) {
                gameViewPanel.appendChat("[INFO]: " + infoMessage);
            } else {
                loginPanel.setStatusMessage(infoMessage, false);
            }
        });
    }

    @Override
    public void onPong() {
        SwingUtilities.invokeLater(() -> loginPanel.setStatusMessage("Server raggiungibile.", false));
    }

    @Override
    public void onHelp(String helpText) {
        // Il testo di aiuto arriva due volte: all'avvio e a richiesta esplicita.
        // Non viene mostrato in un popup all'avvio per non coprire la schermata di login.
        if (helpText != null && helpText.length() > 0) {
            System.out.println("[SERVER HELP] " + helpText);
        }
    }

    @Override
    public void onServerShutdown(String reason) {
        SwingUtilities.invokeLater(() -> Dialogs.warn(this, "Server non disponibile",
                "Il server e' stato arrestato.\nRiavvia il server e poi riconnettiti."));
    }

    /**
     * @return true se il pannello di gioco è la schermata attualmente visibile
     */
    private boolean isGameViewVisible() {
        for (java.awt.Component component : backdrop.getComponents()) {
            if (component == gameViewPanel) {
                // In una CardLayout solo il card attivo è visibile: è sufficiente
                // controllarlo, senza interrogare la CardLayout su quale sia.
                return component.isVisible();
            }
        }
        return false;
    }

    public static void main(String[] args) {
        Glass.install();

        SwingUtilities.invokeLater(() -> {
            ClientMain client = new ClientMain();
            client.setVisible(true);
        });
    }
}
