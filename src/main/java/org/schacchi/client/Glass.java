package org.schacchi.client;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Kit di base dello stile "liquid glass" del client.
 *
 * <p>L'obiettivo e' una superfici continua e translucida invece dei riquadri grigi di
 * default Swing. Il vetro e' reale, non un'illusione: ogni pannello qui sotto
 * sfonda su uno sfondo disegnato a mano, cosicche' la trasparenza si vede.
 *
 * <p>Tutto e' dipinto a mano in {@code paintComponent} invece di affidarsi a Border e
 * LookAndFeel: il LAF di sistema reintroduce sempre bordi, sfondi opachi e titoli
 * che rompevano l'effetto.
 */
public final class Glass {

    private Glass() {}

    // Palette scura, satura ma non acida: su schermi OLED il nero puro stanca.
    public static final Color BG_DEEP     = new Color(0x0B0D14);
    public static final Color BG          = new Color(0x10131C);
    public static final Color SURFACE     = alpha(new Color(0x1A1E2B), 190);
    public static final Color SURFACE_HI  = alpha(new Color(0x222738), 200);

    public static final Color TEXT        = new Color(0xEDF0F7);
    public static final Color TEXT_DIM    = new Color(0x8B93A7);
    public static final Color TEXT_FAINT  = new Color(0x5C6377);

    public static final Color ACCENT      = new Color(0x6E8BFF);
    public static final Color ACCENT_HI   = new Color(0x8AA0FF);
    public static final Color SUCCESS     = new Color(0x4ADE80);
    public static final Color DANGER      = new Color(0xFF6B6B);
    public static final Color WARNING     = new Color(0xFBBF24);

    /** Scacchiera: due grigi neutri, i pezzi si staccano senza colori da giocattolo. */
    public static final Color BOARD_LIGHT  = new Color(0xB9C2D0);
    public static final Color BOARD_DARK   = new Color(0x5A6478);
    public static final Color BOARD_EDGE   = new Color(0x2A3040);

    public static final int RADIUS  = 20;
    public static final int RADIUS_SM = 12;
    public static final int GAP = 14;

    private static String sansFamily, monoFamily;

    /**
     * Famiglia sans dell'interfaccia.
     *
     * <p>La ricerca avviene una volta sola e confronta il nome che il sistema
     * restituisce davvero: chiedere solo {@code canDisplay} darebbe un falso
     * positivo, perche' il JDK risolve ogni nome a un fallback senza avvisare.
     */
    private static String resolve(String... families) {
        String probe = new Font("Dialog", Font.PLAIN, 14).getFamily();
        for (String family : families) {
            if (probe.equals(new Font(family, Font.PLAIN, 14).getFamily())) {
                return family;
            }
        }
        return families[0];
    }

    public static String sansFamily() {
        if (sansFamily == null) sansFamily = resolve("Inter", "Segoe UI", "SF Pro Text", "Ubuntu", "Cantarell", "SansSerif");
        return sansFamily;
    }

    public static String monoFamily() {
        if (monoFamily == null) monoFamily = resolve("JetBrains Mono", "Fira Code", "Cascadia Mono", "DejaVu Sans Mono", "Monospaced");
        return monoFamily;
    }

    /** Stack tipografico: Inter se installata, altrimenti i fallback di sistema. */
    public static Font sans(int size, int style) {
        return new Font(sansFamily(), style, size);
    }

    public static Font mono(int size, int style) {
        return new Font(monoFamily(), style, size);
    }

    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    /** Mescola due colori; {@code t=0} restituisce {@code from}. */
    public static Color mix(Color from, Color to, double t) {
        double k = Math.max(0, Math.min(1, t));
        return new Color(
                (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * k),
                (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * k),
                (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * k));
    }

    public static Graphics2D prep(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g2;
    }

    public static RoundRectangle2D round(int x, int y, int w, int h, int r) {
        return new RoundRectangle2D.Float(x, y, w, h, r * 2f, r * 2f);
    }

    /** Come {@link #round} ma con coordinate frazionarie: serve per i bordi a mezzapixel. */
    public static RoundRectangle2D roundF(double x, double y, double w, double h, int r) {
        return new RoundRectangle2D.Double(x, y, w, h, r * 2.0, r * 2.0);
    }

    /**
     * Bordo con luce in alto: e' il dettaglio che fa leggere una superficie come
     * vetro illuminato dall'alto invece che come un rettangolo piatto.
     */
    public static void glassBorder(Graphics2D g2, int w, int h, int r) {
        g2.setPaint(new GradientPaint(
                0, 0, alpha(Color.WHITE, 70),
                0, h, alpha(Color.WHITE, 12)));
        g2.setStroke(new BasicStroke(1.1f));
        g2.draw(roundF(0.5f, 0.5f, w - 1, h - 1, r));
    }

    // ---------- Sfondo aurora ----------

    /**
     * Disegna lo sfondo: gradiente profondo con due macchie di colore in movimento
     * lento. E' il "dietro il vetro" che rende visibili le superfici translucide.
     */
    public static void paintAurora(Graphics2D g2, int w, int h, long t) {
        g2.setPaint(new GradientPaint(0, 0, BG_DEEP, w, h, new Color(0x151A2C)));
        g2.fillRect(0, 0, w, h);

        double phase = t / 1000.0;
        blob(g2, w, h, 0.30, 0.24, Math.PI * 2 * (phase * 0.045), w * 0.55f, ACCENT, 60);
        blob(g2, w, h, 0.76, 0.72, Math.PI * 2 * (phase * 0.035) + 2.1, w * 0.48f, new Color(0xB06BFF), 48);
        blob(g2, w, h, 0.12, 0.86, Math.PI * 2 * (phase * 0.028) + 4.0, w * 0.42f, new Color(0x2BD9C8), 34);
    }

    private static void blob(Graphics2D g2, int w, int h, double ax, double ay, double phase, float radius, Color color, int alpha) {
        double x = w * (ax + 0.10 * Math.cos(phase));
        double y = h * (ay + 0.10 * Math.sin(phase * 1.31));
        float r = radius * (0.85f + 0.15f * (float) Math.sin(phase * 0.7));

        // RadialGradientFill con alpha decrescente: niente sfumature a strisce.
        g2.setPaint(new RadialGradientPaint(
                new java.awt.geom.Point2D.Double(x, y), r,
                new float[]{0f, 0.55f, 1f},
                new Color[]{alpha(color, alpha), alpha(color, alpha / 3), alpha(color, 0)}));
        g2.fill(new java.awt.geom.Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
    }

    // ---------- Widget ----------

    /** Pannello di vetro con bordo arrotondato e luce superiore. */
    public static class Panel extends JPanel {
        private final int radius;
        private Color tint;
        private boolean outlined;

        public Panel() { this(new BorderLayout(), RADIUS, SURFACE, true); }

        public Panel(LayoutManager layout) { this(layout, RADIUS, SURFACE, true); }

        public Panel(LayoutManager layout, int radius, Color tint, boolean outlined) {
            super(layout);
            this.radius = radius;
            this.tint = tint;
            this.outlined = outlined;
            setOpaque(false);
        }

        /**
         * Cambia la tinta del vetro a runtime.
         *
         * <p>Serve alle pastiglie che indicano il colore del pezzo: se la tinta fosse
         * finale, ogni colore richiederebbe un pannello nuovo da costruire a mano.
         */
        public void setSurface(Color tint) {
            this.tint = tint;
            repaint();
        }

        public void setOutlined(boolean outlined) {
            this.outlined = outlined;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = prep(g);
            g2.setColor(tint);
            g2.fill(round(0, 0, getWidth(), getHeight(), radius));
            if (outlined) glassBorder(g2, getWidth(), getHeight(), radius);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Contenitore che disegna lo sfondo animato una volta sola per tutta la finestra. */
    public static class Backdrop extends JPanel {
        private final Timer timer;

        public Backdrop(LayoutManager layout) {
            super(layout);
            setOpaque(false);
            timer = new Timer(33, e -> repaint());
            timer.setCoalesce(true);
        }

        public void start() { timer.start(); }

        public void stop() { timer.stop(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = prep(g);
            paintAurora(g2, getWidth(), getHeight(), System.currentTimeMillis());
            g2.dispose();
        }
    }

    /**
     * Bottone a pillola con hover e press animati.
     *
     * <p>Sovrascrive {@code paintComponent} perche' il LAF di sistema dipingerebbe
     * sopra il gradiente un rettangolo opaco e un bordo rettangolare.
     */
    public static class Button extends JButton {
        public enum Kind { PRIMARY, GHOST, DANGER, SUCCESS }

        private final Kind kind;
        private float hover;
        private float press;
        private Timer animator;

        public Button(String text, Kind kind) {
            super(text);
            this.kind = kind;
            setFont(sans(13, Font.BOLD));
            setBorder(new EmptyBorder(10, 18, 10, 18));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            // Il colore del testo lo decide il bottone, non il LAF: sui pulsanti chiari
            // il default grigio di sistema diventa illeggibile.
            setForeground(kind == Kind.GHOST ? TEXT : Color.WHITE);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { animate(hover, 1f); }
                @Override public void mouseExited(java.awt.event.MouseEvent e) { animate(hover, 0f); pressTo(0f); }
                @Override public void mousePressed(java.awt.event.MouseEvent e) { pressTo(1f); }
                @Override public void mouseReleased(java.awt.event.MouseEvent e) { pressTo(0f); }
            });
        }

        private void animate(float from, float to) {
            if (animator != null && animator.isRunning()) return;
            final float start = from;
            animator = new Timer(16, new java.awt.event.ActionListener() {
                float v = start;
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    v += (to - v) * 0.28f;
                    if (Math.abs(to - v) < 0.01f) { v = to; ((Timer) e.getSource()).stop(); }
                    hover = v;
                    repaint();
                }
            });
            animator.start();
        }

        private void pressTo(float to) {
            press = to;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = prep(g);
            int w = getWidth(), h = getHeight();
            int r = h / 2;

            Color base = switch (kind) {
                case PRIMARY -> ACCENT;
                case SUCCESS -> new Color(0x2F9E5F);
                case DANGER  -> new Color(0xD64550);
                case GHOST   -> SURFACE;
            };

            if (isEnabled()) {
                if (kind == Kind.GHOST) {
                    g2.setColor(alpha(Color.WHITE, (int) (14 + 26 * hover)));
                    g2.fill(round(0, 0, w, h, r));
                    g2.setPaint(new GradientPaint(0, 0, alpha(Color.WHITE, (int) (46 + 60 * hover)),
                            0, h, alpha(Color.WHITE, 8)));
                    g2.setStroke(new BasicStroke(1.1f));
                    g2.draw(roundF(0.5f, 0.5f, w - 1, h - 1, r));
                } else {
                    g2.setPaint(new GradientPaint(0, 0,
                            mix(base, Color.WHITE, 0.18 + 0.12 * hover), 0, h,
                            mix(base, Color.BLACK, 0.22)));
                    g2.fill(round(0, 0, w, h - (int) (2 * press), r));
                    // Riflesso in alto sul pillola: da' volume al bottone.
                    g2.setColor(alpha(Color.WHITE, (int) (34 + 40 * hover)));
                    g2.fill(round(0, 0, w, h / 2, r));
                }
            } else {
                g2.setColor(alpha(Color.WHITE, 10));
                g2.fill(round(0, 0, w, h, r));
            }

            g2.dispose();

            super.paintComponent(g);
        }

        @Override
        protected void paintBorder(Graphics g) {}

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(d.width, Math.max(40, d.height));
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            // Il colore segue lo stato: senza questo un bottone disabilitato
            // resterebbe con la tinta piena e sembrerebbe ancora cliccabile.
            setForeground(!enabled ? TEXT_FAINT : (kind == Kind.GHOST ? TEXT : Color.WHITE));
        }
    }

    /**
     * Tab interattive disegnate a mano, in stile pillola.
     *
     * <p>{@link JTabbedPane} porta con se' bordi, angoli e sfondi che nessun tema
     * Swing riesce a rendere trasparenti: qui si dipingono solo i titoli e il resto
     * resta vetro.
     */
    public static class Tabs extends JPanel {
        private final List<String> titles = new ArrayList<>();
        private final List<Component> cards = new ArrayList<>();
        private final CardLayout cardLayout = new CardLayout();
        private final JPanel cardHost = new JPanel(cardLayout);
        private int selected;
        private int hover = -1;

        public Tabs() {
            super(new BorderLayout(0, 14));
            setOpaque(false);
            cardHost.setOpaque(false);

            add(createBar(), BorderLayout.NORTH);
            add(cardHost, BorderLayout.CENTER);
        }

        private JComponent createBar() {
            Glass.Tabs self = this;
            JPanel bar = new JPanel(null) {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = prep(g);
                    int h = getHeight();
                    int x = 0;
                    for (int i = 0; i < titles.size(); i++) {
                        int w = tabWidth(i, g2);
                        boolean active = i == selected;
                        int lit = active ? 255 : (i == hover ? 128 : 0);

                        if (active) {
                            g2.setPaint(new GradientPaint(0, 0, alpha(Color.WHITE, 26), 0, h, alpha(Color.WHITE, 10)));
                            g2.fill(round(x, 0, w, h, h / 2));
                            g2.setColor(alpha(ACCENT, 150));
                            g2.fill(roundF(x + w / 2f - 12, h - 3, 24, 3, 2));
                        } else if (lit > 0) {
                            g2.setColor(alpha(Color.WHITE, 16 * lit));
                            g2.fill(round(x, 0, w, h, h / 2));
                        }

                        g2.setFont(sans(13, active ? Font.BOLD : Font.PLAIN));
                        g2.setColor(active ? TEXT : mix(TEXT_DIM, TEXT, lit * 0.5));
                        FontMetrics fm = g2.getFontMetrics();
                        String title = titles.get(i);
                        g2.drawString(title, x + (w - fm.stringWidth(title)) / 2f,
                                (h + fm.getAscent() - fm.getDescent()) / 2f);
                        x += w;
                    }
                    g2.dispose();
                }
            };
            bar.setOpaque(false);
            bar.setPreferredSize(new Dimension(200, 42));
            bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
            bar.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) { self.selectAt(e.getX()); }
            });
            bar.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                    int previous = self.hover;
                    self.hover = self.indexAt(e.getX());
                    if (previous != self.hover) bar.repaint();
                }
            });
            return bar;
        }

        private int tabWidth(int index, Graphics2D g2) {
            g2.setFont(sans(13, index == selected ? Font.BOLD : Font.PLAIN));
            return g2.getFontMetrics().stringWidth(titles.get(index)) + 34;
        }

        /** Indice della tab sotto il pixel x, o -1 se il punto e' oltre l'ultima. */
        private int indexAt(int x) {
            // Misura su un contesto grafico temporaneo: getGraphics() su un pannello
            // non ancora visualizzato restituisce null e non puo' essere usato qui.
            Graphics2D measure = prep(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics());
            int acc = 0;
            for (int i = 0; i < titles.size(); i++) {
                acc += tabWidth(i, measure);
                if (x < acc) return i;
            }
            measure.dispose();
            return -1;
        }

        private void selectAt(int x) {
            int index = indexAt(x);
            if (index >= 0) select(index);
        }

        public void addTab(String title, Component card) {
            titles.add(title);
            cards.add(card);
            String key = "tab" + cards.size();
            cardHost.add(card, key);
            if (cards.size() == 1) {
                selected = 0;
                cardLayout.show(cardHost, key);
            }
            // La barra e' ridisegnata a mano: senza questo la nuova tab non
            // apparirebbe fino al prossimo repaint accidentale.
            revalidate();
            repaint();
        }

        public void select(int index) {
            if (index < 0 || index >= cards.size() || index == selected) return;
            selected = index;
            cardLayout.show(cardHost, "tab" + (index + 1));
            revalidate();
            repaint();
        }

        public int selectedIndex() {
            return selected;
        }
    }

    /**
     * Sfondo comune ai campi di testo e password: vetrino scuro, bordo arrotondato
     * e riflesso d'accento che si accende in focus.
     *
     * <p>Il disegno avviene prima di {@code super.paintComponent} e su {@code setOpaque(false)}:
     * {@code BasicTextFieldUI} controlla quel flag e salta il riempimento dello sfondo,
     * quindi il testo del campo si stende sul vetro invece che su un rettangolo grigio.
     */
    private static void paintFieldBackdrop(Graphics2D g2, int w, int h, boolean focused) {
        int r = RADIUS_SM;
        g2.setColor(alpha(Color.BLACK, 92));
        g2.fill(round(0, 0, w, h, r));
        g2.setPaint(new GradientPaint(0, 0,
                alpha(focused ? ACCENT_HI : Color.WHITE, focused ? 70 : 20),
                0, h, alpha(Color.WHITE, 4)));
        g2.setStroke(new BasicStroke(focused ? 1.4f : 1.1f));
        g2.draw(roundF(0.5f, 0.5f, w - 1, h - 1, r));
    }

    private static void styleField(JTextComponent field) {
        field.setOpaque(false);
        field.setFont(sans(14, Font.PLAIN));
        field.setForeground(TEXT);
        field.setCaretColor(ACCENT_HI);
        field.setBorder(new EmptyBorder(11, 15, 11, 15));
        field.setSelectionColor(alpha(ACCENT, 110));
        field.setSelectedTextColor(Color.WHITE);
    }

    private static void paintPlaceholder(Graphics2D g2, int w, int h, String text) {
        if (text == null || text.isEmpty()) return;
        g2.setFont(sans(14, Font.PLAIN));
        g2.setColor(alpha(TEXT_FAINT, 200));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, 15, (h + fm.getAscent() - fm.getDescent()) / 2);
    }

    /** Campo di testo con etichetta segnaposto disegnata a mano. */
    public static class Field extends JTextField {
        private final String placeholder;

        public Field(String placeholder) {
            this.placeholder = placeholder;
            styleField(this);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = prep(g);
            paintFieldBackdrop(g2, getWidth(), getHeight(), isFocusOwner());
            if (getText().isEmpty()) paintPlaceholder(g2, getWidth(), getHeight(), placeholder);
            g2.dispose();
            super.paintComponent(g);
        }

        @Override
        protected void paintBorder(Graphics g) {}
    }

    /** Campo password: stesso vetro, testo mascherato. */
    public static class SecretField extends JPasswordField {
        private final String placeholder;

        public SecretField(String placeholder) {
            this.placeholder = placeholder;
            setEchoChar('•');
            styleField(this);
        }

        public String plainText() {
            return new String(getPassword());
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = prep(g);
            paintFieldBackdrop(g2, getWidth(), getHeight(), isFocusOwner());
            if (getPassword().length == 0) paintPlaceholder(g2, getWidth(), getHeight(), placeholder);
            g2.dispose();
            super.paintComponent(g);
        }

        @Override
        protected void paintBorder(Graphics g) {}
    }

    /** Area di testo su fondo di vetro, per log, chat e dati esportati. */
    public static class Area extends JTextArea {
        public Area() {
            setOpaque(false);
            setFont(sans(13, Font.PLAIN));
            setForeground(TEXT);
            setCaretColor(ACCENT_HI);
            setLineWrap(true);
            setWrapStyleWord(true);
            setBorder(new EmptyBorder(12, 14, 12, 14));
            setSelectionColor(alpha(ACCENT, 110));
            setSelectedTextColor(Color.WHITE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = prep(g);
            paintFieldBackdrop(g2, getWidth(), getHeight(), isFocusOwner());
            g2.dispose();
            super.paintComponent(g);
        }

        @Override
        protected void paintBorder(Graphics g) {}
    }

    /** Etichetta stilizzata, senza i bordi e i colori di default. */
    public static JLabel label(String text, int size, int style, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(sans(size, style));
        l.setForeground(color);
        l.setBorder(new EmptyBorder(0, 0, 0, 0));
        return l;
    }

    public static JLabel title(String text)  { return label(text, 26, Font.BOLD, TEXT); }
    public static JLabel body(String text)   { return label(text, 14, Font.PLAIN, TEXT); }
    public static JLabel muted(String text)  { return label(text, 12, Font.PLAIN, TEXT_DIM); }

    /**
     * Contenitore trasparente con un layout.
     *
     * <p>Tutti i pannelli del client passano di qui: il trucco per non vedere bordi
     * e fondi grigi del LookAndFeel e' un {@code JPanel} completamente trasparente
     * che fa solo da contenitore.
     */
    public static JPanel row(LayoutManager layout, int gap) {
        JPanel p = new JPanel(layout);
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(0, 0, 0, 0));
        return p;
    }

    /**
     * Applica il tema Swing globale.
     *
     * <p>Si eliminano etichette, bordi e sfondi opachi dei componenti di sistema:
     * senza questo, scrollbar, popup e combo box resterebbero grigi eromperebbero
     * l'unico pannello curato.
     */
    public static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {}

        UIManager.put("Panel.background", new Color(0, 0, 0, 0));
        UIManager.put("OptionPane.background", BG);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Label.font", sans(14, Font.PLAIN));
        UIManager.put("TextField.background", alpha(Color.BLACK, 90));
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", ACCENT);
        UIManager.put("TextField.selectionBackground", alpha(ACCENT, 90));
        UIManager.put("TextField.selectionForeground", Color.WHITE);
        UIManager.put("PasswordField.background", alpha(Color.BLACK, 90));
        UIManager.put("PasswordField.foreground", TEXT);
        UIManager.put("PasswordField.caretForeground", ACCENT);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.background", alpha(Color.WHITE, 6));
        UIManager.put("ScrollBar.thumb", alpha(Color.WHITE, 46));
        UIManager.put("ScrollBar.thumbShadow", alpha(Color.WHITE, 0));
        UIManager.put("ScrollBar.track", alpha(Color.WHITE, 0));
        UIManager.put("TabbedPane.background", new Color(0, 0, 0, 0));
        UIManager.put("TabbedPane.foreground", TEXT_DIM);
        UIManager.put("TabbedPane.selected", TEXT);
        UIManager.put("TabbedPane.contentAreaInsets", new Insets(0, 0, 0, 0));
        UIManager.put("ToolTip.background", alpha(new Color(0x1A1E2B), 240));
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("ToolTip.font", sans(12, Font.PLAIN));
        UIManager.put("ToolTip.border", BorderFactory.createEmptyBorder(6, 9, 6, 9));
        UIManager.put("Table.background", new Color(0, 0, 0, 0));
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.gridColor", alpha(Color.WHITE, 10));
        UIManager.put("Table.selectionBackground", alpha(ACCENT, 46));
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("Table.showGrid", Boolean.FALSE);
        UIManager.put("Table.intercellSpacing", new Dimension(0, 0));
        UIManager.put("Table.showVerticalLines", Boolean.FALSE);
        UIManager.put("TableHeader.background", new Color(0, 0, 0, 0));
        UIManager.put("TableHeader.foreground", TEXT_FAINT);
        UIManager.put("TableHeader.font", sans(11, Font.BOLD));
    }

}
