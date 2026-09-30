package org.schacchi.client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Schermata di connessione e autenticazione (Login / Registrazione / Accesso Ospite).
 * Permette di configurare l'indirizzo del server (predefinito: game.cristianrenosto.party:12345).
 */
public class LoginPanel extends JPanel {
    private final ClientNetwork network;
    private final Runnable onAuthenticatedCallback;

    private JTextField txtHost;
    private JTextField txtPort;

    // Login
    private JTextField txtLoginUser;
    private JPasswordField txtLoginPass;

    // Register
    private JTextField txtRegUser;
    private JPasswordField txtRegPass;
    private JPasswordField txtRegPassConfirm;

    // Guest
    private JTextField txtGuestNick;

    private JLabel lblStatus;

    public LoginPanel(ClientNetwork network, Runnable onAuthenticatedCallback) {
        this.network = network;
        this.onAuthenticatedCallback = onAuthenticatedCallback;

        setLayout(new GridBagLayout());
        setBackground(new Color(24, 25, 29));

        JPanel card = new JPanel(new BorderLayout(12, 12));
        card.setPreferredSize(new Dimension(460, 480));
        card.setBackground(new Color(34, 36, 42));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(60, 64, 76), 1),
                new EmptyBorder(20, 24, 20, 24)
        ));

        // Titolo
        JPanel titlePanel = new JPanel(new GridLayout(2, 1, 2, 2));
        titlePanel.setOpaque(false);
        JLabel lblTitle = new JLabel("Server Scacchi Online", SwingConstants.CENTER);
        lblTitle.setFont(new Font("SansSerif", Font.BOLD, 22));
        lblTitle.setForeground(Color.WHITE);

        JLabel lblSub = new JLabel("game.cristianrenosto.party", SwingConstants.CENTER);
        lblSub.setFont(new Font("SansSerif", Font.PLAIN, 13));
        lblSub.setForeground(new Color(130, 180, 255));

        titlePanel.add(lblTitle);
        titlePanel.add(lblSub);
        card.add(titlePanel, BorderLayout.NORTH);

        // Centro: Tabs Login / Registrati / Ospite
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("SansSerif", Font.BOLD, 12));

        tabs.addTab("Accedi", createLoginTab());
        tabs.addTab("Crea Account", createRegisterTab());
        tabs.addTab("Gioca come Ospite", createGuestTab());

        // Pannello server host e porta in alto dentro il centro
        JPanel centerBox = new JPanel(new BorderLayout(10, 10));
        centerBox.setOpaque(false);

        JPanel serverConfig = new JPanel(new GridLayout(1, 4, 6, 6));
        serverConfig.setOpaque(false);
        serverConfig.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(60, 65, 75)),
                "Server di Destinazione", 0, 0, new Font("SansSerif", Font.PLAIN, 11), Color.LIGHT_GRAY
        ));

        txtHost = new JTextField("game.cristianrenosto.party");
        txtPort = new JTextField("12345");

        serverConfig.add(new JLabel("Host:"));
        serverConfig.add(txtHost);
        serverConfig.add(new JLabel("Porta:"));
        serverConfig.add(txtPort);

        centerBox.add(serverConfig, BorderLayout.NORTH);
        centerBox.add(tabs, BorderLayout.CENTER);

        card.add(centerBox, BorderLayout.CENTER);

        // Stato in basso
        lblStatus = new JLabel("Pronto per la connessione", SwingConstants.CENTER);
        lblStatus.setForeground(Color.LIGHT_GRAY);
        lblStatus.setFont(new Font("SansSerif", Font.PLAIN, 12));
        card.add(lblStatus, BorderLayout.SOUTH);

        add(card);
    }

    private JPanel createLoginTab() {
        JPanel p = new JPanel(new GridLayout(3, 2, 10, 12));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(15, 10, 15, 10));

        txtLoginUser = new JTextField();
        txtLoginPass = new JPasswordField();

        JButton btnLogin = new JButton("Accedi");
        btnLogin.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnLogin.setBackground(new Color(45, 120, 220));
        btnLogin.setForeground(Color.WHITE);
        btnLogin.addActionListener(e -> performLogin());

        p.add(new JLabel("Username:"));
        p.add(txtLoginUser);
        p.add(new JLabel("Password:"));
        p.add(txtLoginPass);
        p.add(new JLabel(""));
        p.add(btnLogin);

        return p;
    }

    private JPanel createRegisterTab() {
        JPanel p = new JPanel(new GridLayout(4, 2, 10, 10));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(15, 10, 15, 10));

        txtRegUser = new JTextField();
        txtRegPass = new JPasswordField();
        txtRegPassConfirm = new JPasswordField();

        JButton btnRegister = new JButton("Registrati");
        btnRegister.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnRegister.setBackground(new Color(40, 140, 70));
        btnRegister.setForeground(Color.WHITE);
        btnRegister.addActionListener(e -> performRegister());

        p.add(new JLabel("Nuovo Username:"));
        p.add(txtRegUser);
        p.add(new JLabel("Password:"));
        p.add(txtRegPass);
        p.add(new JLabel("Conferma Password:"));
        p.add(txtRegPassConfirm);
        p.add(new JLabel(""));
        p.add(btnRegister);

        return p;
    }

    private JPanel createGuestTab() {
        JPanel p = new JPanel(new GridLayout(2, 2, 10, 12));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(25, 10, 25, 10));

        txtGuestNick = new JTextField("Ospite_" + (int)(Math.random() * 900 + 100));

        JButton btnGuest = new JButton("Entra come Ospite");
        btnGuest.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnGuest.addActionListener(e -> performGuestLogin());

        p.add(new JLabel("Nickname Ospite:"));
        p.add(txtGuestNick);
        p.add(new JLabel(""));
        p.add(btnGuest);

        return p;
    }

    private void ensureConnected(Runnable afterConnect) {
        String host = txtHost.getText().trim();
        int port = 12345;
        try {
            port = Integer.parseInt(txtPort.getText().trim());
        } catch (NumberFormatException ignored) {}
        // Copia definitiva: 'port' viene riassegnato nel try e non puo' essere
        // catturato direttamente dalla lambda qui sotto.
        final int targetPort = port;
        final String targetHost = host;

        if (network.isConnected()) {
            afterConnect.run();
            return;
        }

        lblStatus.setText("Connessione in corso a " + targetHost + ":" + targetPort + "...");
        lblStatus.setForeground(Color.YELLOW);

        network.connect(targetHost, targetPort);

        // Attende brevemente la connessione
        new Thread(() -> {
            for (int i = 0; i < 25; i++) {
                if (network.isConnected()) {
                    SwingUtilities.invokeLater(() -> {
                        lblStatus.setText("Connesso al server!");
                        lblStatus.setForeground(new Color(100, 240, 120));
                        afterConnect.run();
                    });
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {}
            }
            SwingUtilities.invokeLater(() -> {
                lblStatus.setText("Impossibile raggiungere " + targetHost + ":" + targetPort);
                lblStatus.setForeground(Color.RED);
            });
        }).start();
    }

    private void performLogin() {
        String u = txtLoginUser.getText().trim();
        String p = new String(txtLoginPass.getPassword()).trim();
        if (u.isEmpty() || p.isEmpty()) {
            lblStatus.setText("Inserisci username e password");
            lblStatus.setForeground(Color.RED);
            return;
        }

        ensureConnected(() -> network.login(u, p));
    }

    private void performRegister() {
        String u = txtRegUser.getText().trim();
        String p = new String(txtRegPass.getPassword()).trim();
        String pc = new String(txtRegPassConfirm.getPassword()).trim();

        if (u.length() < 3 || p.length() < 3) {
            lblStatus.setText("Username e password devono avere almeno 3 caratteri");
            lblStatus.setForeground(Color.RED);
            return;
        }
        if (!p.equals(pc)) {
            lblStatus.setText("Le password non coincidono!");
            lblStatus.setForeground(Color.RED);
            return;
        }

        ensureConnected(() -> network.register(u, p));
    }

    private void performGuestLogin() {
        String nick = txtGuestNick.getText().trim();
        if (nick.isEmpty()) nick = "Ospite";
        String finalNick = nick;

        ensureConnected(() -> {
            network.setName(finalNick);
            if (onAuthenticatedCallback != null) {
                onAuthenticatedCallback.run();
            }
        });
    }

    public void setStatusMessage(String message, boolean isError) {
        lblStatus.setText(message);
        lblStatus.setForeground(isError ? Color.RED : new Color(100, 240, 120));
    }
}
