package org.schacchi.client;

import org.schacchi.model.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Componente grafico interattivo per la scacchiera 8x8.
 * - Adatta la prospettiva (Bianco in basso o Nero in basso se si gioca con il Nero)
 * - Evidenzia la casella selezionata, l'ultima mossa e le mosse legali con indicatori
 * - Evidenzia il Re sotto scacco
 * - Visualizza coordinate su bordi caselle (stile Chess.com / Lichess)
 * - Gestisce la promozione del pedone tramite dialogo interattivo
 */
public class ChessBoardPanel extends JPanel {
    private ChessBoard board = new ChessBoard();
    private PieceColor perspective = PieceColor.WHITE;
    private boolean interactive = true;

    private Position selectedPosition = null;
    private List<Move> legalMovesForSelected = new ArrayList<>();
    private Move lastMove = null;

    private Consumer<Move> moveListener;

    // Evidenziazioni: ambrate e semitrasparenti, cosi' non spengono la scacchiera
    // ne' i pezzi bianchi che su una casella chiara sparirebbero.
    private static final Color COLOR_SELECTED = Glass.alpha(new Color(0xFFD166), 120);
    private static final Color COLOR_LAST_MOVE = Glass.alpha(new Color(0xF0C674), 110);
    private static final Color COLOR_CHECK = Glass.alpha(new Color(0xFF6B6B), 170);
    private static final Color COLOR_LEGAL_HINT = Glass.alpha(new Color(0x0B0D14), 70);
    private static final Color COLOR_LEGAL_CAPTURE = Glass.alpha(new Color(0x0B0D14), 90);

    public ChessBoardPanel() {
        setPreferredSize(new Dimension(592, 592));
        setMinimumSize(new Dimension(320, 320));
        setOpaque(false);

        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!interactive) return;
                handleSquareClick(e.getX(), e.getY());
            }
        };
        addMouseListener(mouseHandler);
    }

    public void setMoveListener(Consumer<Move> listener) {
        this.moveListener = listener;
    }

    public void setPerspective(PieceColor color) {
        this.perspective = (color != null) ? color : PieceColor.WHITE;
        repaint();
    }

    public PieceColor getPerspective() {
        return perspective;
    }

    public void setInteractive(boolean interactive) {
        this.interactive = interactive;
    }

    public ChessBoard getBoard() {
        return board;
    }

    public void setBoard(ChessBoard board) {
        this.board = board;
        this.selectedPosition = null;
        this.legalMovesForSelected.clear();
        repaint();
    }

    public void setLastMove(Move move) {
        this.lastMove = move;
        repaint();
    }

    /**
     * Mappa coordinate grafiche (pixel) nella Position della scacchiera logica.
     */
    private Position getSquareAt(int px, int py) {
        int squareSize = getSquareSize();
        if (squareSize <= 0) return null;

        // Si sottraggono le origini centrate: senza, un clic nelle margini non
        // troverebbe mai la casella giusta.
        int colDisplay = (px - getOriginX()) / squareSize;
        int rowDisplay = (py - getOriginY()) / squareSize;

        if (colDisplay < 0 || colDisplay >= 8 || rowDisplay < 0 || rowDisplay >= 8) {
            return null;
        }

        int row = (perspective == PieceColor.WHITE) ? rowDisplay : (7 - rowDisplay);
        int col = (perspective == PieceColor.WHITE) ? colDisplay : (7 - colDisplay);

        return Position.of(row, col);
    }

    /**
     * Lato della casella in pixel, arrotondato a floor.
     *
     * <p>Il resto della griglia viene ricavato da questo valore invece di dividere
     * ogni volta: con una divisione fra interi le caselle estreme sfalserebbero di
     * qualche pixel e le coordinate interne finirebbero fuori dalla scacchiera.
     */
    private int getSquareSize() {
        return Math.min(getWidth(), getHeight()) / 8;
    }

    private int getOriginX() {
        return (getWidth() - getSquareSize() * 8) / 2;
    }

    private int getOriginY() {
        return (getHeight() - getSquareSize() * 8) / 2;
    }

    private void handleSquareClick(int px, int py) {
        Position clicked = getSquareAt(px, py);
        if (clicked == null) return;

        Piece clickedPiece = board.getPiece(clicked);

        // Se abbiamo già un pezzo selezionato
        if (selectedPosition != null) {
            // Controlla se la casella cliccata è una mossa legale
            Move chosenMove = null;
            for (Move m : legalMovesForSelected) {
                if (m.getTo().equals(clicked)) {
                    chosenMove = m;
                    break;
                }
            }

            if (chosenMove != null) {
                // Controllo promozione pedone
                Piece moving = board.getPiece(selectedPosition);
                int promoRow = (moving.getColor() == PieceColor.WHITE) ? 0 : 7;
                if (moving.getType() == PieceType.PAWN && clicked.getRow() == promoRow) {
                    PieceType promoChoice = promptPromotionChoice(moving.getColor());
                    chosenMove = new Move(selectedPosition, clicked, promoChoice);
                }

                // Invia la mossa
                if (moveListener != null) {
                    moveListener.accept(chosenMove);
                }

                selectedPosition = null;
                legalMovesForSelected.clear();
                repaint();
                return;
            }
        }

        // Se clicchiamo su un pezzo del proprio colore (e del proprio turno)
        if (clickedPiece != null && clickedPiece.getColor() == perspective && clickedPiece.getColor() == board.getTurn()) {
            selectedPosition = clicked;
            legalMovesForSelected = computeLegalMovesFor(clicked);
        } else {
            selectedPosition = null;
            legalMovesForSelected.clear();
        }

        repaint();
    }

    private List<Move> computeLegalMovesFor(Position from) {
        List<Move> legal = new ArrayList<>();
        List<Move> pseudo = board.getPseudoLegalMoves(from);
        for (Move m : pseudo) {
            if (board.isLegalMove(m)) {
                legal.add(m);
            }
        }
        return legal;
    }

    /**
     * Scelta del pezzo di promozione.
     *
     * <p>Le quattro opzioni sono pezzi della stessa parte, quindi il colore non
     * serve: si scelglie il tipo. La Regina resta la prima voce, come da regolamento.
     */
    private PieceType promptPromotionChoice(PieceColor color) {
        PieceType[] options = {PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT};
        String[] labels = {"Regina", "Torre", "Alfiere", "Cavallo"};

        JPanel picker = Glass.row(new GridLayout(2, 2, 8, 8), 8);
        final PieceType[] chosen = {PieceType.QUEEN};
        for (int i = 0; i < options.length; i++) {
            final PieceType type = options[i];
            Glass.Button b = new Glass.Button(labels[i] + "  " + symbolOf(type, color),
                    i == 0 ? Glass.Button.Kind.PRIMARY : Glass.Button.Kind.GHOST);
            b.setPreferredSize(new Dimension(150, 46));
            b.addActionListener(e -> {
                chosen[0] = type;
                Window w = SwingUtilities.getWindowAncestor(b);
                if (w != null) w.setVisible(false);
            });
            picker.add(b);
        }

        Glass.Panel card = new Glass.Panel(new BorderLayout(0, 18), Glass.RADIUS, Glass.SURFACE, true);
        card.setBorder(new EmptyBorder(26, 30, 24, 30));

        JLabel title = Glass.label("Promozione del pedone", 18, Font.BOLD, Glass.TEXT);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        JLabel hint = Glass.label("Scegli il pezzo che sostituisce il pedone", 13, Font.PLAIN, Glass.TEXT_DIM);
        hint.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel head = Glass.row(new GridLayout(0, 1, 0, 6), 6);
        head.add(title);
        head.add(hint);
        card.add(head, BorderLayout.NORTH);
        card.add(picker, BorderLayout.CENTER);

        // I quattro pulsanti sono l'unica azione: niente riga "Annulla" in fondo.
        // La Regina resta il default se il dialogo viene chiuso con Esc.
        Dialogs.bare(this, "Promozione", card);
        return chosen[0];
    }

    /** Glifo del pezzo, riusato dal selettore di promozione. */
    private static String symbolOf(PieceType type, PieceColor color) {
        return String.valueOf(getPieceSymbol(new Piece(type, color)));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = Glass.prep(g);

        int squareSize = getSquareSize();
        if (squareSize <= 0) {
            g2.dispose();
            return;
        }
        int boardSize = squareSize * 8;
        Position checkKingPos = board.isKingInCheck(board.getTurn()) ? board.findKing(board.getTurn()) : null;

        // Scacchiera centrata nel componente: il pannello puo' essere piu' largo
        // dell'altezza, e senza questo le caselle resterebbero addossate a sinistra.
        int originX = (getWidth() - boardSize) / 2;
        int originY = (getHeight() - boardSize) / 2;

        // Riquadro di vetro dietro la scacchiera, con lo stesso margine su tutti i
        // lati: e' il contenitore che tiene insieme scacchiera e vetro, quindi non
        // serve un pannello padre che si allargherebbe fino ai bordi della finestra.
        int pad = 16;
        g2.setColor(Glass.alpha(Glass.SURFACE, 130));
        g2.fill(Glass.round(originX - pad, originY - pad, boardSize + pad * 2, boardSize + pad * 2, Glass.RADIUS));
        g2.setPaint(new GradientPaint(
                originX, originY - pad, Glass.alpha(Color.WHITE, 40),
                originX, originY + boardSize + pad, Glass.alpha(Color.WHITE, 6)));
        g2.setStroke(new BasicStroke(1.1f));
        g2.draw(Glass.roundF(originX - pad + 0.5, originY - pad + 0.5,
                boardSize + pad * 2 - 1, boardSize + pad * 2 - 1, Glass.RADIUS));

        // Ombra morbida sotto la scacchiera: la stacca dal vetro.
        g2.setColor(new Color(0, 0, 0, 90));
        g2.fill(Glass.roundF(originX - 3, originY + 4, boardSize + 6, boardSize + 6, 12));

        // 1. Caselle, ritagliate dentro il riquadro arrotondato: i fillRect
        // delle casze d'angolo sborderebbero altrimenti dagli angoli del vetro.
        Shape boardClip = Glass.roundF(originX - 2, originY - 2, boardSize + 4, boardSize + 4, 10);
        g2.clip(boardClip);

        for (int rDisp = 0; rDisp < 8; rDisp++) {
            for (int cDisp = 0; cDisp < 8; cDisp++) {
                int row = (perspective == PieceColor.WHITE) ? rDisp : (7 - rDisp);
                int col = (perspective == PieceColor.WHITE) ? cDisp : (7 - cDisp);
                Position currentPos = Position.of(row, col);

                int x = originX + cDisp * squareSize;
                int y = originY + rDisp * squareSize;

                boolean isLight = (row + col) % 2 == 0;
                // Le due tinte piu' vicine del tema, non i colori del legno: vanno
                // d'accordo con il resto della finestra e non stancano gli occhi.
                g2.setColor(isLight ? Glass.BOARD_LIGHT : Glass.BOARD_DARK);
                g2.fillRect(x, y, squareSize, squareSize);

                if (lastMove != null && (lastMove.getFrom().equals(currentPos) || lastMove.getTo().equals(currentPos))) {
                    g2.setColor(COLOR_LAST_MOVE);
                    g2.fillRect(x, y, squareSize, squareSize);
                }
                if (selectedPosition != null && selectedPosition.equals(currentPos)) {
                    g2.setColor(COLOR_SELECTED);
                    g2.fillRect(x, y, squareSize, squareSize);
                }
                if (checkKingPos != null && checkKingPos.equals(currentPos)) {
                    g2.setColor(COLOR_CHECK);
                    g2.fillRect(x, y, squareSize, squareSize);
                }

                // Coordinate: nei colori della casella opposta, cosi' restano leggibili
                // su entrambe le tinte.
                g2.setFont(Glass.sans(Math.max(9, squareSize / 8), Font.BOLD));
                g2.setColor(isLight ? Glass.BOARD_DARK : Glass.BOARD_LIGHT);
                FontMetrics fm = g2.getFontMetrics();

                if (cDisp == 0) {
                    g2.drawString(String.valueOf(currentPos.getRank()), x + 4, y + fm.getAscent() + 2);
                }
                if (rDisp == 7) {
                    String file = String.valueOf(currentPos.getFile());
                    g2.drawString(file, x + squareSize - fm.stringWidth(file) - 4, y + squareSize - 4);
                }
            }
        }

        // 2. Indicatori delle mosse legali
        for (Move legalMove : legalMovesForSelected) {
            Position to = legalMove.getTo();
            int rDisp = (perspective == PieceColor.WHITE) ? to.getRow() : (7 - to.getRow());
            int cDisp = (perspective == PieceColor.WHITE) ? to.getCol() : (7 - to.getCol());
            int cx = originX + cDisp * squareSize + squareSize / 2;
            int cy = originY + rDisp * squareSize + squareSize / 2;

            if (board.getPiece(to) != null) {
                // Anello per le catture: si distingue dal pallino di una mossa tranquilla.
                int radius = squareSize / 2 - 4;
                g2.setColor(COLOR_LEGAL_CAPTURE);
                g2.setStroke(new BasicStroke(Math.max(2f, squareSize / 14f)));
                g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
            } else {
                int radius = squareSize / 6;
                g2.setColor(COLOR_LEGAL_HINT);
                g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
            }
        }

        // 3. Pezzi
        int fontSize = (int) (squareSize * 0.80);
        g2.setFont(pieceFont(fontSize));
        FontMetrics fm = g2.getFontMetrics();

        for (int rDisp = 0; rDisp < 8; rDisp++) {
            for (int cDisp = 0; cDisp < 8; cDisp++) {
                int row = (perspective == PieceColor.WHITE) ? rDisp : (7 - rDisp);
                int col = (perspective == PieceColor.WHITE) ? cDisp : (7 - cDisp);
                Piece piece = board.getPiece(row, col);
                if (piece == null) continue;

                String symbol = String.valueOf(getPieceSymbol(piece));
                int textWidth = fm.stringWidth(symbol);
                int x = originX + cDisp * squareSize + (squareSize - textWidth) / 2;
                int y = originY + rDisp * squareSize + (squareSize - fm.getHeight()) / 2 + fm.getAscent();

                boolean white = piece.getColor() == PieceColor.WHITE;

                // Ombra morbida: stacca il pezzo dalla casella.
                g2.setColor(new Color(0, 0, 0, 70));
                g2.drawString(symbol, x + 1, y + 2);

                // I pezzi bianchi hanno un contorno scuro, altrimenti su una casella
                // chiara diventano una macchia indistinguibile.
                if (white) {
                    Graphics2D outline = (Graphics2D) g2.create();
                    outline.setStroke(new BasicStroke(Math.max(2f, squareSize / 26f),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    outline.setColor(Glass.alpha(Glass.BG_DEEP, 220));
                    outline.drawString(symbol, x, y);
                    outline.dispose();
                }

                g2.setColor(white ? Color.WHITE : new Color(0x14161F));
                g2.drawString(symbol, x, y);
            }
        }

        // 4. Contorno della scacchiera. Il ritaglio delle caselle viene tolto
        // prima, altrimenti anche il bordo ne risulterebbe mozzato.
        g2.setClip(null);
        g2.setColor(Glass.alpha(Glass.BOARD_EDGE, 200));
        g2.setStroke(new BasicStroke(1.2f));
        g2.draw(Glass.roundF(originX - 2, originY - 2, boardSize + 4, boardSize + 4, 10));

        g2.dispose();
    }

    /**
     * Font dei pezzi.
     *
     * <p>I glifi scacchiesti esistono in poche famiglie: senza un controllo esplicito
     * il JDK sceglie un fallback che non contiene nulla e sulla scacchiera appaiono
     * dei rettangoli vuoti al posto dei pezzi.
     */
    private static Font pieceFont(int size) {
        for (String family : new String[]{"Segoe UI Symbol", "Noto Sans Symbols 2", "DejaVu Sans", "Symbola"}) {
            Font candidate = new Font(family, Font.PLAIN, size);
            if (candidate.canDisplay('♔') && candidate.canDisplay('♚')) {
                return candidate;
            }
        }
        return new Font(Font.DIALOG, Font.PLAIN, size);
    }

    private static char getPieceSymbol(Piece piece) {
        if (piece.getColor() == PieceColor.WHITE) {
            return switch (piece.getType()) {
                case KING -> '♔';
                case QUEEN -> '♕';
                case ROOK -> '♖';
                case BISHOP -> '♗';
                case KNIGHT -> '♘';
                case PAWN -> '♙';
            };
        } else {
            return switch (piece.getType()) {
                case KING -> '♚';
                case QUEEN -> '♛';
                case ROOK -> '♜';
                case BISHOP -> '♝';
                case KNIGHT -> '♞';
                case PAWN -> '♟';
            };
        }
    }
}
