package com.ltm.geoduel.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ltm.geoduel.client.ui.components.Icons;
import com.ltm.geoduel.client.ui.components.PillButton;
import com.ltm.geoduel.client.ui.components.RoundedPanel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Sảnh chờ: hồ sơ của mình, danh sách người chơi online (mời đấu),
 * bảng xếp hạng và lịch sử thi đấu.
 */
public class LobbyPanel extends JPanel {
    public interface Actions {
        void invite(String username);
        void refreshAll();
        void logout();
    }

    private final Actions actions;

    // hồ sơ của mình
    private final JLabel meName = new JLabel();
    private final JLabel meElo = new JLabel();
    private final JLabel meRecord = new JLabel();
    private String myUsername = "";

    // danh sách online
    private final DefaultListModel<JsonObject> onlineModel = new DefaultListModel<>();
    private final JList<JsonObject> onlineList = new JList<>(onlineModel);
    private final JLabel onlineCount = new JLabel("0 người");
    private final PillButton inviteBtn = new PillButton("Mời thi đấu", PillButton.Kind.PRIMARY);

    // bảng phải
    private final CardLayout tabCards = new CardLayout();
    private final JPanel tabPanel = new JPanel(tabCards);
    private final DefaultTableModel lbModel = readOnlyModel("#", "Người chơi", "Elo", "Thắng", "Hòa", "Thua", "Tổng điểm");
    private final DefaultTableModel histModel = readOnlyModel("Thời gian", "Đối thủ", "Tỉ số", "Kết quả", "Elo +/-");
    private final PillButton tabLeaderboard = new PillButton("Bảng xếp hạng", PillButton.Kind.TAB);
    private final PillButton tabHistory = new PillButton("Lịch sử của tôi", PillButton.Kind.TAB);
    private JTable lbTable;

    // toast
    private final JLabel toast = new JLabel(" ", SwingConstants.CENTER);
    private final Timer toastTimer = new Timer(5000, e -> toast.setText(" "));

    public LobbyPanel(Actions actions) {
        this.actions = actions;
        setBackground(Theme.BG);
        setOpaque(true);
        setLayout(new BorderLayout(16, 12));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));

        add(header(), BorderLayout.NORTH);

        JPanel center = new JPanel(new GridLayout(1, 2, 16, 0));
        center.setOpaque(false);
        center.add(onlineColumn());
        center.add(rightColumn());
        add(center, BorderLayout.CENTER);

        toast.setFont(Theme.font(13));
        toast.setForeground(Theme.AMBER_DARK);
        add(toast, BorderLayout.SOUTH);
        toastTimer.setRepeats(false);
    }

    /** Nền sảnh: bản đồ thế giới rất mờ — gợi không khí thám hiểm mà không gây rối. */
    @Override
    protected void paintComponent(java.awt.Graphics g) {
        super.paintComponent(g);
        java.awt.Graphics2D g2 = Theme.prep((java.awt.Graphics2D) g.create());
        WorldBackdrop.paint(this, g2, getWidth(), getHeight(), 225);
        g2.dispose();
    }

    // ================= header =================

    private JPanel header() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        left.add(new JLabel(Icons.compass(30, Theme.AMBER, Theme.AMBER)));
        JLabel brand = new JLabel("GeoDuel");
        brand.setFont(Theme.bold(20));
        brand.setForeground(Theme.TEXT);
        left.add(brand);

        RoundedPanel me = new RoundedPanel(new FlowLayout(FlowLayout.LEFT, 12, 8), Theme.SURFACE, Theme.BORDER_SOFT);
        meName.setFont(Theme.bold(15));
        meName.setForeground(Theme.TEXT);
        meElo.setFont(Theme.monoBold(15));
        meElo.setForeground(Theme.AMBER);
        meRecord.setFont(Theme.font(12));
        meRecord.setForeground(Theme.TEXT_MUTED);
        me.add(meName);
        me.add(meElo);
        me.add(meRecord);

        PillButton logout = new PillButton("Đăng xuất", PillButton.Kind.GHOST);
        logout.addActionListener(e -> actions.logout());
        javax.swing.JButton muteBtn = new javax.swing.JButton(Icons.speaker(20, Theme.TEXT_MUTED,
                com.ltm.geoduel.client.audio.SoundManager.isMuted()));
        muteBtn.setContentAreaFilled(false);
        muteBtn.setBorderPainted(false);
        muteBtn.setFocusPainted(false);
        muteBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        muteBtn.setToolTipText("Bật/tắt âm thanh");
        muteBtn.addActionListener(e -> muteBtn.setIcon(Icons.speaker(20, Theme.TEXT_MUTED,
                com.ltm.geoduel.client.audio.SoundManager.toggleMuted())));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        right.add(me);
        right.add(muteBtn);
        right.add(logout);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    public void setProfile(String username, String displayName, int elo, int wins, int losses, int draws) {
        myUsername = username;
        meName.setText(displayName);
        meElo.setText("Elo " + elo);
        meRecord.setText(wins + " thắng · " + draws + " hòa · " + losses + " thua");
    }

    // ================= cột online =================

    private JPanel onlineColumn() {
        RoundedPanel col = new RoundedPanel(new BorderLayout(0, 10), Theme.SURFACE, null, 18, true);
        col.setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        JLabel title = new JLabel("NGƯỜI CHƠI TRỰC TUYẾN");
        title.setFont(Theme.bold(12));
        title.setForeground(Theme.TEXT_MUTED);
        onlineCount.setFont(Theme.font(12));
        onlineCount.setForeground(Theme.TEXT_FAINT);
        head.add(title, BorderLayout.WEST);
        head.add(onlineCount, BorderLayout.EAST);
        col.add(head, BorderLayout.NORTH);

        onlineList.setBackground(Theme.SURFACE);
        onlineList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        onlineList.setCellRenderer(new OnlineRenderer());
        onlineList.setFixedCellHeight(58);
        onlineList.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        onlineList.addListSelectionListener(e -> updateInviteButton());
        onlineList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) tryInviteSelected();
            }
        });
        JScrollPane scroll = new JScrollPane(onlineList);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Theme.SURFACE);
        col.add(scroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        JLabel hint = new JLabel("Chọn một người đang rảnh rồi bấm mời (hoặc nhấp đúp)");
        hint.setFont(Theme.font(11));
        hint.setForeground(Theme.TEXT_FAINT);
        inviteBtn.setEnabled(false);
        inviteBtn.addActionListener(e -> tryInviteSelected());
        south.add(hint, BorderLayout.WEST);
        south.add(inviteBtn, BorderLayout.EAST);
        col.add(south, BorderLayout.SOUTH);
        return col;
    }

    private void tryInviteSelected() {
        JsonObject sel = onlineList.getSelectedValue();
        if (sel == null) return;
        String username = sel.get("username").getAsString();
        boolean free = "FREE".equals(sel.get("status").getAsString());
        if (username.equals(myUsername) || !free) return;
        actions.invite(username);
        showToast("Đã gửi lời mời tới " + sel.get("displayName").getAsString() + ", đang chờ trả lời...");
    }

    private void updateInviteButton() {
        JsonObject sel = onlineList.getSelectedValue();
        inviteBtn.setEnabled(sel != null
                && !sel.get("username").getAsString().equals(myUsername)
                && "FREE".equals(sel.get("status").getAsString()));
    }

    public void updateOnline(JsonArray players) {
        JsonObject selected = onlineList.getSelectedValue();
        String keep = selected != null ? selected.get("username").getAsString() : null;
        onlineModel.clear();
        int idx = -1, i = 0;
        for (JsonElement e : players) {
            JsonObject p = e.getAsJsonObject();
            onlineModel.addElement(p);
            if (p.get("username").getAsString().equals(keep)) idx = i;
            i++;
        }
        onlineCount.setText(players.size() + " người");
        if (idx >= 0) onlineList.setSelectedIndex(idx);
        updateInviteButton();
    }

    private class OnlineRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            JsonObject p = (JsonObject) value;
            boolean free = "FREE".equals(p.get("status").getAsString());
            boolean isMe = p.get("username").getAsString().equals(myUsername);

            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            row.setBackground(selected ? Theme.ELEVATED : Theme.SURFACE);

            JLabel dot = new JLabel(Icons.dot(14, free ? Theme.GREEN : Theme.RED));

            JPanel names = new JPanel(new GridLayout(2, 1));
            names.setOpaque(false);
            JLabel name = new JLabel(p.get("displayName").getAsString() + (isMe ? "  (bạn)" : ""));
            name.setFont(Theme.bold(14));
            name.setForeground(isMe ? Theme.TEXT_MUTED : Theme.TEXT);
            JLabel status = new JLabel(free ? "đang rảnh" : "đang thi đấu");
            status.setFont(Theme.font(11));
            status.setForeground(free ? Theme.GREEN : Theme.TEXT_FAINT);
            names.add(name);
            names.add(status);

            JLabel elo = new JLabel("Elo " + p.get("elo").getAsInt());
            elo.setFont(Theme.monoBold(13));
            elo.setForeground(Theme.AMBER);

            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
            left.setOpaque(false);
            left.add(dot);
            left.add(names);
            row.add(left, BorderLayout.WEST);
            row.add(elo, BorderLayout.EAST);
            return row;
        }
    }

    // ================= cột phải =================

    private JPanel rightColumn() {
        RoundedPanel col = new RoundedPanel(new BorderLayout(0, 10), Theme.SURFACE, null, 18, true);
        col.setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));

        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        tabs.setOpaque(false);
        tabLeaderboard.addActionListener(e -> selectTab(true));
        tabHistory.addActionListener(e -> selectTab(false));
        PillButton refresh = new PillButton("Làm mới", PillButton.Kind.GHOST);
        refresh.addActionListener(e -> actions.refreshAll());
        tabs.add(tabLeaderboard);
        tabs.add(tabHistory);
        tabs.add(Box.createHorizontalStrut(10));
        tabs.add(refresh);
        col.add(tabs, BorderLayout.NORTH);

        lbTable = styledTable(lbModel);
        JTable histTable = styledTable(histModel);
        tabPanel.setOpaque(false);
        tabPanel.add(wrapTable(lbTable), "lb");
        tabPanel.add(wrapTable(histTable), "hist");
        col.add(tabPanel, BorderLayout.CENTER);
        selectTab(true);
        return col;
    }

    private void selectTab(boolean leaderboard) {
        tabCards.show(tabPanel, leaderboard ? "lb" : "hist");
        tabLeaderboard.setSelected(leaderboard);
        tabHistory.setSelected(!leaderboard);
        repaint();
    }

    private static DefaultTableModel readOnlyModel(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    private JTable styledTable(DefaultTableModel model) {
        JTable t = new JTable(model);
        t.setBackground(Theme.SURFACE);
        t.setForeground(Theme.TEXT);
        t.setFont(Theme.font(13));
        t.setRowHeight(30);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setSelectionBackground(Theme.ELEVATED);
        t.setSelectionForeground(Theme.TEXT);
        t.setFillsViewportHeight(true);
        JTableHeader h = t.getTableHeader();
        h.setBackground(Theme.SURFACE);
        h.setForeground(Theme.TEXT_MUTED);
        h.setFont(Theme.bold(11));
        h.setReorderingAllowed(false);
        h.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean sel,
                                                           boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(table, value, sel, false, row, col);
                c.setBackground(sel ? Theme.ELEVATED : row % 2 == 0 ? Theme.SURFACE : Theme.mix(Theme.SURFACE, Theme.ELEVATED, 0.45f));
                c.setForeground(Theme.TEXT);
                setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
                // tô nổi bật dòng của chính mình trong bảng xếp hạng
                if (table == lbTable) {
                    Object uname = table.getClientProperty("row-user-" + row);
                    if (myUsername.equals(uname)) c.setForeground(Theme.AMBER);
                }
                String colName = table.getColumnName(col);
                if ("Elo".equals(colName) || "Tổng điểm".equals(colName) || "Tỉ số".equals(colName) || "Elo +/-".equals(colName)) {
                    setFont(Theme.mono(13));
                    setHorizontalAlignment(RIGHT);
                } else if ("#".equals(colName) || "Thắng".equals(colName) || "Hòa".equals(colName) || "Thua".equals(colName)) {
                    setFont(Theme.font(13));
                    setHorizontalAlignment(CENTER);
                } else {
                    setFont(Theme.font(13));
                    setHorizontalAlignment(LEFT);
                }
                if ("Kết quả".equals(colName) && value != null) {
                    switch (value.toString()) {
                        case "THẮNG" -> c.setForeground(Theme.GREEN);
                        case "THUA" -> c.setForeground(Theme.RED);
                        default -> c.setForeground(Theme.TEXT_MUTED);
                    }
                }
                return c;
            }
        });
        return t;
    }

    private JScrollPane wrapTable(JTable t) {
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(Theme.SURFACE);
        return sp;
    }

    public void updateLeaderboard(JsonArray rows) {
        lbModel.setRowCount(0);
        int r = 0;
        for (JsonElement e : rows) {
            JsonObject o = e.getAsJsonObject();
            lbModel.addRow(new Object[]{
                    o.get("rank").getAsInt(),
                    o.get("displayName").getAsString(),
                    o.get("elo").getAsInt(),
                    o.get("wins").getAsInt(),
                    o.get("draws").getAsInt(),
                    o.get("losses").getAsInt(),
                    String.format("%,d", o.get("totalScore").getAsLong())
            });
            lbTable.putClientProperty("row-user-" + r, o.get("username").getAsString());
            r++;
        }
    }

    public void updateHistory(JsonArray rows) {
        histModel.setRowCount(0);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM HH:mm");
        for (JsonElement e : rows) {
            JsonObject o = e.getAsJsonObject();
            String time = o.get("time").getAsString();
            try {
                time = LocalDateTime.parse(time).format(fmt);
            } catch (Exception ignored) {}
            int delta = o.get("eloChange").getAsInt();
            histModel.addRow(new Object[]{
                    time,
                    o.get("opponent").getAsString(),
                    String.format("%,d – %,d", o.get("myScore").getAsInt(), o.get("oppScore").getAsInt()),
                    o.get("result").getAsString(),
                    (delta >= 0 ? "+" : "") + delta
            });
        }
    }

    public void showToast(String message) {
        toast.setText(message);
        toastTimer.restart();
    }
}
