package org.schacchi.client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Schermata di connessione e autenticazione: Accedi / Crea account / Ospite.
 *
 * <p>L'host e la porta stanno in una riga compattabile sopra le tab, non dentro
 * ogni tab: servono una volta sola e ripeterli tre volte faceva solo rumore.
 */
public class LoginPanel extends JPanel {
    private final ClientNetwork network;
    private final Runnable onAuthenticatedCallback;

    private Glass.Field txtHost;
    private Glass.Field txtPort;

    private Glass.Field txtLoginUser;
    private Glass.SecretField txtLoginPass;

    private Glass.Field txtRegUser;
    private Glass.SecretField txtRegPass;
    private Glass.SecretField txtRegPassConfirm;
    private Glass.Field txtAge;

    private Glass.Field txtGuestNick;

    private JLabel lblStatus;
    private Glass.Tabs tabs;

    public LoginPanel(ClientNetwork network, Runnable onAuthenticatedCallback) {
        this.network = network;
        this.onAuthenticatedCallback = onAuthenticatedCallback;

        setOpaque(false);
        setLayout(new GridBagLayout());

        Glass.Panel card = new Glass.Panel(new BorderLayout(0, 22), Glass.RADIUS, Glass.SURFACE, true);
        card.setBorder(new EmptyBorder(34, 38, 30, 38));
        // Larghezza fissa, altezza lasciata al contenuto: forcing 620 schiacciava
        // le tab piu' lunghe (registrazione ha cinque campi) contro la barra di stato.
        card.setPreferredSize(new Dimension(470, 700));
        card.setMinimumSize(new Dimension(470, 700));

        card.add(createHeader(), BorderLayout.NORTH);
        card.add(createBody(), BorderLayout.CENTER);
        card.add(createStatusBar(), BorderLayout.SOUTH);

        add(card);
    }

    private JComponent createHeader() {
        // Pila verticale con BoxLayout: le tre righe (scacchiera, titolo, privacy)
        // prendono ciascuna la propria altezza naturale. Con BorderLayout la riga
        // centrale verrebbe schiacciata a zero e il titolo finirebbe attaccato.
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

        JLabel mark = Glass.label("\u265E", 34, Font.PLAIN, Glass.ACCENT);
        mark.setAlignmentX(Component.CENTER_ALIGNMENT);
        header.add(mark);
        header.add(verticalGap(10));

        JLabel title = Glass.title("Scacchi");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        header.add(title);
        header.add(verticalGap(2));

        JLabel sub = Glass.muted("game.cristianrenosto.party");
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);
        header.add(sub);
        header.add(verticalGap(10));

        // Riferimento all'informativa privacy (GDPR art. 13). Il testo completo vive
        // in docs/privacy.md: questo rimando basta a rendere la finalita' del
        // trattamento visibile PRIMA che l'utente fornisca dati personali.
        JLabel privacy = Glass.label("Informativa privacy (art. 13 GDPR)", 12, Font.PLAIN, Glass.ACCENT);
        privacy.setAlignmentX(Component.CENTER_ALIGNMENT);
        privacy.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        privacy.setToolTipText("Finalita', base giuridica, conservazione e diritti esercitabili");
        privacy.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                showPrivacyNotice();
            }
        });
        header.add(privacy);

        return header;
    }

    /** Spazio verticale vuoto in un BoxLayout. */
    private static Component verticalGap(int height) {
        Dimension d = new Dimension(1, height);
        return new Box.Filler(d, d, d);
    }

    private JComponent createBody() {
        JPanel body = Glass.row(new BorderLayout(0, 20), 0);
        body.add(createServerBar(), BorderLayout.NORTH);

        tabs = new Glass.Tabs();
        tabs.addTab("Accedi", createLoginTab());
        tabs.addTab("Crea account", createRegisterTab());
        tabs.addTab("Ospite", createGuestTab());
        body.add(tabs, BorderLayout.CENTER);

        return body;
    }

    /** Riga host: porta e host accorciati, con un comando per mostrarli. */
    private JComponent createServerBar() {
        Glass.Panel bar = new Glass.Panel(new BorderLayout(10, 10), Glass.RADIUS_SM, Glass.alpha(Glass.SURFACE_HI, 90), true);
        bar.setBorder(new EmptyBorder(12, 14, 12, 14));

        JLabel lbl = Glass.label("SERVER", 10, Font.BOLD, Glass.TEXT_FAINT);
        bar.add(lbl, BorderLayout.WEST);

        String defaultHost = System.getProperty("chess.host",
                System.getenv().getOrDefault("CHESS_HOST", "127.0.0.1"));

        JPanel fields = Glass.row(new GridLayout(1, 4, 8, 0), 8);
        fields.setOpaque(false);

        txtHost = new Glass.Field(defaultHost);
        txtHost.setFont(Glass.sans(12, Font.PLAIN));
        txtPort = new Glass.Field("12345");
        txtPort.setFont(Glass.sans(12, Font.PLAIN));
        txtPort.setHorizontalAlignment(JTextField.CENTER);

        fields.add(labeled("Host", txtHost));
        fields.add(labeled("Porta", txtPort));
        bar.add(fields, BorderLayout.CENTER);

        return bar;
    }

    private JComponent labeled(String text, JComponent field) {
        JPanel box = Glass.row(new BorderLayout(0, 4), 0);
        JLabel l = Glass.label(text, 10, Font.BOLD, Glass.TEXT_FAINT);
        box.add(l, BorderLayout.NORTH);
        box.add(field, BorderLayout.CENTER);
        return box;
    }

    private JComponent createLoginTab() {
        JPanel p = Glass.row(new GridLayout(0, 1, 0, 12), 0);
        p.setBorder(new EmptyBorder(4, 0, 0, 0));

        txtLoginUser = new Glass.Field("Username");
        txtLoginPass = new Glass.SecretField("Password");

        p.add(labeled("USERNAME", txtLoginUser));
        p.add(labeled("PASSWORD", txtLoginPass));

        Glass.Button btnLogin = new Glass.Button("Accedi", Glass.Button.Kind.PRIMARY);
        btnLogin.addActionListener(e -> performLogin());
        p.add(wrap(btnLogin, 8, 0, 0, 0));

        submitOnEnter(txtLoginUser, btnLogin);
        submitOnEnter(txtLoginPass, btnLogin);
        return p;
    }

    private JComponent createRegisterTab() {
        JPanel p = Glass.row(new GridLayout(0, 1, 0, 11), 0);
        p.setBorder(new EmptyBorder(4, 0, 0, 0));

        txtRegUser = new Glass.Field("Almeno 3 caratteri");
        txtRegPass = new Glass.SecretField("Almeno 8 caratteri");
        txtRegPassConfirm = new Glass.SecretField("Ripeti la password");

        // Eta': dato personale trattato per adempiere all'art. 8 GDPR. Il valore 0
        // significa "non dichiarata": la registrazione resta possibile, ma il server
        // non potra' applicare il controllo sull'eta' minima.
        txtAge = new Glass.Field("0");
        txtAge.setToolTipText("La tua eta'. Lascia 0 se preferisci non dichiararla.");
        txtAge.setHorizontalAlignment(JTextField.CENTER);

        JPanel ageRow = Glass.row(new BorderLayout(10, 0), 10);
        ageRow.setOpaque(false);
        ageRow.add(labeled("ETA' (0 = non dichiarare)", txtAge), BorderLayout.CENTER);

        p.add(labeled("USERNAME", txtRegUser));
        p.add(labeled("PASSWORD", txtRegPass));
        p.add(labeled("CONFERMA PASSWORD", txtRegPassConfirm));
        p.add(ageRow);

        Glass.Button btnRegister = new Glass.Button("Crea account", Glass.Button.Kind.SUCCESS);
        btnRegister.addActionListener(e -> performRegister());
        p.add(wrap(btnRegister, 8, 0, 0, 0));

        submitOnEnter(txtRegPassConfirm, btnRegister);
        return p;
    }

    private JComponent createGuestTab() {
        JPanel p = Glass.row(new BorderLayout(0, 18), 0);
        p.setBorder(new EmptyBorder(10, 0, 0, 0));

        JPanel top = Glass.row(new GridLayout(0, 1, 0, 14), 0);
        top.setOpaque(false);
        JLabel hint = Glass.label("Gioca subito, senza account. Le partite non verranno registrate sul tuo profilo.",
                12, Font.PLAIN, Glass.TEXT_DIM);
        hint.setHorizontalAlignment(SwingConstants.CENTER);
        top.add(hint);

        txtGuestNick = new Glass.Field("Ospite_" + (int) (Math.random() * 900 + 100));
        top.add(labeled("NICKNAME", txtGuestNick));
        p.add(top, BorderLayout.NORTH);

        // Il bottone va in fondo con la sua altezza naturale: dentro una griglia
        // si stirerebbe per riempire tutto lo spazio rimasto.
        Glass.Button btnGuest = new Glass.Button("Entra come ospite", Glass.Button.Kind.PRIMARY);
        btnGuest.addActionListener(e -> performGuestLogin());
        p.add(btnGuest, BorderLayout.SOUTH);

        submitOnEnter(txtGuestNick, btnGuest);
        return p;
    }

    private JComponent createStatusBar() {
        lblStatus = Glass.label("Pronto per la connessione", 12, Font.PLAIN, Glass.TEXT_DIM);
        lblStatus.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel bar = Glass.row(new BorderLayout(), 0);
        bar.setBorder(new EmptyBorder(16, 0, 0, 0));
        bar.add(lblStatus, BorderLayout.CENTER);
        return bar;
    }

    // ---------- Comportamento (invariato rispetto alla versione precedente) ----------

    /**
     * Collega il tasto Invio dei campi al bottone.
     *
     * <p>{@code setDefaultButton} da solo non basta: senza focus esplicito il campo
     * non e' il componente attivo e l'Invio non arriva da nessuna parte.
     */
    private void submitOnEnter(JTextField field, Glass.Button button) {
        field.addActionListener(e -> button.doClick());
    }

    private static JComponent wrap(JComponent c, int top, int left, int bottom, int right) {
        JPanel p = Glass.row(new BorderLayout(), 0);
        p.setBorder(new EmptyBorder(top, left, bottom, right));
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private void ensureConnected(Runnable afterConnect) {
        final String targetHost = txtHost.getText().trim();
        int port = 12345;
        try {
            port = Integer.parseInt(txtPort.getText().trim());
        } catch (NumberFormatException ignored) {}
        final int targetPort = port;

        if (network.isConnected()) {
            afterConnect.run();
            return;
        }

        setStatus("Connessione a " + targetHost + ":" + targetPort + "...", Glass.WARNING);
        network.connect(targetHost, targetPort);

        // Attende brevemente la connessione.
        new Thread(() -> {
            for (int i = 0; i < 25; i++) {
                if (network.isConnected()) {
                    SwingUtilities.invokeLater(() -> {
                        setStatus("Connesso al server", Glass.SUCCESS);
                        afterConnect.run();
                    });
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {}
            }
            SwingUtilities.invokeLater(() ->
                    setStatus("Impossibile raggiungere " + targetHost + ":" + targetPort, Glass.DANGER));
        }, "ChessLogin-Wait").start();
    }

    private void performLogin() {
        String u = txtLoginUser.getText().trim();
        String p = txtLoginPass.plainText().trim();
        if (u.isEmpty() || p.isEmpty()) {
            setStatus("Inserisci username e password", Glass.DANGER);
            return;
        }
        ensureConnected(() -> network.login(u, p));
    }

    private void performRegister() {
        String u = txtRegUser.getText().trim();
        String p = txtRegPass.plainText().trim();
        String pc = txtRegPassConfirm.plainText().trim();

        if (u.length() < 3) {
            setStatus("Lo username deve avere almeno 3 caratteri", Glass.DANGER);
            tabs.select(1);
            return;
        }
        if (p.length() < 8) {
            setStatus("La password deve avere almeno 8 caratteri", Glass.DANGER);
            tabs.select(1);
            return;
        }
        if (!p.equals(pc)) {
            setStatus("Le password non coincidono", Glass.DANGER);
            tabs.select(1);
            return;
        }

        int age = 0;
        String rawAge = txtAge.getText().trim();
        if (!rawAge.isEmpty()) {
            try {
                age = Integer.parseInt(rawAge);
            } catch (NumberFormatException ex) {
                setStatus("L'eta' deve essere un numero", Glass.DANGER);
                tabs.select(1);
                return;
            }
        }
        final int declaredAge = age;

        ensureConnected(() -> network.register(u, p, declaredAge));
    }

    private void performGuestLogin() {
        String nick = txtGuestNick.getText().trim();
        if (nick.isEmpty()) nick = "Ospite";
        final String finalNick = nick;

        ensureConnected(() -> {
            network.setName(finalNick);
            if (onAuthenticatedCallback != null) {
                onAuthenticatedCallback.run();
            }
        });
    }

    /** Registrazione riuscita: si torna alla tab di accesso con l'account compilato. */
    public void onRegistered(String username) {
        tabs.select(0);
        txtLoginUser.setText(username);
        txtLoginPass.setText("");
        txtLoginPass.requestFocusInWindow();
        setStatus("Account creato. Ora puoi accedere.", Glass.SUCCESS);
    }

    private void setStatus(String message, Color color) {
        lblStatus.setText(message);
        lblStatus.setForeground(color);
    }

    public void setStatusMessage(String message, boolean isError) {
        setStatus(message, isError ? Glass.DANGER : Glass.SUCCESS);
    }

    /**
     * Riepilogo dell'informativa privacy mostrato nel client. Il testo completo,
     * con i dati del titolare che solo il gestore puo' fornire, e' in docs/privacy.md.
     */
    private void showPrivacyNotice() {
        JTextPane area = new JTextPane();
        area.setEditable(false);
        area.setOpaque(false);
        area.setFont(Glass.sans(13, Font.PLAIN));
        area.setForeground(Glass.TEXT);
        area.setText(
                "TRATTAMENTO DEI DATI PERSONALI\n"
                + "---------------------------------------\n"
                + "Dati trattati: username, eta' dichiarata, statistiche di gioco\n"
                + "(vittorie, sconfitte, patte, ELO), lista amici, messaggi di chat.\n\n"
                + "Finalita': gestione del gioco e ranking. I messaggi di chat sono\n"
                + "inoltrati ai partecipanti e NON vengono conservati su disco.\n"
                + "Gli indirizzi IP non vengono registrati nei log.\n\n"
                + "Base giuridica: esecuzione del contratto (art. 6.1.b GDPR) per\n"
                + "l'uso del servizio; consenso (art. 6.1.a) per la lista amici.\n\n"
                + "Conservazione: finche' l'account resta attivo o fino alla\n"
                + "cancellazione. Non e' prevista scadenza automatica.\n\n"
                + "I tuoi diritti (art. 15-22): puoi in ogni momento\n"
                + "  EXPORT_DATA     per ottenere tutti i tuoi dati\n"
                + "  DELETE_ACCOUNT  per cancellare l'account\n"
                + "  LOGOUT          per uscire dall'account\n"
                + "La cancellazione e' irreversibile e rimuove anche le\n"
                + "amicizie che ti riguardano.\n\n"
                + "Minori: sotto i 16 anni l'uso richiede il consenso di un\n"
                + "genitore. Sotto i 13 anni non e' ammessa la registrazione.\n\n"
                + "Informativa completa: docs/privacy.md");

        JScrollPane scroll = new JScrollPane(area);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(new EmptyBorder(0, 0, 0, 0));
        scroll.getVerticalScrollBar().setOpaque(false);
        scroll.setPreferredSize(new Dimension(520, 420));

        Dialogs.custom(this, "Informativa privacy", scroll, "Ho letto", "Chiudi",
                () -> { }, null);
    }
}
