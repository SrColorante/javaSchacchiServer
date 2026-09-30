package org.schacchi.client;

import org.schacchi.model.ChessBoard;
import org.schacchi.model.Move;
import org.schacchi.model.PieceColor;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Schermata di gioco attiva contenente la scacchiera, i pannelli giocatori,
 * lo storico delle mosse, la chat in tempo reale e i controlli partita (resa, patta, abbandona).
 */
public class GameViewPanel extends JPanel {
    private final ClientNetwork network;
    private final ChessBoardPanel boardPanel;
    private final Runnable onLeaveCallback;

    private JLabel lblOpponentName;
    private JLabel lblPlayerName;
    private JLabel lblTurnIndicator;
    private JTextArea moveHistoryArea;
    private JTextArea chatArea;
    private JTextField chatInputField;

    private String currentRoomId = "";
    private PieceColor myColor = PieceColor.WHITE;
    private String opponentName = "Avversario";
    private boolean spectating = false;

    public GameViewPanel(ClientNetwork network, Runnable onLeaveCallback) {
        this.network = network;
        this.onLeaveCallback = onLeaveCallback;
        this.boardPanel = new ChessBoardPanel();

        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(10, 10, 10, 10));
        setBackground(new Color(24, 25, 28));

        // Listener per le mosse generate sulla scacchiera
        boardPanel.setMoveListener(move -> {
            network.sendMove(move.toUci());
        });

        // Pannello sinistro / centrale: Giocatori + Scacchiera
        JPanel gameCenterPanel = new JPanel(new BorderLayout(5, 5));
        gameCenterPanel.setOpaque(false);

        // Barra avversario (in alto)
        JPanel opponentPanel = createPlayerBar(false);
        gameCenterPanel.add(opponentPanel, BorderLayout.NORTH);

        // Scacchiera al centro (centrata)
        JPanel boardContainer = new JPanel(new GridBagLayout());
        boardContainer.setOpaque(false);
        boardContainer.add(boardPanel);
        gameCenterPanel.add(boardContainer, BorderLayout.CENTER);

        // Barra giocatore locale (in basso)
        JPanel playerPanel = createPlayerBar(true);
        gameCenterPanel.add(playerPanel, BorderLayout.SOUTH);

        add(gameCenterPanel, BorderLayout.CENTER);

        // Sidebar destra: Storico mosse + Chat + Azioni
        JPanel sidebar = createSidebar();
        add(sidebar, BorderLayout.EAST);
    }

    private JPanel createPlayerBar(boolean isLocalPlayer) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(new Color(36, 38, 43));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(50, 52, 60), 1),
                new EmptyBorder(8, 12, 8, 12)
        ));

        JLabel nameLabel = new JLabel(isLocalPlayer ? "Tu" : "In attesa dell'avversario...");
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(new Font("SansSerif", Font.BOLD, 14));

        JLabel statusLabel = new JLabel(isLocalPlayer ? "(Bianco)" : "");
        statusLabel.setForeground(new Color(170, 175, 190));
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));

        if (isLocalPlayer) {
            this.lblPlayerName = nameLabel;
            this.lblTurnIndicator = statusLabel;
        } else {
            this.lblOpponentName = nameLabel;
        }

        bar.add(nameLabel, BorderLayout.WEST);
        bar.add(statusLabel, BorderLayout.EAST);
        return bar;
    }

    private JPanel createSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(8, 8));
        sidebar.setPreferredSize(new Dimension(300, 550));
        sidebar.setOpaque(false);

        // Storico mosse in alto
        JPanel historyPanel = new JPanel(new BorderLayout());
        historyPanel.setOpaque(false);
        historyPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(70, 75, 85)),
                "Storico Mosse", 0, 0, new Font("SansSerif", Font.BOLD, 12), Color.LIGHT_GRAY
        ));

        moveHistoryArea = new JTextArea();
        moveHistoryArea.setEditable(false);
        moveHistoryArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        moveHistoryArea.setBackground(new Color(32, 34, 38));
        moveHistoryArea.setForeground(new Color(225, 225, 230));
        JScrollPane historyScroll = new JScrollPane(moveHistoryArea);
        historyScroll.setPreferredSize(new Dimension(280, 160));
        historyPanel.add(historyScroll, BorderLayout.CENTER);

        // Chat al centro
        JPanel chatPanel = new JPanel(new BorderLayout(4, 4));
        chatPanel.setOpaque(false);
        chatPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(70, 75, 85)),
                "Chat Partita", 0, 0, new Font("SansSerif", Font.BOLD, 12), Color.LIGHT_GRAY
        ));

        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setFont(new Font("SansSerif", Font.PLAIN, 12));
        chatArea.setBackground(new Color(32, 34, 38));
        chatArea.setForeground(new Color(210, 215, 225));
        JScrollPane chatScroll = new JScrollPane(chatArea);
        chatPanel.add(chatScroll, BorderLayout.CENTER);

        JPanel chatInputBox = new JPanel(new BorderLayout(4, 4));
        chatInputBox.setOpaque(false);
        chatInputField = new JTextField();
        chatInputField.addActionListener(e -> sendChat());
        JButton btnSendChat = new JButton("Invia");
        btnSendChat.addActionListener(e -> sendChat());

        chatInputBox.add(chatInputField, BorderLayout.CENTER);
        chatInputBox.add(btnSendChat, BorderLayout.EAST);
        chatPanel.add(chatInputBox, BorderLayout.SOUTH);

        // Bottoni azione in basso
        JPanel actionsPanel = new JPanel(new GridLayout(1, 3, 6, 6));
        actionsPanel.setOpaque(false);

        JButton btnDraw = new JButton("Patta");
        btnDraw.setToolTipText("Offri una patta all'avversario");
        btnDraw.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(this, "Vuoi proporre la patta?", "Offerta Patta", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                network.offerDraw();
                appendChat("Tu: [Hai offerto la patta]");
            }
        });

        JButton btnResign = new JButton("Arrenditi");
        btnResign.setForeground(new Color(230, 80, 80));
        btnResign.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(this, "Sei sicuro di volerti arrendere?", "Resa Partita", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                network.resign();
            }
        });

        JButton btnLeave = new JButton("Esci");
        btnLeave.setToolTipText("Abbandona la stanza");
        btnLeave.addActionListener(e -> {
            // Essere in partita, uscire equivale a un abbandono: va detto chiaramente,
            // altrimenti l'utente pensa di tornare semplicemente in lobby.
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Abbandonando perdi la partita e l'avversario riceve la vittoria per forfeit.\nVuoi davvero uscire?",
                    "Abbandona Partita", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                network.leaveRoom();
                if (onLeaveCallback != null) onLeaveCallback.run();
            }
        });

        actionsPanel.add(btnDraw);
        actionsPanel.add(btnResign);
        actionsPanel.add(btnLeave);

        // Assegna parti alla sidebar
        JPanel topSide = new JPanel(new BorderLayout(6, 6));
        topSide.setOpaque(false);
        topSide.add(historyPanel, BorderLayout.NORTH);
        topSide.add(chatPanel, BorderLayout.CENTER);

        sidebar.add(topSide, BorderLayout.CENTER);
        sidebar.add(actionsPanel, BorderLayout.SOUTH);

        return sidebar;
    }

    private void sendChat() {
        String msg = chatInputField.getText().trim();
        if (!msg.isEmpty()) {
            network.sendChat(msg);
            chatInputField.setText("");
        }
    }

    public void startNewGame(String roomId, String colorStr, String opponent) {
        this.currentRoomId = roomId;
        this.myColor = "WHITE".equalsIgnoreCase(colorStr) ? PieceColor.WHITE : PieceColor.BLACK;
        this.opponentName = opponent;
        this.spectating = false;

        boardPanel.setBoard(new ChessBoard());
        boardPanel.setLastMove(null);
        boardPanel.setPerspective(myColor);
        boardPanel.setInteractive(true);

        lblPlayerName.setText(network.getCurrentUsername() + " (" + (myColor == PieceColor.WHITE ? "Bianco" : "Nero") + ")");
        lblOpponentName.setText(opponent + " (" + (myColor == PieceColor.WHITE ? "Nero" : "Bianco") + ")");
        lblTurnIndicator.setText(boardPanel.getBoard().getTurn() == myColor ? ">> IL TUO TURNO <<" : "Turno dell'avversario...");
        lblTurnIndicator.setForeground(boardPanel.getBoard().getTurn() == myColor
                ? new Color(100, 240, 120) : new Color(170, 175, 190));

        moveHistoryArea.setText("");
        chatArea.setText("--- Nuova Partita Iniziata in stanza " + roomId + " ---\n");
    }

    /**
     * Mostra una partita in cui questo client sta solo assistendo: la scacchiera
     * e' sincronizzata dal server e i controlli di gioco non sono utilizzabili.
     */
    public void startSpectating(String roomId, String fen) {
        this.currentRoomId = roomId;
        this.spectating = true;
        this.myColor = PieceColor.WHITE;
        this.opponentName = "Partita in corso";

        boardPanel.setBoard(new ChessBoard());
        boardPanel.setLastMove(null);
        boardPanel.setPerspective(PieceColor.WHITE);
        boardPanel.setInteractive(false);

        lblPlayerName.setText("Osservatore");
        lblOpponentName.setText("Stanza " + roomId);
        lblTurnIndicator.setText("Modalita' spettatore");
        lblTurnIndicator.setForeground(Color.GRAY);

        moveHistoryArea.setText("");
        chatArea.setText("--- Stai assistendo alla partita " + roomId + " ---\n");

        applyServerFen(fen);
    }

    /**
     * Risincronizza la scacchiera sulla posizione FEN autorevole del server.
     * Il client non riapplica le mosse in locale: se per qualsiasi motivo il suo stato
     * divergesse da quello del server, ricalcolerebbe mosse diverse da quelle reali.
     *
     * @return true se il FEN e' stato applicato
     */
    private boolean applyServerFen(String fen) {
        if (fen == null || fen.isBlank()) return false;
        try {
            boardPanel.setBoard(ChessBoard.fromFen(fen.trim()));
            boardPanel.repaint();
            return true;
        } catch (IllegalArgumentException e) {
            System.err.println("FEN non valido dal server: " + fen + " (" + e.getMessage() + ")");
            return false;
        }
    }

    public void onMoveExecuted(String uciMove, String fen, boolean isMine) {
        // La posizione autorevole arriva sempre dal server: si usa quella.
        // Il FEN contiene gia' l'ultima mossa, quindi non va riapplicata in locale.
        if (!applyServerFen(fen)) {
            // Fallback solo se il server non ha inviato un FEN utilizzabile:
            // in quel caso si replica la mossa sulla scacchiera locale.
            try {
                Move move = Move.fromUci(uciMove);
                if (!boardPanel.getBoard().makeMove(move)) {
                    System.err.println("Mossa locale non applicabile: " + uciMove);
                }
                boardPanel.setLastMove(move);
            } catch (Exception e) {
                System.err.println("Errore aggiornamento mossa " + uciMove + ": " + e.getMessage());
                return;
            }
        } else {
            try {
                boardPanel.setLastMove(Move.fromUci(uciMove));
            } catch (Exception ignored) {
                // Solo l'evidenziazione dell'ultima mossa: senza, la partita prosegue
            }
        }

        // Aggiorna indicatore turno
        boolean isMyTurn = (boardPanel.getBoard().getTurn() == myColor) && !spectating;
        lblTurnIndicator.setText(spectating
                ? "Modalita' spettatore"
                : (isMyTurn ? ">> IL TUO TURNO <<" : "Turno dell'avversario..."));
        lblTurnIndicator.setForeground(spectating
                ? Color.GRAY
                : (isMyTurn ? new Color(100, 240, 120) : new Color(170, 175, 190)));

        // Aggiungi a storico
        moveHistoryArea.append(uciMove + "  ");
        if (boardPanel.getBoard().getTurn() == PieceColor.WHITE) {
            moveHistoryArea.append("\n");
        }
    }

    /**
     * Applica una posizione FEN inviata dal server (comando BOARD/FEN) senza
     * modificarne lo storico delle mosse.
     */
    public void onBoardSynced(String fen, boolean isMine) {
        if (applyServerFen(fen)) {
            boardPanel.repaint();
        }
    }

    public void appendChat(String message) {
        chatArea.append(message + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    public void onGameOver(String winner, String reason) {
        boardPanel.setInteractive(false);
        lblTurnIndicator.setText("PARTITA TERMINATA: " + winner);
        lblTurnIndicator.setForeground(Color.ORANGE);

        String message = "Partita conclusa!\nEsito: " + winner + "\nMotivo: " + reason;
        JOptionPane.showMessageDialog(this, message, "Fine Partita", JOptionPane.INFORMATION_MESSAGE);
    }
}
