package org.schacchi.client;

import org.schacchi.model.ChessBoard;
import org.schacchi.model.Move;
import org.schacchi.model.PieceColor;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * Schermata di partita: scacchiera, barre dei giocatori, storico mosse e chat.
 *
 * <p>Chat e storico stanno in tab per non rubare spazio alla scacchiera, che e' il
 * motivo per cui l'utente ha aperto questa finestra.
 */
public class GameViewPanel extends JPanel {
    private final ClientNetwork network;
    private final ChessBoardPanel boardPanel;
    private final Runnable onLeaveCallback;

    private JLabel lblOpponentName;
    private JLabel lblPlayerName;
    private JLabel lblTurnIndicator;
    private JComponent chipOpponent;
    private JComponent chipPlayer;
    private Glass.Area moveHistoryArea;
    private Glass.Area chatArea;
    private Glass.Field chatInputField;

    private String currentRoomId = "";
    private PieceColor myColor = PieceColor.WHITE;
    private String opponentName = "Avversario";
    private boolean spectating = false;

    public GameViewPanel(ClientNetwork network, Runnable onLeaveCallback) {
        this.network = network;
        this.onLeaveCallback = onLeaveCallback;
        this.boardPanel = new ChessBoardPanel();

        setOpaque(false);
        setLayout(new BorderLayout(Glass.GAP, Glass.GAP));
        setBorder(new EmptyBorder(Glass.GAP, Glass.GAP, Glass.GAP, Glass.GAP));

        boardPanel.setMoveListener(move -> network.sendMove(move.toUci()));

        add(createBoardColumn(), BorderLayout.CENTER);
        add(createSidebar(), BorderLayout.EAST);
    }

    // ---------- Colonna centrale ----------

    private JComponent createBoardColumn() {
        JPanel column = Glass.row(new BorderLayout(0, 10), 10);

        JPanel opponentBar = createPlayerBar(false);
        column.add(opponentBar, BorderLayout.NORTH);

        // Il riquadro di vetro lo disegna ChessBoardPanel stesso, stringendosi
        // attorno al quadrato: un pannello separato che occupa tutto lo spazio
        // lascerebbe una cornice enorme ai lati della scacchiera.
        column.add(boardPanel, BorderLayout.CENTER);

        column.add(createPlayerBar(true), BorderLayout.SOUTH);
        return column;
    }

    private Glass.Panel createPlayerBar(boolean isLocalPlayer) {
        Glass.Panel bar = new Glass.Panel(new BorderLayout(12, 0), Glass.RADIUS_SM, Glass.SURFACE, true);
        bar.setBorder(new EmptyBorder(10, 14, 10, 14));
        bar.setPreferredSize(new Dimension(10, 48));
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));

        JLabel nameLabel = Glass.label(isLocalPlayer ? "Tu" : "In attesa dell'avversario...", 14, Font.BOLD, Glass.TEXT);
        JLabel statusLabel = Glass.label("", 12, Font.PLAIN, Glass.TEXT_DIM);

        // Pastiglia del colore: dice subito chi muove senza dover leggere il nome.
        JPanel chip = new Glass.Panel(new GridBagLayout(), 8, Glass.alpha(Color.WHITE, 40), false);
        chip.setPreferredSize(new Dimension(14, 14));
        chip.setMinimumSize(new Dimension(14, 14));

        if (isLocalPlayer) {
            this.lblPlayerName = nameLabel;
            this.lblTurnIndicator = statusLabel;
            this.chipPlayer = chip;
        } else {
            this.lblOpponentName = nameLabel;
            this.chipOpponent = chip;
        }

        JPanel chipBox = Glass.row(new BorderLayout(8, 0), 8);
        chipBox.setOpaque(false);
        chipBox.add(chip, BorderLayout.WEST);
        chipBox.add(nameLabel, BorderLayout.CENTER);

        bar.add(chipBox, BorderLayout.CENTER);
        bar.add(statusLabel, BorderLayout.EAST);
        return bar;
    }

    /** Riempie la pastiglia col colore del pezzo: bianco pieno o scuro con bordo chiaro. */
    private static void tintChip(JComponent chip, boolean white) {
        if (chip instanceof Glass.Panel panel) {
            panel.setSurface(white ? Glass.alpha(Color.WHITE, 235) : Glass.alpha(Glass.BG_DEEP, 220));
            panel.setOutlined(!white);
        }
    }

    // ---------- Sidebar ----------

    private JComponent createSidebar() {
        Glass.Panel sidebar = new Glass.Panel(new BorderLayout(0, 12), Glass.RADIUS, Glass.SURFACE, true);
        sidebar.setPreferredSize(new Dimension(320, 10));

        Glass.Tabs tabs = new Glass.Tabs();
        tabs.addTab("Chat", createChatTab());
        tabs.addTab("Mosse", createHistoryTab());
        sidebar.add(tabs, BorderLayout.CENTER);

        sidebar.add(createActionsBar(), BorderLayout.SOUTH);
        return sidebar;
    }

    private JComponent createChatTab() {
        JPanel panel = Glass.row(new BorderLayout(0, 10), 0);

        chatArea = new Glass.Area();
        chatArea.setFont(Glass.sans(13, Font.PLAIN));
        panel.add(scroll(chatArea), BorderLayout.CENTER);

        JPanel inputRow = Glass.row(new BorderLayout(8, 0), 8);
        chatInputField = new Glass.Field("Scrivi un messaggio");
        chatInputField.addActionListener(e -> sendChat());
        inputRow.add(chatInputField, BorderLayout.CENTER);

        Glass.Button send = new Glass.Button("Invia", Glass.Button.Kind.PRIMARY);
        send.addActionListener(e -> sendChat());
        inputRow.add(send, BorderLayout.EAST);
        panel.add(inputRow, BorderLayout.SOUTH);

        return panel;
    }

    private JComponent createHistoryTab() {
        JPanel panel = Glass.row(new BorderLayout(), 0);
        moveHistoryArea = new Glass.Area();
        moveHistoryArea.setEditable(false);
        moveHistoryArea.setFont(Glass.mono(13, Font.PLAIN));
        moveHistoryArea.setLineWrap(false);
        panel.add(scroll(moveHistoryArea), BorderLayout.CENTER);
        return panel;
    }

    private JComponent createActionsBar() {
        JPanel actions = Glass.row(new GridLayout(1, 3, 8, 0), 8);

        Glass.Button draw = new Glass.Button("Patta", Glass.Button.Kind.GHOST);
        draw.setToolTipText("Offri una patta all'avversario");
        draw.addActionListener(e -> {
            if (Dialogs.confirm(this, "Offerta patta", "Vuoi proporre la patta?")) {
                network.offerDraw();
                appendChat("Tu: [Hai offerto la patta]");
            }
        });

        Glass.Button resign = new Glass.Button("Arrenditi", Glass.Button.Kind.DANGER);
        resign.addActionListener(e -> {
            if (Dialogs.confirm(this, "Resa partita", "Sei sicuro di volerti arrendere?",
                    Glass.Button.Kind.DANGER)) {
                network.resign();
            }
        });

        Glass.Button leave = new Glass.Button("Esci", Glass.Button.Kind.GHOST);
        leave.setToolTipText("Abbandona la stanza");
        leave.addActionListener(e -> {
            // Essere in partita, uscire equivale a un abbandono: va detto chiaramente,
            // altrimenti l'utente pensa di tornare semplicemente in lobby.
            if (Dialogs.confirm(this, "Abbandona partita",
                    "Abbandonando perdi la partita e l'avversario riceve la vittoria per forfeit.\n"
                            + "Vuoi davvero uscire?", Glass.Button.Kind.DANGER)) {
                network.leaveRoom();
                if (onLeaveCallback != null) onLeaveCallback.run();
            }
        });

        actions.add(draw);
        actions.add(resign);
        actions.add(leave);
        return actions;
    }

    private static JScrollPane scroll(Component view) {
        JScrollPane s = new JScrollPane(view);
        s.setOpaque(false);
        s.getViewport().setOpaque(false);
        s.setBorder(new EmptyBorder(0, 0, 0, 0));
        s.getVerticalScrollBar().setOpaque(false);
        s.getHorizontalScrollBar().setOpaque(false);
        return s;
    }

    private void sendChat() {
        String msg = chatInputField.getText().trim();
        if (!msg.isEmpty()) {
            network.sendChat(msg);
            chatInputField.setText("");
        }
    }

    // ---------- API pubblica (invariata) ----------

    public void startNewGame(String roomId, String colorStr, String opponent) {
        this.currentRoomId = roomId;
        this.myColor = "WHITE".equalsIgnoreCase(colorStr) ? PieceColor.WHITE : PieceColor.BLACK;
        this.opponentName = opponent;
        this.spectating = false;

        boardPanel.setBoard(new ChessBoard());
        boardPanel.setLastMove(null);
        boardPanel.setPerspective(myColor);
        boardPanel.setInteractive(true);

        lblPlayerName.setText(network.getCurrentUsername() + "  (" + (myColor == PieceColor.WHITE ? "Bianco" : "Nero") + ")");
        lblOpponentName.setText(opponent + "  (" + (myColor == PieceColor.WHITE ? "Nero" : "Bianco") + ")");
        tintChip(chipPlayer, myColor == PieceColor.WHITE);
        tintChip(chipOpponent, myColor != PieceColor.WHITE);
        updateTurnIndicator();

        moveHistoryArea.setText("");
        chatArea.setText("Partita iniziata nella stanza " + roomId + "\n");
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
        tintChip(chipPlayer, true);
        tintChip(chipOpponent, false);
        lblTurnIndicator.setText("Modalita' spettatore");
        lblTurnIndicator.setForeground(Glass.TEXT_FAINT);

        moveHistoryArea.setText("");
        chatArea.setText("Stai assistendo alla partita " + roomId + "\n");

        applyServerFen(fen);
    }

    /**
     * Mostra lo storico delle mosse inviate dal server a uno spettatore che entra
     * a meta' partita: senza, la scacchiera mostrerebbe i pezzi gia' spostati senza
     * alcuna traccia di come ci si e' arrivati.
     */
    public void onMoveHistoryReceived(List<String> moves) {
        if (moves == null || moves.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        int moveNumber = 1;
        for (String uci : moves) {
            if (moveNumber % 2 == 1) sb.append(moveNumber).append(".  ");
            sb.append(uci).append("   ");
            if (moveNumber % 2 == 0) sb.append('\n');
            moveNumber++;
        }
        if (moveNumber % 2 == 1) sb.append('\n');
        moveHistoryArea.setText(sb.toString());
        moveHistoryArea.setCaretPosition(0);
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

        updateTurnIndicator();

        // Aggiungi a storico
        moveHistoryArea.append(uciMove + "   ");
        if (boardPanel.getBoard().getTurn() == PieceColor.WHITE) {
            moveHistoryArea.append("\n");
        }
    }

    private void updateTurnIndicator() {
        if (spectating) {
            lblTurnIndicator.setText("Modalita' spettatore");
            lblTurnIndicator.setForeground(Glass.TEXT_FAINT);
            return;
        }
        boolean isMyTurn = boardPanel.getBoard().getTurn() == myColor;
        lblTurnIndicator.setText(isMyTurn ? "Il tuo turno" : "Turno dell'avversario");
        lblTurnIndicator.setForeground(isMyTurn ? Glass.SUCCESS : Glass.TEXT_DIM);
    }

    /** Applica una posizione FEN inviata dal server (comando BOARD/FEN). */
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
        lblTurnIndicator.setText("Partita conclusa: " + winner);
        lblTurnIndicator.setForeground(Glass.WARNING);

        Dialogs.info(this, "Fine partita", winner + "\n" + reason);
    }
}
