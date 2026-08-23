package com.ltm.geoduel.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ltm.geoduel.client.ui.components.CountdownBar;
import com.ltm.geoduel.client.ui.components.Icons;
import com.ltm.geoduel.client.ui.components.PillButton;
import com.ltm.geoduel.client.ui.components.RoundedPanel;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Màn hình trận đấu kiểu thám hiểm toàn màn hình: ảnh manh mối tràn nền,
 * HUD nổi (lượt, đồng hồ, tỉ số), mini-map góc phải tự phóng to khi rê chuột vào.
 * Khi có kết quả lượt: bản đồ chiếm toàn màn hình cùng dải kết quả.
 */
public class MatchPanel extends JLayeredPane {
    public interface Actions {
        void sendGuess(int matchId, int round, double lat, double lng);
        void leaveMatch(int matchId);
        void rematchChoice(int matchId, boolean again);
    }

    private enum Phase { IDLE, GUESS, RESULT }

    private static final int PAD = 14;
    private static final Color GLASS = new Color(252, 250, 244, 235); // pill kính sáng trên nền ảnh

    private final Actions actions;

    // trạng thái trận
    private int matchId = -1;
    private int totalRounds = 5;
    private int currentRound = 0;
    private String oppName = "";
    private Phase phase = Phase.IDLE;
    private boolean mapExpanded = false;

    // nền: ảnh phẳng hoặc ảnh 360° tuỳ manh mối hiện tại
    private final PhotoCanvas photo = new PhotoCanvas();
    private final PanoViewer pano = new PanoViewer();
    private final MapPanel map = new MapPanel();

    /** Một manh mối đã giải mã. */
    private record Clue(java.awt.image.BufferedImage img, boolean pano) {}
    private final List<Clue> clues = new ArrayList<>();
    private int clueIndex = 0;
    private boolean showingPano = false;

    // HUD nổi
    private final JLabel roundLabel = new JLabel("LƯỢT –/–");
    private final JLabel guessStatus = new JLabel("Trận đấu sắp bắt đầu...");
    private final RoundedPanel roundPill = pill(new GridLayout(2, 1, 0, 2));
    private final CountdownBar countdown = new CountdownBar();
    private final RoundedPanel timerPill = pill(new BorderLayout());
    private final JLabel myScoreName = new JLabel("", SwingConstants.RIGHT);
    private final JLabel myScoreVal = new JLabel("0", SwingConstants.RIGHT);
    private final JLabel oppScoreName = new JLabel();
    private final JLabel oppScoreVal = new JLabel("0");
    private final PillButton quitBtn = new PillButton("Thoát trận", PillButton.Kind.DANGER);
    private final RoundedPanel scorePill = pill(new FlowLayout(FlowLayout.CENTER, 10, 8));

    // điều hướng ảnh (góc trái dưới)
    private final RoundedPanel navBar = pill(new FlowLayout(FlowLayout.LEFT, 8, 6));
    private final JLabel photoCounter = new JLabel("–/–");
    private final JPanel dotsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 6));

    // mini-map + nút gửi (góc phải dưới)
    private final RoundedPanel miniMapBox = new RoundedPanel(new BorderLayout(0, 6),
            new Color(252, 250, 244, 245), Theme.BORDER);
    private final JLabel coordsLabel = new JLabel("Click bản đồ để đặt ghim", SwingConstants.CENTER);
    private final PillButton sendBtn = new PillButton("GỬI DỰ ĐOÁN", PillButton.Kind.PRIMARY);
    private JPanel sendRow; // hàng toạ độ + nút gửi dưới mini-map (ẩn ở màn kết quả)

    // dải kết quả lượt
    private final RoundedPanel resultBanner = pill(new GridLayout(3, 1, 0, 3));
    private final JLabel resultLine1 = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel resultLine2 = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel resultNext = new JLabel(" ", SwingConstants.CENTER);

    private final EndOverlay endOverlay = new EndOverlay();
    private final Timer collapseTimer = new Timer(300, e -> maybeCollapseMap());

    public MatchPanel(Actions actions) {
        this.actions = actions;
        setBackground(Theme.BG);
        setOpaque(true);

        buildHud();
        add(photo, Integer.valueOf(0));
        add(pano, Integer.valueOf(0));
        add(miniMapBox, Integer.valueOf(200));
        add(roundPill, Integer.valueOf(200));
        add(timerPill, Integer.valueOf(200));
        add(scorePill, Integer.valueOf(200));
        add(navBar, Integer.valueOf(200));
        add(resultBanner, Integer.valueOf(200));

        collapseTimer.setRepeats(false);
        endOverlay.setVisible(false);
        applyPhase(Phase.IDLE);
    }

    private static RoundedPanel pill(java.awt.LayoutManager lm) {
        RoundedPanel p = new RoundedPanel(lm, GLASS, Theme.BORDER_SOFT);
        p.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        return p;
    }

    public String loadMapAssets(Path assetsDir) {
        return map.loadMap(assetsDir);
    }

    public JComponent endOverlay() { return endOverlay; }

    // ================= dựng HUD =================

    private void buildHud() {
        // pill lượt + trạng thái (trái trên)
        roundLabel.setFont(Theme.bold(16));
        roundLabel.setForeground(Theme.TEXT);
        guessStatus.setFont(Theme.font(12));
        guessStatus.setForeground(Theme.TEXT_MUTED);
        roundPill.add(roundLabel);
        roundPill.add(guessStatus);

        // pill đồng hồ (giữa trên)
        countdown.setPreferredSize(new Dimension(96, 40));
        timerPill.add(countdown, BorderLayout.CENTER);

        // pill tỉ số + thoát (phải trên)
        myScoreName.setFont(Theme.bold(13));
        myScoreName.setForeground(Theme.AMBER);
        myScoreVal.setFont(Theme.monoBold(20));
        myScoreVal.setForeground(Theme.AMBER);
        JLabel dash = new JLabel("–");
        dash.setForeground(Theme.TEXT_FAINT);
        oppScoreVal.setFont(Theme.monoBold(20));
        oppScoreVal.setForeground(Theme.TEAL);
        oppScoreName.setFont(Theme.bold(13));
        oppScoreName.setForeground(Theme.TEAL);
        quitBtn.setFont(Theme.bold(12));
        quitBtn.addActionListener(e -> {
            if (matchId >= 0) actions.leaveMatch(matchId);
        });
        JButton muteBtn = new JButton(Icons.speaker(20, Theme.TEXT_MUTED,
                com.ltm.geoduel.client.audio.SoundManager.isMuted()));
        muteBtn.setContentAreaFilled(false);
        muteBtn.setBorderPainted(false);
        muteBtn.setFocusPainted(false);
        muteBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        muteBtn.setToolTipText("Bật/tắt âm thanh");
        muteBtn.addActionListener(e -> muteBtn.setIcon(Icons.speaker(20, Theme.TEXT_MUTED,
                com.ltm.geoduel.client.audio.SoundManager.toggleMuted())));
        scorePill.add(myScoreName);
        scorePill.add(myScoreVal);
        scorePill.add(dash);
        scorePill.add(oppScoreVal);
        scorePill.add(oppScoreName);
        scorePill.add(muteBtn);
        scorePill.add(quitBtn);

        // thanh điều hướng ảnh (trái dưới)
        JButton prev = navButton(false);
        JButton next = navButton(true);
        prev.addActionListener(e -> showClue(clueIndex - 1));
        next.addActionListener(e -> showClue(clueIndex + 1));
        photoCounter.setFont(Theme.mono(13));
        photoCounter.setForeground(Theme.TEXT);
        dotsRow.setOpaque(false);
        JLabel credit = new JLabel("Ảnh: KartaView/Mapillary · CC BY-SA 4.0 — lăn chuột để phóng to");
        credit.setFont(Theme.font(11));
        credit.setForeground(Theme.TEXT_FAINT);
        navBar.add(prev);
        navBar.add(photoCounter);
        navBar.add(next);
        navBar.add(dotsRow);
        navBar.add(credit);

        // hộp mini-map
        coordsLabel.setFont(Theme.mono(12));
        coordsLabel.setForeground(Theme.TEXT_MUTED);
        sendBtn.setEnabled(false);
        sendBtn.addActionListener(e -> sendGuess());
        sendRow = new JPanel(new GridLayout(2, 1, 0, 6));
        sendRow.setOpaque(false);
        sendRow.add(coordsLabel);
        sendRow.add(sendBtn);
        miniMapBox.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        miniMapBox.add(map, BorderLayout.CENTER);
        miniMapBox.add(sendRow, BorderLayout.SOUTH);
        MouseAdapter hover = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                collapseTimer.stop();
                if (!mapExpanded && phase == Phase.GUESS) {
                    mapExpanded = true;
                    revalidate();
                    repaint();
                }
            }
            @Override public void mouseExited(MouseEvent e) { collapseTimer.restart(); }
        };
        miniMapBox.addMouseListener(hover);
        map.addMouseListener(hover);
        sendBtn.addMouseListener(hover);

        map.setPinListener((lat, lng) -> {
            coordsLabel.setText(String.format("%.5f, %.5f", lat, lng));
            sendBtn.setEnabled(true);
            com.ltm.geoduel.client.audio.SoundManager.sfx("pin");
        });

        // dải kết quả
        resultLine1.setFont(Theme.bold(16));
        resultLine1.setForeground(Theme.TEXT);
        resultLine2.setFont(Theme.font(14));
        resultLine2.setForeground(Theme.TEXT_MUTED);
        resultNext.setFont(Theme.font(12));
        resultNext.setForeground(Theme.TEXT_FAINT);
        resultBanner.add(resultLine1);
        resultBanner.add(resultLine2);
        resultBanner.add(resultNext);
    }

    private JButton navButton(boolean right) {
        JButton b = new JButton(Icons.arrow(20, right, Theme.TEXT_MUTED));
        b.setRolloverIcon(Icons.arrow(20, right, Theme.TEXT));
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(28, 28));
        return b;
    }

    private void rebuildDots(int active, int total) {
        dotsRow.removeAll();
        for (int i = 0; i < total; i++) {
            final int idx = i;
            JButton dot = new JButton();
            dot.setPreferredSize(new Dimension(22, 8));
            dot.setContentAreaFilled(false);
            dot.setBorderPainted(false);
            dot.setFocusPainted(false);
            dot.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            dot.addActionListener(e -> showClue(idx));
            dot.setIcon(new javax.swing.Icon() {
                public int getIconWidth() { return 22; }
                public int getIconHeight() { return 8; }
                public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
                    Graphics2D g2 = Theme.prep(g.create());
                    g2.setColor(idx == active ? Theme.AMBER : Theme.BORDER);
                    g2.fillRoundRect(x + 2, y + 2, 18, 4, 4, 4);
                    g2.dispose();
                }
            });
            dotsRow.add(dot);
        }
        navBar.revalidate();
        navBar.repaint();
    }

    private void maybeCollapseMap() {
        if (!mapExpanded) return;
        java.awt.Point p = getMousePosition(true);
        if (p == null || !miniMapBox.getBounds().contains(p)) {
            mapExpanded = false;
            revalidate();
            repaint();
        }
    }

    // ================= bố cục =================

    @Override
    public void doLayout() {
        int w = getWidth(), h = getHeight();
        photo.setBounds(0, 0, w, h);
        pano.setBounds(0, 0, w, h);
        photo.setVisible(phase != Phase.RESULT && !showingPano);
        pano.setVisible(phase != Phase.RESULT && showingPano);

        Dimension rp = roundPill.getPreferredSize();
        roundPill.setBounds(PAD, PAD, rp.width, rp.height);

        Dimension tp = timerPill.getPreferredSize();
        timerPill.setBounds((w - tp.width) / 2, PAD, tp.width, tp.height);

        Dimension sp = scorePill.getPreferredSize();
        scorePill.setBounds(w - sp.width - PAD, PAD, sp.width, sp.height);
        int topH = Math.max(rp.height, Math.max(tp.height, sp.height));

        Dimension nb = navBar.getPreferredSize();
        navBar.setBounds(PAD, h - nb.height - PAD, Math.min(nb.width, w - 2 * PAD), nb.height);
        navBar.setVisible(phase == Phase.GUESS);

        if (phase == Phase.RESULT) {
            Dimension bn = resultBanner.getPreferredSize();
            int bw = Math.min(w - 2 * PAD, Math.max(bn.width + 40, 560));
            int bannerY = h - bn.height - PAD;
            resultBanner.setBounds((w - bw) / 2, bannerY, bw, bn.height);
            resultBanner.setVisible(true);
            int mapY = topH + 2 * PAD;
            miniMapBox.setBounds(PAD, mapY, w - 2 * PAD, bannerY - mapY - PAD);
        } else {
            resultBanner.setVisible(false);
            int boxW, boxH;
            if (mapExpanded) {
                boxW = Math.min((int) (w * 0.48), 680);
                boxH = Math.min((int) (h * 0.66), 620);
            } else {
                boxW = 300;
                boxH = 260;
            }
            miniMapBox.setBounds(w - boxW - PAD, h - boxH - PAD, boxW, boxH);
        }
        miniMapBox.setVisible(phase != Phase.IDLE);
    }

    private void applyPhase(Phase p) {
        this.phase = p;
        boolean guess = p == Phase.GUESS;
        if (sendRow != null) sendRow.setVisible(guess);
        revalidate();
        doLayout();
        repaint();
    }

    private void sendGuess() {
        if (!map.hasPin() || matchId < 0) return;
        sendBtn.setEnabled(false);
        actions.sendGuess(matchId, currentRound, map.pinLat(), map.pinLng());
    }

    // ================= sự kiện từ server =================

    public void startMatch(JsonObject data, String myDisplayName) {
        matchId = data.get("matchId").getAsInt();
        totalRounds = data.get("totalRounds").getAsInt();
        currentRound = 0;
        JsonObject opp = data.getAsJsonObject("opponent");
        oppName = opp.get("displayName").getAsString();

        myScoreName.setText(myDisplayName);
        oppScoreName.setText(oppName + " · Elo " + opp.get("elo").getAsInt());
        myScoreVal.setText("0");
        oppScoreVal.setText("0");
        roundLabel.setText("CHUẨN BỊ...");
        guessStatus.setText("Trận đấu sắp bắt đầu");
        clearClues();
        countdown.stop();
        endOverlay.setVisible(false);
        mapExpanded = false;
        applyPhase(Phase.IDLE);
    }

    private void clearClues() {
        clues.clear();
        clueIndex = 0;
        showingPano = false;
        photo.setImage(null);
        photoCounter.setText("–/–");
        rebuildDots(0, 0);
    }

    /** Hiển thị manh mối thứ i: ảnh 360° → viewer xoay, ảnh phẳng → khung zoom. */
    private void showClue(int i) {
        if (clues.isEmpty()) return;
        clueIndex = Math.floorMod(i, clues.size());
        Clue c = clues.get(clueIndex);
        showingPano = c.pano();
        if (c.pano()) {
            pano.setImage(c.img(), Double.NaN);
        } else {
            photo.setImage(c.img());
        }
        photoCounter.setText((clueIndex + 1) + "/" + clues.size());
        rebuildDots(clueIndex, clues.size());
        doLayout();
        repaint();
    }

    public void onRoundStart(JsonObject data) {
        currentRound = data.get("round").getAsInt();
        int duration = data.get("durationSec").getAsInt();
        roundLabel.setText("LƯỢT " + currentRound + "/" + totalRounds);
        guessStatus.setText("Đây là đâu? Soi ảnh rồi ghim lên bản đồ");

        // giải mã ảnh ngoài EDT; mỗi phần tử: {"b64": "...", "pano": bool}
        JsonArray imgs = data.getAsJsonArray("images");
        List<String[]> raw = new ArrayList<>();
        imgs.forEach(e -> {
            JsonObject o = e.getAsJsonObject();
            raw.add(new String[]{o.get("b64").getAsString(),
                    o.has("pano") && o.get("pano").getAsBoolean() ? "1" : "0"});
        });
        clearClues();
        final int roundOfImages = currentRound;
        new javax.swing.SwingWorker<List<Clue>, Void>() {
            @Override protected List<Clue> doInBackground() {
                List<Clue> decoded = new ArrayList<>();
                for (String[] r : raw) {
                    try {
                        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
                                new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(r[0])));
                        if (img != null) decoded.add(new Clue(img, "1".equals(r[1])));
                    } catch (Exception ignored) {}
                }
                return decoded;
            }
            @Override protected void done() {
                if (roundOfImages != currentRound) return; // lượt đã đổi trong lúc giải mã
                try {
                    clues.clear();
                    clues.addAll(get());
                } catch (Exception ignored) {}
                showClue(0);
            }
        }.execute();

        map.startGuessing();
        coordsLabel.setText("Click bản đồ để đặt ghim");
        sendBtn.setEnabled(false);
        mapExpanded = false;
        countdown.start(duration);
        applyPhase(Phase.GUESS);
        com.ltm.geoduel.client.audio.SoundManager.startAmbient();
    }

    public void onGuessAck() {
        sendBtn.setEnabled(false);
        map.lockPin();
        guessStatus.setText("Đã khóa dự đoán — chờ đối thủ...");
        com.ltm.geoduel.client.audio.SoundManager.sfx("lock");
        com.ltm.geoduel.client.audio.SoundManager.startTension();
    }

    public void onOpponentGuessed() {
        guessStatus.setText(guessStatus.getText().startsWith("Đã khóa")
                ? "Cả hai đã gửi — chờ kết quả"
                : "Đối thủ đã gửi dự đoán — nhanh lên!");
        com.ltm.geoduel.client.audio.SoundManager.sfx("alert");
        com.ltm.geoduel.client.audio.SoundManager.startTension();
    }

    /** Server ép lại thời hạn (một bên đã nộp → bên kia chỉ còn ít giây). */
    public void onTimerSync(int remainingSec) {
        countdown.start(remainingSec);
    }

    public void onRoundResult(JsonObject data) {
        countdown.stop();
        JsonObject mine = data.getAsJsonObject("mine");
        JsonObject opp = data.getAsJsonObject("opp");

        Double myLat = mine.get("guessed").getAsBoolean() ? mine.get("lat").getAsDouble() : null;
        Double myLng = myLat != null ? mine.get("lng").getAsDouble() : null;
        Double oLat = opp.get("guessed").getAsBoolean() ? opp.get("lat").getAsDouble() : null;
        Double oLng = oLat != null ? opp.get("lng").getAsDouble() : null;
        applyPhase(Phase.RESULT);
        map.showResult(myLat, myLng, oLat, oLng,
                data.get("targetLat").getAsDouble(), data.get("targetLng").getAsDouble());

        resultLine1.setText("Đáp án: " + data.get("locationName").getAsString());
        resultLine2.setText(sideText("Bạn", mine) + "    ·    " + sideText(oppName, opp));
        boolean last = data.get("isLastRound").getAsBoolean();
        int next = data.get("nextInSec").getAsInt();
        resultNext.setText(last ? "Đang tổng kết trận đấu..." : "Lượt tiếp theo sau " + next + " giây");

        myScoreVal.setText(String.format("%,d", data.get("myTotal").getAsInt()));
        oppScoreVal.setText(String.format("%,d", data.get("oppTotal").getAsInt()));
        guessStatus.setText("Kết quả lượt " + currentRound);
        com.ltm.geoduel.client.audio.SoundManager.stopMusic();
        com.ltm.geoduel.client.audio.SoundManager.sfx("ding");
    }

    private static String sideText(String who, JsonObject side) {
        if (!side.get("guessed").getAsBoolean()) {
            return who + ": không dự đoán (0 điểm)";
        }
        return String.format("%s: %.1f km (+%,d điểm)", who,
                side.get("distKm").getAsDouble(), side.get("score").getAsInt());
    }

    public void onMatchEnd(JsonObject data) {
        countdown.stop();
        com.ltm.geoduel.client.audio.SoundManager.stopMusic();
        switch (data.get("result").getAsString()) {
            case "WIN" -> com.ltm.geoduel.client.audio.SoundManager.sfx("win");
            case "LOSE" -> com.ltm.geoduel.client.audio.SoundManager.sfx("lose");
            default -> com.ltm.geoduel.client.audio.SoundManager.sfx("ding");
        }
        endOverlay.show(data, oppName, matchId);
    }

    public void onRematchWait() {
        endOverlay.showWaiting();
    }

    // ================= lớp phủ kết thúc =================

    /** Lớp phủ mờ + thẻ kết quả trận, gắn vào layered pane của MainFrame. */
    private class EndOverlay extends JPanel {
        private final JLabel big = new JLabel(" ", SwingConstants.CENTER);
        private final JLabel sub = new JLabel(" ", SwingConstants.CENTER);
        private final JLabel elo = new JLabel(" ", SwingConstants.CENTER);
        private final PillButton again = new PillButton("Chơi lại", PillButton.Kind.PRIMARY);
        private final PillButton exit = new PillButton("Thoát", PillButton.Kind.GHOST);
        private final JLabel waiting = new JLabel(" ", SwingConstants.CENTER);

        EndOverlay() {
            setOpaque(false);
            setLayout(new GridBagLayout());
            // nuốt sự kiện chuột để không click xuyên xuống HUD/bản đồ phía dưới
            addMouseListener(new MouseAdapter() {});
            addMouseMotionListener(new MouseAdapter() {});
            addMouseWheelListener(e -> {});
            RoundedPanel card = new RoundedPanel(new GridBagLayout(), Theme.ELEVATED, Theme.BORDER);
            card.setBorder(BorderFactory.createEmptyBorder(30, 44, 26, 44));

            big.setFont(Theme.bold(34));
            sub.setFont(Theme.font(15));
            sub.setForeground(Theme.TEXT_MUTED);
            elo.setFont(Theme.monoBold(16));
            waiting.setFont(Theme.font(13));
            waiting.setForeground(Theme.TEXT_MUTED);

            again.addActionListener(e -> actions.rematchChoice(matchId, true));
            exit.addActionListener(e -> actions.rematchChoice(matchId, false));

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 0));
            buttons.setOpaque(false);
            buttons.add(again);
            buttons.add(exit);

            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.insets = new java.awt.Insets(4, 0, 4, 0);
            c.gridy = 0; card.add(big, c);
            c.gridy = 1; card.add(sub, c);
            c.gridy = 2; card.add(elo, c);
            c.gridy = 3; c.insets = new java.awt.Insets(16, 0, 2, 0); card.add(buttons, c);
            c.gridy = 4; c.insets = new java.awt.Insets(4, 0, 0, 0); card.add(waiting, c);
            add(card, new GridBagConstraints());
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setColor(new Color(8, 11, 16, 205));
            g2.fillRect(0, 0, getWidth(), getHeight());
        }

        void show(JsonObject data, String oppName, int matchId) {
            String result = data.get("result").getAsString();
            String reason = data.get("reason").getAsString();
            switch (result) {
                case "WIN" -> { big.setText("CHIẾN THẮNG"); big.setForeground(Theme.GREEN); }
                case "LOSE" -> { big.setText("THẤT BẠI"); big.setForeground(Theme.RED); }
                default -> { big.setText("HÒA"); big.setForeground(Theme.AMBER); }
            }
            String reasonText = switch (reason) {
                case "OPPONENT_LEFT" -> " (đối thủ thoát trận)";
                case "OPPONENT_DISCONNECTED" -> " (đối thủ mất kết nối)";
                case "YOU_LEFT" -> " (bạn đã thoát trận)";
                default -> "";
            };
            sub.setText(String.format("Tỉ số: %,d – %,d với %s%s",
                    data.get("myTotal").getAsInt(), data.get("oppTotal").getAsInt(), oppName, reasonText));
            int change = data.get("myEloChange").getAsInt();
            elo.setText(String.format("Elo: %s%d  →  %d", change >= 0 ? "+" : "", change,
                    data.get("myNewElo").getAsInt()));
            elo.setForeground(change >= 0 ? Theme.GREEN : Theme.RED);

            boolean canRematch = "NORMAL".equals(reason);
            again.setVisible(canRematch);
            exit.setVisible(canRematch);
            again.setEnabled(true);
            exit.setEnabled(true);
            waiting.setText(canRematch ? " " : "Đang trở về sảnh chờ...");
            setVisible(true);
            revalidate();
            repaint();
        }

        void showWaiting() {
            again.setEnabled(false);
            exit.setEnabled(false);
            waiting.setText("Đã chọn Chơi lại — đang chờ đối thủ...");
        }
    }
}
