package org.schacchi.server;

import org.schacchi.model.ChessBoard;
import org.schacchi.model.Move;
import org.schacchi.model.PieceColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Gestisce una singola sessione di gioco di scacchi tra due giocatori (Bianco e Nero).
 * Il server funge da garante delle regole (validando ogni mossa con ChessBoard)
 * e da ponte di comunicazione tra i due client.
 *
 * <h2>Condizioni di fine partita</h2>
 * L'esito viene sempre centralizzato in {@link #endGame}, in modo che statistiche ed
 * ELO vengano aggiornati per ogni esito possibile: scacco matto, stallo, resa,
 * abbandono per disconnessione, patta concordata e le patte automatiche FIDE
 * (materiale insufficiente, regola delle 50 mosse, ripetizione della posizione).
 */
public class GameSession {
    public enum GameStatus {
        WAITING_FOR_OPPONENT,
        IN_PROGRESS,
        FINISHED
    }

    private final String sessionId;
    private final String roomName;
    private ConnectionHandler whitePlayer;
    private ConnectionHandler blackPlayer;
    private final List<ConnectionHandler> spectators = Collections.synchronizedList(new ArrayList<>());
    private final ChessBoard board;
    private GameStatus status;
    private final List<String> moveHistory = Collections.synchronizedList(new ArrayList<>());
    private ConnectionHandler drawOfferedBy;
    private String endReason = "";

    public GameSession(String sessionId, String roomName, ConnectionHandler host) {
        this(sessionId, roomName, host, null);
    }

    /**
     * @param initialFen posizione di partenza in notazione FEN; null per la disposizione
     *                   iniziale degli scacchi. Se il FEN non e' valido si riparte
     *                   dalla disposizione iniziale.
     */
    public GameSession(String sessionId, String roomName, ConnectionHandler host, String initialFen) {
        this.sessionId = sessionId;
        this.roomName = (roomName != null && !roomName.trim().isEmpty()) ? roomName.trim() : sessionId;
        this.board = createBoard(sessionId, initialFen);
        this.status = GameStatus.WAITING_FOR_OPPONENT;
        if (host != null) {
            this.whitePlayer = host;
            host.setCurrentSession(this);
            host.setAssignedColor(PieceColor.WHITE);
        }
    }

    private static ChessBoard createBoard(String sessionId, String initialFen) {
        if (initialFen == null || initialFen.isBlank()) {
            return new ChessBoard();
        }
        try {
            return ChessBoard.fromFen(initialFen);
        } catch (IllegalArgumentException e) {
            System.err.println("FEN iniziale non valido per la stanza \"" + sessionId
                    + "\", si usa la posizione standard: " + e.getMessage());
            return new ChessBoard();
        }
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRoomName() {
        return roomName;
    }

    public synchronized GameStatus getStatus() {
        return status;
    }

    public synchronized ConnectionHandler getWhitePlayer() {
        return whitePlayer;
    }

    public synchronized ConnectionHandler getBlackPlayer() {
        return blackPlayer;
    }

    public synchronized ChessBoard getBoard() {
        return board;
    }

    public List<String> getMoveHistory() {
        synchronized (moveHistory) {
            return Collections.unmodifiableList(new ArrayList<>(moveHistory));
        }
    }

    public String getEndReason() {
        return endReason;
    }

    public synchronized boolean isSpectator(ConnectionHandler player) {
        return spectators.contains(player);
    }

    /**
     * Aggiunge il secondo giocatore alla sessione e avvia la partita.
     */
    public synchronized boolean addPlayer(ConnectionHandler player) {
        if (status == GameStatus.FINISHED) {
            return false;
        }

        if (whitePlayer == null) {
            whitePlayer = player;
            player.setCurrentSession(this);
            player.setAssignedColor(PieceColor.WHITE);
            return true;
        } else if (blackPlayer == null && !player.equals(whitePlayer)) {
            blackPlayer = player;
            player.setCurrentSession(this);
            player.setAssignedColor(PieceColor.BLACK);
            this.status = GameStatus.IN_PROGRESS;

            // Invia inizio partita a entrambi i giocatori
            whitePlayer.sendMessage("GAME_START " + sessionId + " WHITE " + blackPlayer.getUsername());
            blackPlayer.sendMessage("GAME_START " + sessionId + " BLACK " + whitePlayer.getUsername());
            broadcast("INFO Partita iniziata tra " + whitePlayer.getUsername() + " (Bianco) e "
                    + blackPlayer.getUsername() + " (Nero)");

            Server.getInstance().notifySessionStarted(this);
            return true;
        } else {
            // Sessione piena per i giocatori, aggiungi come spettatore
            spectators.add(player);
            player.setCurrentSession(this);
            player.setAssignedColor(null);
            // Oltre al FEN si invia la cronologia delle mosse gia' giocate: uno spettatore
            // che entra a meta' partita altrimenti vedrebbe i pezzi gia' spostati sulla
            // scacchiera ma nessuno storico, e non potrebbe ricostruire come ci e' arrivati.
            player.sendMessage("SPECTATING " + sessionId + " " + board.toFen());
            synchronized (moveHistory) {
                if (!moveHistory.isEmpty()) {
                    player.sendMessage("MOVE_HISTORY " + String.join(" ", moveHistory));
                }
            }
            return false;
        }
    }

    /**
     * Valida ed esegue una mossa nel turno del giocatore corrispondente.
     */
    public synchronized void processMove(ConnectionHandler sender, String uciMoveStr) {
        if (status != GameStatus.IN_PROGRESS) {
            sender.sendMessage("ERROR La partita non e' in corso");
            return;
        }

        PieceColor currentTurn = board.getTurn();
        ConnectionHandler expectedPlayer = (currentTurn == PieceColor.WHITE) ? whitePlayer : blackPlayer;

        if (sender != expectedPlayer) {
            sender.sendMessage("ERROR Non e' il tuo turno!");
            return;
        }

        Move move;
        try {
            move = Move.fromUci(uciMoveStr);
        } catch (Exception e) {
            sender.sendMessage("ERROR Formato mossa non valido. Usa la notazione UCI (es: e2e4 o e7e8q)");
            return;
        }

        // Il server funge da garante delle regole
        if (!board.isLegalMove(move)) {
            sender.sendMessage("ERROR Mossa illegale secondo le regole degli scacchi: " + uciMoveStr);
            return;
        }

        // Esegue la mossa
        boolean success = board.makeMove(move);
        if (!success) {
            sender.sendMessage("ERROR Impossibile eseguire la mossa: " + uciMoveStr);
            return;
        }

        // Un'offerta di patta decade non appena il giocatore che l'ha fatta muove.
        this.drawOfferedBy = null;

        String uciExecuted = move.toUci();
        moveHistory.add(uciExecuted);
        String fen = board.toFen();

        // Notifica il giocatore che ha mosso
        sender.sendMessage("MOVE_OK " + uciExecuted + " " + fen);

        // Notifica l'avversario
        ConnectionHandler opponent = (sender == whitePlayer) ? blackPlayer : whitePlayer;
        if (opponent != null) {
            opponent.sendMessage("OPPONENT_MOVE " + uciExecuted + " " + fen);
        }

        // Notifica eventuali spettatori
        synchronized (spectators) {
            for (ConnectionHandler spectator : spectators) {
                spectator.sendMessage("MOVE " + uciExecuted + " " + fen);
            }
        }

        Server.getInstance().notifyMoveMade(this, uciExecuted, fen);

        // Controlla le condizioni di fine partita.
        // Scacco matto e stallo hanno la precedenza sulle patte automatiche: ad esempio
        // un mate dato alla centesima semi-mossa soddisferebbe anche la regola delle 50 mosse,
        // ma secondo il regolamento FIDE (art. 9.6.2) la partita è vinta, non patta.
        if (board.isCheckmate()) {
            PieceColor winner = board.getTurn().opposite();
            String winnerName = (winner == PieceColor.WHITE) ? whitePlayer.getUsername() : blackPlayer.getUsername();
            String loserName = (winner == PieceColor.WHITE) ? blackPlayer.getUsername() : whitePlayer.getUsername();
            endGame("CHECKMATE", false, winnerName, loserName,
                    "GAME_OVER " + winner.name() + "_WON CHECKMATE (Vince " + winnerName + ")");
        } else if (board.isStalemate()) {
            endGame("STALEMATE", true, null, null,
                    "GAME_OVER DRAW STALEMATE (Patta per stallo)");
        } else {
            String drawReason = automaticDrawReason();
            if (drawReason != null) {
                endGame(drawReason, true, null, null,
                        "GAME_OVER DRAW " + drawReason + " (" + drawDescription(drawReason) + ")");
            } else if (board.isKingInCheck(board.getTurn())) {
                broadcast("CHECK " + board.getTurn().name());
            }
        }
    }

    /**
     * Verifica se la posizione corrente e' una patta automatica secondo le regole FIDE.
     *
     * @return il motivo della patta, oppure null se la partita continua
     */
    /**
     * Verifica se la posizione corrente e' una patta automatica secondo le regole FIDE.
     * L'ordine va dal motivo piu' vincolante al meno vincolante: senza pezzi capaci di
     * dare il mate non ha senso parlare di 50 mosse, e la quintuple ripetizione e'
     * automatica per norma mentre la tripla e' trattata qui come automatica per scelta
     * di progetto (vedi i limiti noti nel README).
     *
     * @return il motivo della patta, oppure null se la partita continua
     */
    private String automaticDrawReason() {
        if (board.isInsufficientMaterial()) return "INSUFFICIENT_MATERIAL";
        if (board.isSeventyFiveMoveDraw()) return "SEVENTY_FIVE_MOVE_RULE";
        if (board.isFiftyMoveDraw()) return "FIFTY_MOVE_RULE";
        if (board.isFivefoldRepetition()) return "FIVEFOLD_REPETITION";
        if (board.isThreefoldRepetition()) return "THREEFOLD_REPETITION";
        return null;
    }

    private static String drawDescription(String reason) {
        return switch (reason) {
            case "INSUFFICIENT_MATERIAL" -> "Materiale insufficiente per il mate";
            case "SEVENTY_FIVE_MOVE_RULE" -> "Regola delle 75 mosse";
            case "FIFTY_MOVE_RULE" -> "Regola delle 50 mosse";
            case "FIVEFOLD_REPETITION" -> "Ripetizione della posizione per cinque volte";
            case "THREEFOLD_REPETITION" -> "Ripetizione della posizione per tre volte";
            default -> "Patta automatica";
        };
    }

    /**
     * Punto unico di chiusura della partita: notifica ai partecipanti, aggiorna
     * statistiche ed ELO e segnala la chiusura al Server.
     * Tutti gli esiti possibili passano di qui, cosi' nessuna via puo' dimenticare
     * di registrare il risultato.
     */
    private void endGame(String reason, boolean isDraw, String winnerName, String loserName, String message) {
        this.status = GameStatus.FINISHED;
        this.endReason = reason;
        this.drawOfferedBy = null;

        broadcast(message);

        if (isDraw) {
            if (whitePlayer != null && blackPlayer != null) {
                Server.getInstance().getAccountManager()
                        .recordResult(whitePlayer.getUsername(), blackPlayer.getUsername(), true);
            }
        } else if (winnerName != null && loserName != null) {
            Server.getInstance().getAccountManager().recordResult(winnerName, loserName, false);
        }

        Server.getInstance().notifySessionEnded(this, reason + ": " + message);
    }

    /**
     * Gestisce la resa di un giocatore.
     */
    public synchronized void processResign(ConnectionHandler sender) {
        if (status != GameStatus.IN_PROGRESS) {
            sender.sendMessage("ERROR La partita non e' in corso");
            return;
        }

        if (sender == whitePlayer) {
            endGame("RESIGNATION", false, blackPlayer.getUsername(), whitePlayer.getUsername(),
                    "GAME_OVER BLACK_WON RESIGNATION (" + whitePlayer.getUsername() + " si e' arreso)");
        } else if (sender == blackPlayer) {
            endGame("RESIGNATION", false, whitePlayer.getUsername(), blackPlayer.getUsername(),
                    "GAME_OVER WHITE_WON RESIGNATION (" + blackPlayer.getUsername() + " si e' arreso)");
        } else {
            sender.sendMessage("ERROR Non puoi arrendere una partita in cui stai solo assistendo");
        }
    }

    /**
     * Propone una patta all'avversario.
     */
    public synchronized void processDrawOffer(ConnectionHandler sender) {
        if (status != GameStatus.IN_PROGRESS) {
            sender.sendMessage("ERROR La partita non e' in corso");
            return;
        }
        if (sender != whitePlayer && sender != blackPlayer) {
            sender.sendMessage("ERROR Non puoi offrire la patta in una partita in cui stai solo assistendo");
            return;
        }
        ConnectionHandler opponent = (sender == whitePlayer) ? blackPlayer : whitePlayer;
        this.drawOfferedBy = sender;
        opponent.sendMessage("DRAW_OFFER");
        sender.sendMessage("INFO Proposta di patta inviata");
    }

    public synchronized void processDrawAccept(ConnectionHandler sender) {
        if (status != GameStatus.IN_PROGRESS) {
            sender.sendMessage("ERROR La partita non e' in corso");
            return;
        }
        if (drawOfferedBy == null) {
            sender.sendMessage("ERROR Non c'e' nessuna offerta di patta da accettare");
            return;
        }
        if (sender == drawOfferedBy) {
            sender.sendMessage("ERROR Hai proposto tu la patta: aspetta la risposta dell'avversario");
            return;
        }
        endGame("AGREEMENT", true, null, null,
                "GAME_OVER DRAW AGREEMENT (Patta concordata)");
    }

    public synchronized void processDrawDecline(ConnectionHandler sender) {
        if (drawOfferedBy != null && sender != drawOfferedBy) {
            drawOfferedBy.sendMessage("DRAW_DECLINED");
            this.drawOfferedBy = null;
        }
    }

    /**
     * Gestisce il chat tra i due giocatori nella sessione.
     */
    public void processChat(ConnectionHandler sender, String message) {
        // I ritorni a capo romperebbero il protocollo basato su righe: vanno sostituiti.
        String safeMsg = message.replace("\n", " ").replace("\r", " ").trim();
        broadcast("CHAT " + sender.getUsername() + ": " + safeMsg);
    }

    /**
     * Rimuove uno spettatore senza influire sulla partita in corso.
     *
     * @return true se il giocatore era presente tra gli spettatori
     */
    public synchronized boolean removeSpectator(ConnectionHandler player) {
        return spectators.remove(player);
    }

    /**
     * Gestisce l'uscita di un partecipante: spettatore, giocatore o host in attesa.
     */
    public synchronized void handlePlayerDisconnect(ConnectionHandler disconnected) {
        // Uno spettatore che esce non deve concludere la partita.
        if (removeSpectator(disconnected)) {
            return;
        }

        if (status == GameStatus.IN_PROGRESS) {
            if (disconnected != whitePlayer && disconnected != blackPlayer) {
                return;
            }
            ConnectionHandler remaining = (disconnected == whitePlayer) ? blackPlayer : whitePlayer;
            if (remaining != null) {
                String winnerColor = (remaining == whitePlayer) ? "WHITE" : "BLACK";
                remaining.sendMessage("OPPONENT_DISCONNECTED");
                // L'abbandono conta come sconfitta per chi si disconnette.
                endGame("DISCONNECT", false, remaining.getUsername(), disconnected.getUsername(),
                        "GAME_OVER " + winnerColor + "_WON FORFEIT (Avversario disconnesso)");
            }
        } else if (status == GameStatus.WAITING_FOR_OPPONENT) {
            if (disconnected == whitePlayer) {
                status = GameStatus.FINISHED;
                endReason = "HOST_LEFT";
                Server.getInstance().getSessionManager().removeSession(sessionId);
            }
        }
    }

    /**
     * Invia un messaggio a tutti i partecipanti e spettatori della sessione.
     */
    public synchronized void broadcast(String message) {
        if (whitePlayer != null) whitePlayer.sendMessage(message);
        if (blackPlayer != null) blackPlayer.sendMessage(message);
        synchronized (spectators) {
            for (ConnectionHandler spectator : spectators) {
                spectator.sendMessage(message);
            }
        }
    }
}
