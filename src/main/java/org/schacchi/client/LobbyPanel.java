package org.schacchi.client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.util.List;

/**
 * Lobby: stanze, matchmaking, amici, profilo e diritti GDPR.
 *
 * <p>Le quattro sezioni stanno in tab a pillola e condividono la stessa area di vetro,
 * quindi l'altezza non salta quando si passa da una sezione all'altra.
 */
public class LobbyPanel extends JPanel {
    private final ClientNetwork network;

    private JLabel lblUserHeader;
    private JLabel lblEloHeader;
    private JLabel lblStatsHeader;
    private JLabel lblSessionHeader;

    // Stanze
    private DefaultTableModel roomsTableModel;
    private JTable roomsTable;
    private JPanel listHost;
    private JLabel lblNoRooms;
    private Glass.Field txtJoinCode;

    // Amici
    private DefaultListModel<FriendInfo> friendsListModel;
    private JList<FriendInfo> friendsList;
    private Glass.Field txtAddFriend;

    // Profilo
    private JLabel lblProfileName;
    private JLabel lblProfileElo;
    private JLabel lblProfileRecord;
    private JLabel lblProfileWinrate;

    // Dati personali
    private Glass.Area privacyDataArea;
    private JLabel lblPrivacyStatus;
    private final Glass.Button btnExportData = new Glass.Button("Scarica i miei dati", Glass.Button.Kind.GHOST);
    private final Glass.Button btnLogout = new Glass.Button("Esci dall'account", Glass.Button.Kind.GHOST);
    private final Glass.Button btnDeleteAccount = new Glass.Button("Cancella account", Glass.Button.Kind.DANGER);

    public LobbyPanel(ClientNetwork network) {
        this.network = network;
        setOpaque(false);
        setLayout(new BorderLayout(Glass.GAP, Glass.GAP));
        setBorder(new EmptyBorder(Glass.GAP, Glass.GAP, Glass.GAP, Glass.GAP));

        add(createHeader(), BorderLayout.NORTH);

        Glass.Panel body = new Glass.Panel(new BorderLayout(), Glass.RADIUS, Glass.SURFACE, true);
        body.setBorder(new EmptyBorder(6, 20, 20, 20));

        Glass.Tabs tabs = new Glass.Tabs();
        tabs.addTab("Stanze", createRoomsTab());
        tabs.addTab("Amici", createFriendsTab());
        tabs.addTab("Profilo", createProfileTab());
        tabs.addTab("I miei dati", createPrivacyTab());
        body.add(tabs, BorderLayout.CENTER);

        add(body, BorderLayout.CENTER);
    }

    // ---------- Header ----------

    private JComponent createHeader() {
        Glass.Panel header = new Glass.Panel(new BorderLayout(16, 0), Glass.RADIUS, Glass.SURFACE, true);
        header.setBorder(new EmptyBorder(14, 20, 14, 20));

        // Pastiglia con l'iniziale: riconosce il profilo senza rubare spazio.
        JPanel avatar = new Glass.Panel(new GridBagLayout(), 22, Glass.alpha(Glass.ACCENT, 42), true);
        avatar.setPreferredSize(new Dimension(44, 44));
        lblUserHeader = Glass.label("C", 20, Font.BOLD, Glass.TEXT);
        avatar.add(lblUserHeader);

        JPanel userBox = Glass.row(new GridLayout(0, 1, 0, 2), 0);
        lblEloHeader = Glass.label("ELO 1200", 13, Font.BOLD, Glass.WARNING);
        userBox.add(lblEloHeader);
        lblSessionHeader = Glass.muted("In attesa di login");
        userBox.add(lblSessionHeader);

        lblStatsHeader = Glass.label("", 12, Font.PLAIN, Glass.TEXT_DIM);
        lblStatsHeader.setHorizontalAlignment(SwingConstants.RIGHT);

        JPanel right = Glass.row(new GridLayout(0, 1, 0, 2), 0);
        right.add(lblStatsHeader);
        JLabel host = Glass.label("game.cristianrenosto.party", 12, Font.PLAIN, Glass.TEXT_FAINT);
        host.setHorizontalAlignment(SwingConstants.RIGHT);
        right.add(host);

        header.add(avatar, BorderLayout.WEST);
        header.add(userBox, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    // ---------- Stanze ----------

    private JComponent createRoomsTab() {
        JPanel panel = Glass.row(new BorderLayout(12, 12), 12);

        // Tre azioni primarie in fila, tutte della stessa importanza visiva.
        JPanel actions = Glass.row(new GridLayout(1, 3, 10, 0), 10);
        actions.setOpaque(false);

        Glass.Button quickMatch = new Glass.Button("Partita rapida", Glass.Button.Kind.PRIMARY);
        quickMatch.setToolTipText("Coda FIFO: ti incontri col primo che arriva");
        quickMatch.addActionListener(e -> {
            network.quickMatch();
            info("Sei in coda per il matchmaking.\nAppena un avversario si unisce la partita iniziera' automaticamente.");
        });

        Glass.Button createRoom = new Glass.Button("Crea stanza", Glass.Button.Kind.GHOST);
        createRoom.addActionListener(e -> {
            String roomName = Dialogs.ask(this, "Crea stanza",
                    "Dai un nome alla stanza, così chi entra sa cosa aspettarsi.",
                    "Nome stanza");
            if (roomName != null && !roomName.isEmpty()) {
                network.createRoom(roomName);
            }
        });

        Glass.Button refresh = new Glass.Button("Aggiorna", Glass.Button.Kind.GHOST);
        refresh.addActionListener(e -> network.refreshRooms());

        actions.add(quickMatch);
        actions.add(createRoom);
        actions.add(refresh);
        panel.add(actions, BorderLayout.NORTH);

        String[] columns = {"ID", "Nome stanza", "Host (Bianco)"};
        roomsTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        roomsTable = new JTable(roomsTableModel);
        roomsTable.setRowHeight(34);
        roomsTable.setFont(Glass.sans(13, Font.PLAIN));
        roomsTable.setForeground(Glass.TEXT);
        roomsTable.setBackground(new Color(0, 0, 0, 0));
        roomsTable.setSelectionBackground(Glass.alpha(Glass.ACCENT, 60));
        roomsTable.setSelectionForeground(Glass.TEXT);
        roomsTable.setShowGrid(false);
        roomsTable.setIntercellSpacing(new Dimension(0, 0));
        roomsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JTableHeader tableHeader = new JTableHeader(roomsTable.getColumnModel());
        tableHeader.setDefaultRenderer(createHeaderRenderer());
        tableHeader.setReorderingAllowed(false);
        tableHeader.setPreferredSize(new Dimension(10, 30));
        roomsTable.setTableHeader(tableHeader);
        roomsTable.setDefaultRenderer(Object.class, new GlassTableCellRenderer());

        // Doppio click = entrare nella stanza: con una sola colonna da scegliere
        // non ha senso costringere a selezionare e poi premere un bottone.
        roomsTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) joinSelectedRoom();
            }
        });

        // Segnaposto per la lista vuota: una tabella senza righe e' un rettangolo
        // bianco che non dice nulla. Sta in un CardLayout insieme alla tabella.
        // HTML: in un JLabel il "\n" non produce un a capo, servirebbe un <br>.
        lblNoRooms = Glass.label(
                "<html><div style='text-align:center'>Nessuna stanza aperta<br>"
                + "<span style='font-size:12px'>Crea tu la prima, o aspetta che qualcuno entri</span></div></html>",
                15, Font.PLAIN, Glass.TEXT_FAINT);
        lblNoRooms.setHorizontalAlignment(SwingConstants.CENTER);
        lblNoRooms.setVerticalAlignment(SwingConstants.CENTER);

        CardLayout listCards = new CardLayout();
        listHost = Glass.row(listCards, 0);
        listHost.add(lblNoRooms, "EMPTY");
        JScrollPane scroll = glassScroll(roomsTable);
        listHost.add(scroll, "LIST");
        listCards.show(listHost, "EMPTY");

        panel.add(listHost, BorderLayout.CENTER);

        // Campo codice e i due bottoni in una riga: BorderLayout accetta un solo
        // componente per zona, quindi i bottoni stanno in un contenitore a se' o il
        // secondo verrebbe scartato in silenzio.
        JPanel bottom = Glass.row(new BorderLayout(10, 0), 10);
        txtJoinCode = new Glass.Field("ID stanza");
        txtJoinCode.addActionListener(e -> joinByCode());
        bottom.add(txtJoinCode, BorderLayout.CENTER);

        JPanel bottomButtons = Glass.row(new FlowLayout(FlowLayout.RIGHT, 8, 0), 8);
        Glass.Button joinCode = new Glass.Button("Entra da codice", Glass.Button.Kind.GHOST);
        joinCode.addActionListener(e -> joinByCode());
        bottomButtons.add(joinCode);

        Glass.Button joinSelected = new Glass.Button("Entra nella stanza selezionata", Glass.Button.Kind.PRIMARY);
        joinSelected.addActionListener(e -> joinSelectedRoom());
        bottomButtons.add(joinSelected);

        bottom.add(bottomButtons, BorderLayout.EAST);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private void joinByCode() {
        String code = txtJoinCode.getText().trim();
        if (code.isEmpty()) {
            warn("Inserisci il codice ID della stanza.");
            return;
        }
        network.joinRoom(code);
    }

    private void joinSelectedRoom() {
        int row = roomsTable.getSelectedRow();
        if (row < 0) {
            warn("Seleziona prima una stanza dalla lista, o fai doppio click su una riga.");
            return;
        }
        network.joinRoom((String) roomsTableModel.getValueAt(row, 0));
    }

    // ---------- Amici ----------

    private JComponent createFriendsTab() {
        JPanel panel = Glass.row(new BorderLayout(12, 12), 12);

        JPanel addRow = Glass.row(new BorderLayout(10, 0), 10);
        txtAddFriend = new Glass.Field("Nome utente");
        txtAddFriend.addActionListener(e -> addFriend());
        addRow.add(Glass.muted("Nome utente"), BorderLayout.WEST);
        addRow.add(txtAddFriend, BorderLayout.CENTER);

        Glass.Button add = new Glass.Button("Aggiungi", Glass.Button.Kind.PRIMARY);
        add.addActionListener(e -> addFriend());
        addRow.add(add, BorderLayout.EAST);
        panel.add(addRow, BorderLayout.NORTH);

        friendsListModel = new DefaultListModel<>();
        friendsList = new JList<>(friendsListModel);
        friendsList.setFont(Glass.sans(14, Font.PLAIN));
        friendsList.setForeground(Glass.TEXT);
        friendsList.setBackground(new Color(0, 0, 0, 0));
        friendsList.setCellRenderer(new FriendRenderer());
        friendsList.setFixedCellHeight(40);
        panel.add(glassScroll(friendsList), BorderLayout.CENTER);

        JPanel bottom = Glass.row(new FlowLayout(FlowLayout.RIGHT, 0, 0), 0);
        Glass.Button refreshFriends = new Glass.Button("Aggiorna amici", Glass.Button.Kind.GHOST);
        refreshFriends.addActionListener(e -> network.refreshFriends());
        bottom.add(refreshFriends);
        panel.add(bottom, BorderLayout.SOUTH);

        return panel;
    }

    private void addFriend() {
        String name = txtAddFriend.getText().trim();
        if (name.isEmpty()) {
            warn("Scrivi il nome utente da aggiungere.");
            return;
        }
        network.addFriend(name);
        txtAddFriend.setText("");
    }

    // ---------- Profilo ----------

    private JComponent createProfileTab() {
        JPanel panel = Glass.row(new GridBagLayout(), 0);

        JPanel card = Glass.row(new GridLayout(0, 1, 0, 14), 0);
        card.setBorder(new EmptyBorder(30, 30, 30, 30));

        lblProfileName = centered("Nickname: -", 24, Font.BOLD, Glass.TEXT);
        lblProfileElo = centered("ELO 1200", 30, Font.BOLD, Glass.ACCENT_HI);
        lblProfileRecord = centered("Vittorie 0  -  Sconfitte 0  -  Patte 0", 14, Font.PLAIN, Glass.TEXT_DIM);
        lblProfileWinrate = centered("0% vittorie", 14, Font.BOLD, Glass.SUCCESS);

        card.add(lblProfileName);
        card.add(lblProfileElo);
        card.add(lblProfileRecord);
        card.add(lblProfileWinrate);
        // Linea di separazione fra statistiche e nota sul server.
        card.add(separator());
        card.add(centered("Server: game.cristianrenosto.party", 12, Font.PLAIN, Glass.TEXT_FAINT));

        panel.add(card);
        return panel;
    }

    /**
     * Tab "I miei dati": esercizio dei diritti dell'interessato previsti dal GDPR.
     * Espone l'accesso ai dati (art. 15 e 20), la disconnessione dall'account e la
     * cancellazione (art. 17), che e' irreversibile e chiede conferma esplicita.
     */
    private JComponent createPrivacyTab() {
        JPanel panel = Glass.row(new BorderLayout(12, 12), 12);

        privacyDataArea = new Glass.Area();
        privacyDataArea.setFont(Glass.mono(12, Font.PLAIN));
        privacyDataArea.setText("Premi \"Scarica i miei dati\" per vedere cosa il server\n"
                + "conserva sul tuo account.\n");
        panel.add(glassScroll(privacyDataArea), BorderLayout.CENTER);

        lblPrivacyStatus = Glass.muted(" ");

        JPanel actions = Glass.row(new FlowLayout(FlowLayout.LEFT, 10, 0), 10);

        btnExportData.addActionListener(e -> {
            privacyDataArea.setText("Richiesta inviata al server...\n");
            network.exportData();
        });

        btnLogout.addActionListener(e -> {
            if (Dialogs.confirm(this, "Esci dall'account",
                    "Vuoi uscire dall'account restando collegato come ospite?\n"
                            + "L'account non verra' cancellato.")) {
                network.logout();
            }
        });

        btnDeleteAccount.addActionListener(e -> confirmDeleteAccount());

        actions.add(btnExportData);
        actions.add(btnLogout);
        actions.add(btnDeleteAccount);
        actions.add(lblPrivacyStatus);

        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * La cancellazione e' irreversibile: la password viene chiesta in un campo
     * separato invece che nella conferma standard, altrimenti un click di troppo
     * basta a cancellare un account.
     */
    private void confirmDeleteAccount() {
        Glass.SecretField confirm = new Glass.SecretField("Password");
        confirm.setPreferredSize(new Dimension(340, 42));

        JPanel content = Glass.row(new BorderLayout(0, 16), 16);
        JLabel text = Glass.label(
                "<html><body style='width:340px'>"
                + "<p><b>Cancellare definitivamente il tuo account?</b></p>"
                + "<p>Verranno rimossi: profilo, statistiche, ELO e tutte le amicizie.<br>"
                + "Gli altri utenti non vedranno piu' il tuo nome nelle liste amici.</p>"
                + "<p style='color:#FF6B6B'><b>L'operazione e' irreversibile.</b></p>"
                + "<p>Inserisci la password per confermare.</p></body></html>",
                13, Font.PLAIN, Glass.TEXT);
        content.add(text, BorderLayout.NORTH);
        content.add(confirm, BorderLayout.CENTER);

        confirm.addActionListener(e -> { });

        Dialogs.custom(this, "Cancellazione account", content,
                "Cancella", "Annulla",
                () -> {
                    String password = confirm.plainText();
                    if (password.isEmpty()) {
                        setPrivacyStatus("Cancellazione annullata: password non inserita.");
                        return;
                    }
                    network.deleteAccount(password);
                },
                null);
    }

    // ---------- API pubblica (invariata) ----------

    /** Accumula una riga dei dati personali ricevuti dal server. */
    public void appendDataLine(String line) {
        privacyDataArea.append(line + "\n");
    }

    public void setDataAreaText(String text) {
        privacyDataArea.setText(text);
    }

    public void setPrivacyStatus(String message) {
        setPrivacyStatus(message, Glass.TEXT_DIM);
    }

    private void setPrivacyStatus(String message, Color color) {
        lblPrivacyStatus.setText(message);
        lblPrivacyStatus.setForeground(color);
    }

    /** Rende disponibili i pulsanti che hanno senso solo per un account registrato. */
    public void setAccountActionsEnabled(boolean enabled) {
        btnDeleteAccount.setEnabled(enabled);
        btnLogout.setEnabled(enabled);
        if (!enabled) lblSessionHeader.setText("Ospite: nessun account attivo");
    }

    public void updateUserInfo(String username, int elo, int wins, int losses, int draws) {
        // Nell'header l'etichetta utente era "Utente: nome": qui si mostra solo
        // l'iniziale nella pastiglia, il nome intero sta nel riquadro Profilo.
        lblUserHeader.setText(username == null || username.isEmpty() ? "?" : username.substring(0, 1).toUpperCase());
        lblEloHeader.setText("ELO " + elo);
        lblSessionHeader.setText("Account attivo");
        lblStatsHeader.setText("Vittorie " + wins + "   Sconfitte " + losses + "   Patte " + draws);

        lblProfileName.setText(username == null || username.isEmpty() ? "Ospite" : username);
        lblProfileElo.setText(String.valueOf(elo));
        lblProfileRecord.setText("Vittorie " + wins + "   -   Sconfitte " + losses + "   -   Patte " + draws);

        int total = wins + losses + draws;
        double winrate = (total > 0) ? (wins * 100.0 / total) : 0.0;
        lblProfileWinrate.setText(String.format("%.1f%% vittorie su %d partite", winrate, total));
    }

    public void updateRoomList(List<RoomInfo> rooms) {
        roomsTableModel.setRowCount(0);
        for (RoomInfo r : rooms) {
            roomsTableModel.addRow(new Object[]{r.getRoomId(), r.getRoomName(), r.getHostName()});
        }
        // Il segnaposto e la tabella sono le due card dello stesso CardLayout: va
        // mostrata quella giusta o resterebbe il messaggio "nessuna stanza" anche
        // con la lista piena.
        ((CardLayout) listHost.getLayout()).show(listHost, rooms.isEmpty() ? "EMPTY" : "LIST");
    }

    public void updateFriendsList(List<FriendInfo> friends) {
        friendsListModel.clear();
        for (FriendInfo f : friends) {
            friendsListModel.addElement(f);
        }
    }

    // ---------- Helper di aspetto ----------

    /** Filo orizzontale semitrasparente: separa senza aggiungere un bordo. */
    private static JComponent separator() {
        JPanel line = new JPanel();
        line.setOpaque(false);
        line.setBackground(Glass.alpha(Color.WHITE, 16));
        line.setPreferredSize(new Dimension(1, 1));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return line;
    }

    private static JLabel centered(String text, int size, int style, Color color) {
        JLabel l = Glass.label(text, size, style, color);
        l.setHorizontalAlignment(SwingConstants.CENTER);
        return l;
    }

    /** ScrollPane senza bordi e con sfondo trasparente, cosi' si vede il vetro sotto. */
    private static JScrollPane glassScroll(Component view) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(new EmptyBorder(0, 0, 0, 0));
        scroll.getVerticalScrollBar().setOpaque(false);
        scroll.getHorizontalScrollBar().setOpaque(false);
        return scroll;
    }

    private static TableCellRenderer createHeaderRenderer() {
        DefaultTableCellRenderer r = new DefaultTableCellRenderer();
        r.setFont(Glass.sans(11, Font.BOLD));
        r.setForeground(Glass.TEXT_FAINT);
        r.setBorder(new EmptyBorder(8, 10, 8, 10));
        r.setBackground(new Color(0, 0, 0, 0));
        return r;
    }

    /** Cella trasparente con padding generoso: la tabella legge come un elenco. */
    private static final class GlassTableCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            setBorder(new EmptyBorder(0, 10, 0, 10));
            setFont(Glass.sans(13, column == 0 ? Font.BOLD : Font.PLAIN));
            setForeground(selected ? Glass.TEXT : (column == 0 ? Glass.ACCENT_HI : Glass.TEXT_DIM));
            setBackground(selected ? Glass.alpha(Glass.ACCENT, 40) : new Color(0, 0, 0, 0));
            setOpaque(false);
            return this;
        }
    }

    /** Riga amico: pallino di stato colorato piu' nome. */
    private static final class FriendRenderer extends JComponent implements ListCellRenderer<FriendInfo> {
        private final JLabel dot = new JLabel("\u25CF");
        private final JLabel name = Glass.label("", 14, Font.PLAIN, Glass.TEXT);

        @Override
        public Component getListCellRendererComponent(JList<? extends FriendInfo> list, FriendInfo value,
                                                      int index, boolean selected, boolean focused) {
            if (value == null) return this;
            name.setText(value.getUsername());
            dot.setForeground(value.isOnline() ? Glass.SUCCESS : Glass.TEXT_FAINT);
            setOpaque(false);
            removeAll();
            setLayout(new BorderLayout(12, 0));
            setBorder(new EmptyBorder(0, 12, 0, 0));
            if (selected) {
                setOpaque(true);
                setBackground(Glass.alpha(Glass.ACCENT, 34));
                setBorder(new EmptyBorder(0, 6, 0, 6));
            }
            add(dot, BorderLayout.WEST);
            add(name, BorderLayout.CENTER);
            return this;
        }
    }

    // ---------- Dialoghi ----------

    private void info(String message) {
        Dialogs.info(this, "Matchmaking", message);
    }

    private void warn(String message) {
        Dialogs.warn(this, "Attenzione", message);
    }
}
