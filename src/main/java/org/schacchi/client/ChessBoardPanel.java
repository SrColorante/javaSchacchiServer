package org.schacchi.client;

import org.schacchi.model.*;

import javax.swing.*;
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

    // Palette colori stile torneo / Lichess
    private final Color COLOR_LIGHT = new Color(240, 217, 181);
    private final Color COLOR_DARK = new Color(181, 136, 99);
    private final Color COLOR_SELECTED = new Color(246, 246, 105, 210);
    private final Color COLOR_LAST_MOVE = new Color(205, 210, 106, 180);
    private final Color COLOR_CHECK = new Color(235, 90, 80, 220);
    private final Color COLOR_LEGAL_HINT = new Color(40, 40, 40, 70);

    public ChessBoardPanel() {
        setPreferredSize(new Dimension(560, 560));
        setMinimumSize(new Dimension(360, 360));
        setBackground(new Color(30, 30, 30));

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
        int colDisplay = px / squareSize;
        int rowDisplay = py / squareSize;

        if (colDisplay < 0 || colDisplay >= 8 || rowDisplay < 0 || rowDisplay >= 8) {
            return null;
        }

        int row = (perspective == PieceColor.WHITE) ? rowDisplay : (7 - rowDisplay);
        int col = (perspective == PieceColor.WHITE) ? colDisplay : (7 - colDisplay);

        return Position.of(row, col);
    }

    private int getSquareSize() {
        return Math.min(getWidth(), getHeight()) / 8;
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

    private PieceType promptPromotionChoice(PieceColor color) {
        String[] options = {"Regina (Donna)", "Torre", "Alfiere", "Cavallo"};
        int res = JOptionPane.showOptionDialog(
                this,
                "Scegli il pezzo di promozione:",
                "Promozione Pedone",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );

        return switch (res) {
            case 1 -> PieceType.ROOK;
            case 2 -> PieceType.BISHOP;
            case 3 -> PieceType.KNIGHT;
            default -> PieceType.QUEEN;
        };
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int squareSize = getSquareSize();
        Position checkKingPos = board.isKingInCheck(board.getTurn()) ? board.findKing(board.getTurn()) : null;

        // 1. Disegna caselle
        for (int rDisp = 0; rDisp < 8; rDisp++) {
            for (int cDisp = 0; cDisp < 8; cDisp++) {
                int row = (perspective == PieceColor.WHITE) ? rDisp : (7 - rDisp);
                int col = (perspective == PieceColor.WHITE) ? cDisp : (7 - cDisp);
                Position currentPos = Position.of(row, col);

                int x = cDisp * squareSize;
                int y = rDisp * squareSize;

                boolean isLight = (row + col) % 2 == 0;
                g2.setColor(isLight ? COLOR_LIGHT : COLOR_DARK);
                g2.fillRect(x, y, squareSize, squareSize);

                // Evidenzia ultima mossa
                if (lastMove != null && (lastMove.getFrom().equals(currentPos) || lastMove.getTo().equals(currentPos))) {
                    g2.setColor(COLOR_LAST_MOVE);
                    g2.fillRect(x, y, squareSize, squareSize);
                }

                // Evidenzia casella selezionata
                if (selectedPosition != null && selectedPosition.equals(currentPos)) {
                    g2.setColor(COLOR_SELECTED);
                    g2.fillRect(x, y, squareSize, squareSize);
                }

                // Evidenzia Re sotto scacco
                if (checkKingPos != null && checkKingPos.equals(currentPos)) {
                    g2.setColor(COLOR_CHECK);
                    g2.fillRect(x, y, squareSize, squareSize);
                }

                // Disegna etichette coordinate su bordi (es: 'a'-'h' e '1'-'8')
                g2.setFont(new Font("SansSerif", Font.BOLD, Math.max(10, squareSize / 7)));
                Color coordColor = isLight ? COLOR_DARK : COLOR_LIGHT;
                g2.setColor(coordColor);

                if (cDisp == 0) {
                    // Numero traversa (alto a sinistra)
                    int rank = currentPos.getRank();
                    g2.drawString(String.valueOf(rank), x + 3, y + g2.getFontMetrics().getAscent() + 1);
                }
                if (rDisp == 7) {
                    // Lettera colonna (basso a destra)
                    char file = currentPos.getFile();
                    int strWidth = g2.getFontMetrics().stringWidth(String.valueOf(file));
                    g2.drawString(String.valueOf(file), x + squareSize - strWidth - 3, y + squareSize - 3);
                }
            }
        }

        // 2. Disegna indicatori mosse legali per il pezzo selezionato
        for (Move legalMove : legalMovesForSelected) {
            Position to = legalMove.getTo();
            int rDisp = (perspective == PieceColor.WHITE) ? to.getRow() : (7 - to.getRow());
            int cDisp = (perspective == PieceColor.WHITE) ? to.getCol() : (7 - to.getCol());
            int cx = cDisp * squareSize + squareSize / 2;
            int cy = rDisp * squareSize + squareSize / 2;

            Piece targetPiece = board.getPiece(to);
            g2.setColor(COLOR_LEGAL_HINT);
            if (targetPiece != null) {
                // Cerchio cavo per cattura
                int radius = squareSize / 2 - 2;
                g2.setStroke(new BasicStroke(squareSize / 12f));
                g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
            } else {
                // Pallino solido per casella vuota
                int radius = squareSize / 6;
                g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
            }
        }

        // 3. Disegna i pezzi
        int fontSize = (int) (squareSize * 0.78);
        Font pieceFont = new Font("Segoe UI Symbol", Font.PLAIN, fontSize);
        // Fallback a DejaVu Sans o Dialog se i glifi mancano
        if (!pieceFont.canDisplay('♔')) {
            pieceFont = new Font("DejaVu Sans", Font.PLAIN, fontSize);
        }
        if (!pieceFont.canDisplay('♔')) {
            pieceFont = new Font(Font.DIALOG, Font.PLAIN, fontSize);
        }

        g2.setFont(pieceFont);
        FontMetrics fm = g2.getFontMetrics();

        for (int rDisp = 0; rDisp < 8; rDisp++) {
            for (int cDisp = 0; cDisp < 8; cDisp++) {
                int row = (perspective == PieceColor.WHITE) ? rDisp : (7 - rDisp);
                int col = (perspective == PieceColor.WHITE) ? cDisp : (7 - cDisp);
                Piece piece = board.getPiece(row, col);

                if (piece != null) {
                    char symbol = getPieceSymbol(piece);
                    String str = String.valueOf(symbol);
                    int textWidth = fm.stringWidth(str);
                    int x = cDisp * squareSize + (squareSize - textWidth) / 2;
                    int y = rDisp * squareSize + (squareSize - fm.getHeight()) / 2 + fm.getAscent();

                    // Ombreggiatura per contrasto
                    g2.setColor(new Color(0, 0, 0, 110));
                    g2.drawString(str, x + 2, y + 2);

                    // Colore pezzo
                    if (piece.getColor() == PieceColor.WHITE) {
                        g2.setColor(new Color(255, 255, 255));
                    } else {
                        g2.setColor(new Color(30, 30, 30));
                    }
                    g2.drawString(str, x, y);
                }
            }
        }

        g2.dispose();
    }

    private char getPieceSymbol(Piece piece) {
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
