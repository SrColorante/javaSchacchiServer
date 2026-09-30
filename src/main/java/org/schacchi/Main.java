package org.schacchi;

import org.schacchi.server.ConnectionHandler;
import org.schacchi.server.GameSession;
import org.schacchi.server.Server;
import org.schacchi.server.ServerListener;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;

/**
 * Punto d'ingresso principale dell'applicazione.
 * - Su server headless (es. VPS Linux game.cristianrenosto.party) esegue il server in modalità console.
 * - Su ambiente desktop locale fornisce una dashboard di controllo Swing con log in tempo reale.
 */
public class Main {
    public static void main(String[] args) {
        Server server = Server.getInstance();

        // Se siamo su server headless (senza display, es. Linux VPS / container)
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("Rilevato ambiente Headless (server/console). Avvio Server Scacchi...");
            try {
                server.start();
            } catch (IOException e) {
                System.err.println("Errore fatale all'avvio del server: " + e.getMessage());
                e.printStackTrace();
            }
            return;
        }

        // Avvia il server in background
        server.startAsync();

        // Avvia la Dashboard grafica su Swing EDT
        SwingUtilities.invokeLater(() -> createAndShowGUI(server));
    }

    private static void createAndShowGUI(Server server) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        JFrame frame = new JFrame("Server Scacchi - [game.cristianrenosto.party]");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(850, 600);
        frame.setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Header con stato
        JPanel headerPanel = new JPanel(new GridLayout(2, 2, 10, 6));
        headerPanel.setBorder(BorderFactory.createTitledBorder("Stato del Server"));

        JLabel lblStatus = new JLabel("Stato: ONLINE (Porta " + server.getPort() + ")");
        lblStatus.setForeground(new Color(0, 140, 40));
        lblStatus.setFont(lblStatus.getFont().deriveFont(Font.BOLD, 13f));

        JLabel lblHost = new JLabel("Dominio di produzione: game.cristianrenosto.party");
        lblHost.setFont(lblHost.getFont().deriveFont(Font.PLAIN, 12f));

        JLabel lblClients = new JLabel("Client connessi: 0");
        lblClients.setFont(lblClients.getFont().deriveFont(Font.BOLD, 12f));

        JLabel lblSessions = new JLabel("Sessioni di gioco attive: 0");
        lblSessions.setFont(lblSessions.getFont().deriveFont(Font.BOLD, 12f));

        headerPanel.add(lblStatus);
        headerPanel.add(lblHost);
        headerPanel.add(lblClients);
        headerPanel.add(lblSessions);
        mainPanel.add(headerPanel, BorderLayout.NORTH);

        // Area di log in tempo reale
        JTextArea logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        logArea.setBackground(new Color(24, 24, 28));
        logArea.setForeground(new Color(220, 220, 220));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Log Eventi e Partite in Tempo Reale"));
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // Barra inferiore con controlli
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnClearLog = new JButton("Pulisci Log");
        btnClearLog.addActionListener(e -> logArea.setText(""));

        JButton btnToggleServer = new JButton("Arresta Server");
        btnToggleServer.addActionListener(e -> {
            if (server.isRunning()) {
                server.stop();
                lblStatus.setText("Stato: OFFLINE");
                lblStatus.setForeground(Color.RED);
                btnToggleServer.setText("Riavvia Server");
            } else {
                server.startAsync();
                lblStatus.setText("Stato: ONLINE (Porta " + server.getPort() + ")");
                lblStatus.setForeground(new Color(0, 140, 40));
                btnToggleServer.setText("Arresta Server");
            }
        });

        bottomPanel.add(btnClearLog);
        bottomPanel.add(btnToggleServer);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        // Aggiorna contatori e log ascoltando il server
        server.addListener(new ServerListener() {
            private void updateCounts() {
                SwingUtilities.invokeLater(() -> {
                    lblClients.setText("Client connessi: " + server.getConnectedClients().size());
                    lblSessions.setText("Sessioni attive: " + server.getSessionManager().getActiveSessionsCount() +
                            " (Stanze create: " + server.getSessionManager().getAllSessions().size() + ")");
                });
            }

            @Override
            public void onLog(String message) {
                SwingUtilities.invokeLater(() -> {
                    logArea.append(message + "\n");
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                });
            }

            @Override
            public void onClientConnected(ConnectionHandler client) {
                updateCounts();
            }

            @Override
            public void onClientDisconnected(ConnectionHandler client) {
                updateCounts();
            }

            @Override
            public void onSessionCreated(GameSession session) {
                updateCounts();
            }

            @Override
            public void onSessionStarted(GameSession session) {
                updateCounts();
            }

            @Override
            public void onSessionEnded(GameSession session, String reason) {
                updateCounts();
            }
        });

        frame.add(mainPanel);
        frame.setVisible(true);
    }
}
