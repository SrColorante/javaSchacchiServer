package org.schacchi.client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * Dialoghi modali in stile vetro, al posto di {@link JOptionPane}.
 *
 * <p>{@code JOptionPane} porta con se' il LookAndFeel di sistema: pulsanti grigi,
 * icona a colori, bordo spesso. Dopo aver dipinto tutto il resto a mano, un popup
 * cosi' spicca come un errore. Qui ogni finestra e' costruita a mano con gli stessi
 * widget del tema e chiusa con lo stesso protocollo (Swing blocca il chiamante fino
 * a {@code setVisible(false)}).
 */
public final class Dialogs {

    private Dialogs() {}

    /**
     * Contenitore radice di un popup: finestra senza bordi che mostra solo la card
     * di vetro disegnata dentro.
     */
    private static final class Root extends JDialog {
        Root(Window owner, String title, JComponent content) {
            super(owner, title, ModalityType.APPLICATION_MODAL);
            setUndecorated(true);
            // Sfondo trasparente su finestra E contenitore: altrimenti il rettangolo
            // grigio del LookAndFeel resta visibile agli angoli arrotondati.
            setBackground(new Color(0, 0, 0, 0));
            getContentPane().setBackground(new Color(0, 0, 0, 0));
            setContentPane(content);
            pack();
            setLocationRelativeTo(owner);
        }
    }

    /** Riquadro di vetro con pastiglia d'accento, titolo e corpo centrato. */
    private static Glass.Panel header(String title, Color accent) {
        Glass.Panel card = new Glass.Panel(new BorderLayout(0, 16), Glass.RADIUS, Glass.SURFACE, true);
        card.setBorder(new EmptyBorder(26, 32, 24, 32));

        JPanel head = Glass.row(new BorderLayout(12, 0), 12);
        if (accent != null) {
            JPanel dot = new Glass.Panel(new GridBagLayout(), 6, accent, false);
            dot.setPreferredSize(new Dimension(12, 12));
            dot.setMinimumSize(new Dimension(12, 12));
            head.add(dot, BorderLayout.WEST);
        }
        if (title != null) {
            head.add(Glass.label(title, 18, Font.BOLD, Glass.TEXT), BorderLayout.CENTER);
        }
        card.add(head, BorderLayout.NORTH);
        return card;
    }

    /**
     * Etichetta con il corpo del messaggio, centrata e con a capo.
     *
     * <p>Il testo passa in HTML perche' in una {@link JLabel} il {@code \n} non
     * produce un ritorno a capo: senza questa conversione i messaggi su piu' righe
     * finiscono tutti attaccati e vengono tagliati dal bordo del popup.
     */
    private static JLabel messageLabel(String body) {
        String html = "<html><body style='width:340px;text-align:center'>"
                + escapeHtml(body).replace("\n", "<br>")
                + "</body></html>";
        JLabel message = Glass.label(html, 14, Font.PLAIN, Glass.TEXT_DIM);
        message.setHorizontalAlignment(SwingConstants.CENTER);
        return message;
    }

    /**
     * Escappa i caratteri HTML del testo.
     *
     * <p>Il corpo arriva da nome stanza, nomi utente e messaggi del server: senza
     * questo, un "&amp;" o una "&lt;" in un nome rompe il rendering del popup.
     */
    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static JPanel messageWrap(String body) {
        JPanel wrap = Glass.row(new GridBagLayout(), 0);
        wrap.add(messageLabel(body));
        return wrap;
    }

    private static JPanel buttonRow(JButton... buttons) {
        JPanel row = Glass.row(new FlowLayout(FlowLayout.RIGHT, 8, 0), 8);
        for (JButton b : buttons) {
            row.add(b);
        }
        return row;
    }

    private static Glass.Button makeButton(String text, Glass.Button.Kind kind) {
        Glass.Button b = new Glass.Button(text, kind);
        b.setPreferredSize(new Dimension(Math.max(96, text.length() * 9 + 40), 40));
        return b;
    }

    // ---------- API pubblica ----------

    /** Messaggio informativo. */
    public static void info(Component owner, String title, String message) {
        show(owner, title, message, new Glass.Button[0], Glass.ACCENT);
    }

    /** Messaggio d'errore. */
    public static void error(Component owner, String title, String message) {
        show(owner, title, message, new Glass.Button[0], Glass.DANGER);
    }

    /** Messaggio d'avviso. */
    public static void warn(Component owner, String title, String message) {
        show(owner, title, message, new Glass.Button[0], Glass.WARNING);
    }

    /**
     * Dialogo di conferma.
     *
     * @return true se l'utente ha confermato
     */
    public static boolean confirm(Component owner, String title, String message) {
        return confirm(owner, title, message, Glass.Button.Kind.PRIMARY);
    }

    public static boolean confirm(Component owner, String title, String message, Glass.Button.Kind confirmKind) {
        Glass.Button yes = makeButton("Si", confirmKind);
        Glass.Button no = makeButton("No", Glass.Button.Kind.GHOST);
        final boolean[] result = {false};
        yes.addActionListener(e -> { result[0] = true; close(yes); });
        no.addActionListener(e -> { result[0] = false; close(no); });
        show(owner, title, message, new Glass.Button[]{yes, no}, Glass.ACCENT);
        return result[0];
    }

    /**
     * Richiesta di una riga di testo.
     *
     * @return il testo inserito, oppure null se annullato
     */
    public static String ask(Component owner, String title, String message, String placeholder) {
        Glass.Field input = new Glass.Field(placeholder);
        input.setPreferredSize(new Dimension(340, 42));

        Glass.Button ok = makeButton("Crea", Glass.Button.Kind.PRIMARY);
        Glass.Button cancel = makeButton("Annulla", Glass.Button.Kind.GHOST);
        final String[] result = {null};
        ok.addActionListener(e -> { result[0] = input.getText().trim(); close(ok); });
        cancel.addActionListener(e -> { result[0] = null; close(cancel); });
        // Invio conferma, come in ogni campo di testo a Riga singola.
        input.addActionListener(e -> ok.doClick());

        Glass.Panel card = header(title, Glass.ACCENT);
        card.add(messageWrap(message), BorderLayout.CENTER);
        card.add(input, BorderLayout.CENTER);
        card.add(buttonRow(cancel, ok), BorderLayout.SOUTH);

        showCard(owner, title, card, ok);
        return result[0];
    }

    /**
     * Dialogo con contenuto arbitrario, per i casi che non sono un semplice
     * messaggio (per esempio l'informativa privacy o la cancellazione account).
     *
     * @param onConfirm invocato quando l'utente conferma
     * @param onCancel  invocato quando annulla, puo' essere null
     */
    public static void custom(Component owner, String title, JComponent content,
                              String confirmLabel, String cancelLabel,
                              Runnable onConfirm, Runnable onCancel) {
        Glass.Button ok = makeButton(confirmLabel, Glass.Button.Kind.PRIMARY);
        Glass.Button cancel = makeButton(cancelLabel, Glass.Button.Kind.GHOST);
        ok.addActionListener(e -> { close(ok); onConfirm.run(); });
        cancel.addActionListener(e -> { close(cancel); if (onCancel != null) onCancel.run(); });

        Glass.Panel card = new Glass.Panel(new BorderLayout(0, 18), Glass.RADIUS, Glass.SURFACE, true);
        card.setBorder(new EmptyBorder(26, 32, 24, 32));
        card.add(content, BorderLayout.CENTER);
        card.add(buttonRow(cancel, ok), BorderLayout.SOUTH);

        showCard(owner, title, card, ok);
    }

    /**
     * Mostra una card arbitraria senza aggiungere pulsanti: utile quando il contenuto
     * contiene gia' i propri comandi (per esempio il selettore di promozione, dove
     * quattro pulsanti affiancati sono l'unica azione possibile).
     */
    public static void bare(Component owner, String title, JComponent content) {
        showCard(owner, title, content, null);
    }

    // ---------- Interno ----------

    private static void show(Component owner, String title, String message,
                             Glass.Button[] buttons, Color accent) {
        Glass.Panel card = header(title, accent);
        card.add(messageWrap(message), BorderLayout.CENTER);
        if (buttons.length > 0) {
            card.add(buttonRow(buttons), BorderLayout.SOUTH);
        }
        showCard(owner, title, card, buttons.length == 0 ? null : buttons[0]);
    }

    private static void showCard(Component owner, String title, JComponent content, JButton defaultButton) {
        // Swing non e' thread-safe: costruire o mostrare una finestra fuori dall'EDT
        // funziona a intermittenza (semicerie, finestre invisibili, blocchi). Qui si
        // rimanda sempre all'EDT e si aspetta che sia lui a chiudere.
        if (!SwingUtilities.isEventDispatchThread()) {
            try {
                SwingUtilities.invokeAndWait(() -> showCard(owner, title, content, defaultButton));
            } catch (Exception e) {
                throw new IllegalStateException("Impossibile aprire il dialogo", e);
            }
            return;
        }

        Root dialog = new Root(windowOf(owner), title, content);

        // Esc chiude: senza questo il popup sembra bloccato a schermo.
        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.setVisible(false),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        if (defaultButton != null) {
            // L'Invio attiva subito l'azione predefinita, come in ogni dialogo.
            dialog.getRootPane().setDefaultButton(defaultButton);
        }

        dialog.setVisible(true);
        dialog.dispose();
    }

    private static Window windowOf(Component c) {
        return c == null ? null : SwingUtilities.getWindowAncestor(c);
    }

    /** Chiude il dialogo che contiene il bottone premuto. */
    private static void close(JButton button) {
        Window w = SwingUtilities.getWindowAncestor(button);
        if (w != null) w.setVisible(false);
    }
}
