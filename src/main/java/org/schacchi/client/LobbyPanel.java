package org.schacchi.client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * Schermata principale della Lobby dopo il login:
 * - Trovare ed esplorare stanze aperte
 * - Creare una nuova stanza
 * - Unirsi a una stanza tramite codice ID
 * - Matchmaking rapido 1v1
 * - Gestione amici (aggiungi, visualizza stato online/offline)
 * - Statistiche profilo (ELO, Vittorie, Sconfitte, Patte)
 */
public class LobbyPanel extends JPanel {
    private final ClientNetwork network;

    private JLabel lblUserHeader;
    private JLabel lblEloHeader;
    private JLabel lblStatsHeader;

    // Tab Stanze
    private DefaultTableModel roomsTableModel;
    private JTable roomsTable;
    private JTextField txtJoinCode;

    // Tab Amici
    private DefaultListModel<String> friendsListModel;
    private JList<String> friendsList;
    private JTextField txtAddFriend;

    // Tab Profilo
    private JLabel lblProfileName;
    private JLabel lblProfileElo;
    private JLabel lblProfileRecord;
    private JLabel lblProfileWinrate;

    public LobbyPanel(ClientNetwork network) {
        this.network = network;
        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(12, 12, 12, 12));
        setBackground(new Color(28, 30, 34));

        // Header superiore con profilo rapido
        add(createHeader(), BorderLayout.NORTH);

        // Centro con Tabs
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font("SansSerif", Font.BOLD, 13));

        tabbedPane.addTab("Stanze & Matchmaking", createRoomsTab());
        tabbedPane.addTab("Amici", createFriendsTab());
        tabbedPane.addTab("Il Mio Profilo", createProfileTab());

        add(tabbedPane, BorderLayout.CENTER);
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(10, 10));
        header.setBackground(new Color(36, 38, 44));
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(55, 58, 68), 1),
                new EmptyBorder(12, 16, 12, 16)
        ));

        JPanel userBox = new JPanel(new GridLayout(2, 1, 2, 2));
        userBox.setOpaque(false);

        lblUserHeader = new JLabel("Utente: Connesso");
        lblUserHeader.setForeground(Color.WHITE);
        lblUserHeader.setFont(new Font("SansSerif", Font.BOLD, 16));

        lblEloHeader = new JLabel("ELO: 1200");
        lblEloHeader.setForeground(new Color(255, 215, 0));
        lblEloHeader.setFont(new Font("SansSerif", Font.BOLD, 13));

        userBox.add(lblUserHeader);
        userBox.add(lblEloHeader);

        lblStatsHeader = new JLabel("Server: game.cristianrenosto.party  |  Vittorie: 0  Sconfitte: 0  Patte: 0");
        lblStatsHeader.setForeground(new Color(180, 185, 200));
        lblStatsHeader.setFont(new Font("SansSerif", Font.PLAIN, 12));

        header.add(userBox, BorderLayout.WEST);
        header.add(lblStatsHeader, BorderLayout.EAST);
        return header;
    }

    private JPanel createRoomsTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Barra azioni veloci in alto (Matchmaking, Crea, Entra per codice)
        JPanel topActions = new JPanel(new GridLayout(1, 3, 10, 10));
        topActions.setOpaque(false);

        // 1. Partita Rapida
        JButton btnQuickMatch = new JButton("Partita Rapida (Matchmaking)");
        btnQuickMatch.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnQuickMatch.setBackground(new Color(40, 140, 70));
        btnQuickMatch.setForeground(Color.WHITE);
        btnQuickMatch.addActionListener(e -> {
            network.quickMatch();
            JOptionPane.showMessageDialog(this, "Sei in coda per il Matchmaking.\nAppena un avversario si unisce la partita iniziera' automaticamente!", "Matchmaking", JOptionPane.INFORMATION_MESSAGE);
        });

        // 2. Crea Stanza
        JButton btnCreateRoom = new JButton("Crea Nuova Stanza");
        btnCreateRoom.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnCreateRoom.addActionListener(e -> {
            String roomName = JOptionPane.showInputDialog(this, "Nome della stanza da creare:", "Crea Stanza", JOptionPane.PLAIN_MESSAGE);
            if (roomName != null && !roomName.trim().isEmpty()) {
                network.createRoom(roomName.trim());
            }
        });

        // 3. Entra con Codice
        JPanel joinByCodePanel = new JPanel(new BorderLayout(5, 5));
        joinByCodePanel.setOpaque(false);
        txtJoinCode = new JTextField();
        txtJoinCode.setToolTipText("Inserisci ID stanza (es: room_101)");
        JButton btnJoinByCode = new JButton("Entra da Codice");
        btnJoinByCode.addActionListener(e -> {
            String code = txtJoinCode.getText().trim();
            if (!code.isEmpty()) {
                network.joinRoom(code);
            } else {
                JOptionPane.showMessageDialog(this, "Inserisci il codice ID della stanza!", "Attenzione", JOptionPane.WARNING_MESSAGE);
            }
        });
        joinByCodePanel.add(txtJoinCode, BorderLayout.CENTER);
        joinByCodePanel.add(btnJoinByCode, BorderLayout.EAST);

        topActions.add(btnQuickMatch);
        topActions.add(btnCreateRoom);
        topActions.add(joinByCodePanel);

        panel.add(topActions, BorderLayout.NORTH);

        // Tabella Stanze aperte
        String[] columnNames = {"ID Stanza", "Nome Stanza", "Host (Bianco)", "Azione"};
        roomsTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        roomsTable = new JTable(roomsTableModel);
        roomsTable.setRowHeight(28);
        roomsTable.setFont(new Font("SansSerif", Font.PLAIN, 13));
        roomsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane tableScroll = new JScrollPane(roomsTable);
        panel.add(tableScroll, BorderLayout.CENTER);

        // Barra inferiore con "Aggiorna Elenco" ed "Entra nella stanza selezionata"
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 6));
        bottomBar.setOpaque(false);

        JButton btnRefreshRooms = new JButton("Aggiorna Stanze");
        btnRefreshRooms.addActionListener(e -> network.refreshRooms());

        JButton btnJoinSelected = new JButton("Entra nella Stanza Selezionata");
        btnJoinSelected.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnJoinSelected.addActionListener(e -> {
            int selectedRow = roomsTable.getSelectedRow();
            if (selectedRow >= 0) {
                String roomId = (String) roomsTableModel.getValueAt(selectedRow, 0);
                network.joinRoom(roomId);
            } else {
                JOptionPane.showMessageDialog(this, "Seleziona prima una stanza dalla lista!", "Avviso", JOptionPane.WARNING_MESSAGE);
            }
        });

        bottomBar.add(btnRefreshRooms);
        bottomBar.add(btnJoinSelected);
        panel.add(bottomBar, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createFriendsTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Barra aggiunta amico in alto
        JPanel addFriendPanel = new JPanel(new BorderLayout(8, 8));
        addFriendPanel.setOpaque(false);
        txtAddFriend = new JTextField();
        JButton btnAddFriend = new JButton("Aggiungi Amico");
        btnAddFriend.addActionListener(e -> {
            String fName = txtAddFriend.getText().trim();
            if (!fName.isEmpty()) {
                network.addFriend(fName);
                txtAddFriend.setText("");
            }
        });

        addFriendPanel.add(new JLabel("Nome utente amico: "), BorderLayout.WEST);
        addFriendPanel.add(txtAddFriend, BorderLayout.CENTER);
        addFriendPanel.add(btnAddFriend, BorderLayout.EAST);
        panel.add(addFriendPanel, BorderLayout.NORTH);

        // Lista amici
        friendsListModel = new DefaultListModel<>();
        friendsList = new JList<>(friendsListModel);
        friendsList.setFont(new Font("SansSerif", Font.PLAIN, 14));
        friendsList.setFixedCellHeight(32);
        JScrollPane friendsScroll = new JScrollPane(friendsList);
        panel.add(friendsScroll, BorderLayout.CENTER);

        // Barra pulsanti amici in basso
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 6));
        bottomBar.setOpaque(false);

        JButton btnRefreshFriends = new JButton("Aggiorna Amici");
        btnRefreshFriends.addActionListener(e -> network.refreshFriends());

        bottomBar.add(btnRefreshFriends);
        panel.add(bottomBar, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createProfileTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);

        JPanel card = new JPanel(new GridLayout(5, 1, 10, 10));
        card.setBackground(new Color(36, 38, 44));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(60, 64, 76), 1),
                new EmptyBorder(24, 32, 24, 32)
        ));

        lblProfileName = new JLabel("Nickname: -", SwingConstants.CENTER);
        lblProfileName.setFont(new Font("SansSerif", Font.BOLD, 18));
        lblProfileName.setForeground(Color.WHITE);

        lblProfileElo = new JLabel("ELO Rating: 1200", SwingConstants.CENTER);
        lblProfileElo.setFont(new Font("SansSerif", Font.BOLD, 16));
        lblProfileElo.setForeground(new Color(255, 215, 0));

        lblProfileRecord = new JLabel("Vittorie: 0 | Sconfitte: 0 | Patte: 0", SwingConstants.CENTER);
        lblProfileRecord.setFont(new Font("SansSerif", Font.PLAIN, 14));
        lblProfileRecord.setForeground(Color.LIGHT_GRAY);

        lblProfileWinrate = new JLabel("Percentuale di Vittoria: 0%", SwingConstants.CENTER);
        lblProfileWinrate.setFont(new Font("SansSerif", Font.PLAIN, 14));
        lblProfileWinrate.setForeground(new Color(100, 240, 120));

        JLabel lblServerNote = new JLabel("Server: game.cristianrenosto.party", SwingConstants.CENTER);
        lblServerNote.setFont(new Font("SansSerif", Font.ITALIC, 12));
        lblServerNote.setForeground(Color.GRAY);

        card.add(lblProfileName);
        card.add(lblProfileElo);
        card.add(lblProfileRecord);
        card.add(lblProfileWinrate);
        card.add(lblServerNote);

        panel.add(card);
        return panel;
    }

    public void updateUserInfo(String username, int elo, int wins, int losses, int draws) {
        lblUserHeader.setText("Utente: " + username);
        lblEloHeader.setText("ELO: " + elo);
        lblStatsHeader.setText("Server: game.cristianrenosto.party  |  Vittorie: " + wins + "  Sconfitte: " + losses + "  Patte: " + draws);

        lblProfileName.setText("Nickname: " + username);
        lblProfileElo.setText("ELO Rating: " + elo);
        lblProfileRecord.setText("Vittorie: " + wins + " | Sconfitte: " + losses + " | Patte: " + draws);

        int total = wins + losses + draws;
        double winrate = (total > 0) ? (wins * 100.0 / total) : 0.0;
        lblProfileWinrate.setText(String.format("Percentuale di Vittoria: %.1f%% (Partite totali: %d)", winrate, total));
    }

    public void updateRoomList(List<RoomInfo> rooms) {
        roomsTableModel.setRowCount(0);
        for (RoomInfo r : rooms) {
            roomsTableModel.addRow(new Object[]{r.getRoomId(), r.getRoomName(), r.getHostName(), "Entra"});
        }
    }

    public void updateFriendsList(List<FriendInfo> friends) {
        friendsListModel.clear();
        for (FriendInfo f : friends) {
            friendsListModel.addElement(f.getUsername() + "   [" + (f.isOnline() ? "ONLINE" : "OFFLINE") + "]");
        }
    }
}
