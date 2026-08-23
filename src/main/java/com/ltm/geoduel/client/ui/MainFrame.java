package com.ltm.geoduel.client.ui;

import com.google.gson.JsonObject;
import com.ltm.geoduel.client.net.NetworkClient;
import com.ltm.geoduel.client.ui.components.Icons;
import com.ltm.geoduel.client.ui.components.PillButton;
import com.ltm.geoduel.client.ui.components.RoundedPanel;
import com.ltm.geoduel.common.Log;
import com.ltm.geoduel.common.Message;
import com.ltm.geoduel.common.Msg;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Cửa sổ chính: chuyển giữa ba màn hình (đăng nhập / sảnh / trận đấu),
 * là nơi DUY NHẤT nhận và phân phối thông điệp từ server (đã ở trên EDT).
 */
public class MainFrame extends JFrame implements NetworkClient.Listener {
    private static final String SCREEN_LOGIN = "login";
    private static final String SCREEN_LOBBY = "lobby";
    private static final String SCREEN_MATCH = "match";

    private final String host;
    private final int port;
    private final Path assetsDir;

    private final NetworkClient net = new NetworkClient(this);
    private final Timer pingTimer = new Timer(10_000, e -> net.send(new Message(Msg.PING)));

    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final LoginPanel loginPanel;
    private final LobbyPanel lobbyPanel;
    private final MatchPanel matchPanel;

    private JsonObject myProfile; // profile từ LOGIN_RESULT
    private String currentScreen = SCREEN_LOGIN;
    private final Deque<JDialog> inviteDialogs = new ArrayDeque<>();

    public MainFrame(String host, int port, Path assetsDir) {
        super("GeoDuel — Đấu trường tọa độ");
        this.host = host;
        this.port = port;
        this.assetsDir = assetsDir;

        loginPanel = new LoginPanel(new LoginPanel.Actions() {
            @Override public void doLogin(String u, String p) { connectThen(Msg.LOGIN, u, p, null); }
            @Override public void doRegister(String u, String p, String d) { connectThen(Msg.REGISTER, u, p, d); }
        });

        lobbyPanel = new LobbyPanel(new LobbyPanel.Actions() {
            @Override public void invite(String username) {
                net.send(new Message(Msg.INVITE).put("target", username));
            }
            @Override public void refreshAll() { requestLobbyData(); }
            @Override public void logout() {
                net.send(new Message(Msg.LOGOUT));
                myProfile = null;
                showScreen(SCREEN_LOGIN);
                loginPanel.resetForNewSession();
            }
        });

        matchPanel = new MatchPanel(new MatchPanel.Actions() {
            @Override public void sendGuess(int matchId, int round, double lat, double lng) {
                net.send(new Message(Msg.GUESS).put("matchId", matchId).put("round", round)
                        .put("lat", lat).put("lng", lng));
            }
            @Override public void leaveMatch(int matchId) {
                net.send(new Message(Msg.LEAVE_MATCH).put("matchId", matchId));
            }
            @Override public void rematchChoice(int matchId, boolean again) {
                net.send(new Message(Msg.REMATCH_CHOICE).put("matchId", matchId).put("again", again));
            }
        });

        String mapError = matchPanel.loadMapAssets(assetsDir);
        if (mapError != null) {
            Log.warn("Client", mapError);
        }

        cardPanel.add(loginPanel, SCREEN_LOGIN);
        cardPanel.add(lobbyPanel, SCREEN_LOBBY);
        cardPanel.add(matchPanel, SCREEN_MATCH);
        setContentPane(cardPanel);

        // lớp phủ kết thúc trận nằm trên layered pane của cửa sổ
        JLayeredPane layers = getLayeredPane();
        layers.add(matchPanel.endOverlay(), JLayeredPane.MODAL_LAYER);
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { syncOverlayBounds(); }
            @Override public void componentShown(ComponentEvent e) { syncOverlayBounds(); }
        });

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1180, 760));
        setLocationRelativeTo(null);
        setExtendedState(MAXIMIZED_BOTH); // trải nghiệm toàn màn hình như game thật
        showScreen(SCREEN_LOGIN);
    }

    // ================= kết nối & đăng nhập =================

    private void connectThen(String msgType, String username, String password, String displayName) {
        if (!net.isConnected()) {
            try {
                net.connect(host, port);
                pingTimer.start();
            } catch (IOException ex) {
                loginPanel.showError("Không kết nối được máy chủ " + host + ":" + port);
                return;
            }
        }
        Message m = new Message(msgType).put("username", username).put("password", password);
        if (displayName != null) m.put("displayName", displayName);
        net.send(m);
    }

    private void requestLobbyData() {
        net.send(new Message(Msg.GET_ONLINE));
        net.send(new Message(Msg.GET_LEADERBOARD));
        net.send(new Message(Msg.GET_HISTORY));
    }

    private void showScreen(String name) {
        currentScreen = name;
        cards.show(cardPanel, name);
        matchPanel.endOverlay().setVisible(false);
    }

    private void syncOverlayBounds() {
        matchPanel.endOverlay().setBounds(0, 0,
                getContentPane().getWidth(), getContentPane().getHeight());
    }

    // ================= nhận thông điệp (đã ở EDT) =================

    @Override
    public void onMessage(Message msg) {
        switch (msg.type()) {
            case Msg.REGISTER_RESULT -> {
                if (msg.getBool("ok", false)) loginPanel.showInfo(msg.getString("message", ""));
                else loginPanel.showError(msg.getString("message", "Không đăng ký được."));
            }
            case Msg.LOGIN_RESULT -> {
                if (msg.getBool("ok", false)) {
                    myProfile = msg.getObject("profile");
                    applyProfileToLobby();
                    showScreen(SCREEN_LOBBY);
                    loginPanel.resetForNewSession();
                    requestLobbyData();
                } else {
                    loginPanel.showError(msg.getString("message", "Không đăng nhập được."));
                }
            }
            case Msg.ONLINE_LIST -> lobbyPanel.updateOnline(msg.getArray("players"));
            case Msg.LEADERBOARD -> lobbyPanel.updateLeaderboard(msg.getArray("rows"));
            case Msg.HISTORY -> lobbyPanel.updateHistory(msg.getArray("rows"));
            case Msg.INVITE_INCOMING -> showInviteDialog(msg);
            case Msg.INVITE_RESULT -> lobbyPanel.showToast(msg.getString("message", ""));
            case Msg.MATCH_START -> {
                closeInviteDialogs();
                matchPanel.startMatch(msg.data(),
                        myProfile != null ? myProfile.get("displayName").getAsString() : "Bạn");
                showScreen(SCREEN_MATCH);
            }
            case Msg.ROUND_START -> matchPanel.onRoundStart(msg.data());
            case Msg.GUESS_ACK -> matchPanel.onGuessAck();
            case Msg.OPPONENT_GUESSED -> matchPanel.onOpponentGuessed();
            case Msg.TIMER_SYNC -> matchPanel.onTimerSync(msg.getInt("remainingSec", 15));
            case Msg.ROUND_RESULT -> matchPanel.onRoundResult(msg.data());
            case Msg.MATCH_END -> {
                syncOverlayBounds();
                matchPanel.onMatchEnd(msg.data());
            }
            case Msg.REMATCH_WAIT -> matchPanel.onRematchWait();
            case Msg.SESSION_END -> {
                com.ltm.geoduel.client.audio.SoundManager.stopMusic();
                showScreen(SCREEN_LOBBY);
                lobbyPanel.showToast(msg.getString("message", "Phiên đấu kết thúc."));
                requestLobbyData();
            }
            case Msg.ERROR -> {
                String text = msg.getString("message", "Lỗi không xác định.");
                if (SCREEN_LOBBY.equals(currentScreen)) lobbyPanel.showToast(text);
                else Log.warn("Client", "Loi tu server: " + text);
            }
            case Msg.PONG -> { /* heartbeat */ }
            default -> Log.warn("Client", "Thong diep chua xu ly: " + msg.type());
        }
    }

    private void applyProfileToLobby() {
        if (myProfile == null) return;
        lobbyPanel.setProfile(
                myProfile.get("username").getAsString(),
                myProfile.get("displayName").getAsString(),
                myProfile.get("elo").getAsInt(),
                myProfile.get("wins").getAsInt(),
                myProfile.get("losses").getAsInt(),
                myProfile.get("draws").getAsInt());
    }

    @Override
    public void onDisconnected(String reason) {
        com.ltm.geoduel.client.audio.SoundManager.stopMusic();
        pingTimer.stop();
        closeInviteDialogs();
        myProfile = null;
        showScreen(SCREEN_LOGIN);
        loginPanel.resetForNewSession();
        loginPanel.showError(reason);
    }

    // ================= lời mời đến =================

    private void showInviteDialog(Message msg) {
        int inviteId = msg.getInt("inviteId", -1);
        String fromName = msg.getString("fromDisplayName", msg.getString("from", "?"));
        int fromElo = msg.getInt("fromElo", 0);

        JDialog dlg = new JDialog(this, false);
        dlg.setUndecorated(true);
        RoundedPanel card = new RoundedPanel(new GridBagLayout(), Theme.ELEVATED, Theme.AMBER_DARK);
        card.setBorder(BorderFactory.createEmptyBorder(20, 28, 18, 28));

        JLabel icon = new JLabel(Icons.compass(36, Theme.AMBER, Theme.AMBER));
        JLabel title = new JLabel("Lời mời thi đấu", SwingConstants.CENTER);
        title.setFont(Theme.bold(17));
        title.setForeground(Theme.TEXT);
        JLabel who = new JLabel(fromName + "  ·  Elo " + fromElo, SwingConstants.CENTER);
        who.setFont(Theme.font(14));
        who.setForeground(Theme.TEXT_MUTED);

        PillButton accept = new PillButton("Chấp nhận", PillButton.Kind.PRIMARY);
        PillButton decline = new PillButton("Từ chối", PillButton.Kind.GHOST);
        accept.addActionListener(e -> {
            net.send(new Message(Msg.INVITE_RESPONSE).put("inviteId", inviteId).put("accepted", true));
            dlg.dispose();
            inviteDialogs.remove(dlg);
        });
        decline.addActionListener(e -> {
            net.send(new Message(Msg.INVITE_RESPONSE).put("inviteId", inviteId).put("accepted", false));
            dlg.dispose();
            inviteDialogs.remove(dlg);
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        buttons.setOpaque(false);
        buttons.add(accept);
        buttons.add(decline);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.insets = new java.awt.Insets(3, 0, 3, 0);
        c.gridy = 0; card.add(icon, c);
        c.gridy = 1; card.add(title, c);
        c.gridy = 2; card.add(who, c);
        c.gridy = 3; c.insets = new java.awt.Insets(14, 0, 0, 0); card.add(buttons, c);

        dlg.setContentPane(card);
        dlg.pack();
        // xếp chồng nhẹ nếu có nhiều lời mời cùng lúc
        int offset = inviteDialogs.size() * 26;
        dlg.setLocation(getX() + (getWidth() - dlg.getWidth()) / 2 + offset,
                getY() + (getHeight() - dlg.getHeight()) / 2 + offset);
        dlg.setAlwaysOnTop(true);
        inviteDialogs.add(dlg);
        dlg.setVisible(true);
    }

    private void closeInviteDialogs() {
        while (!inviteDialogs.isEmpty()) inviteDialogs.poll().dispose();
    }
}
