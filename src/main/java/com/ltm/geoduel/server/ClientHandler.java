package com.ltm.geoduel.server;

import com.ltm.geoduel.common.Log;
import com.ltm.geoduel.common.Message;
import com.ltm.geoduel.common.Msg;
import com.ltm.geoduel.server.model.UserProfile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Một thread cho một kết nối client: đọc từng dòng JSON, xử lý, trả lời.
 * Ghi ra socket được đồng bộ trên {@code writeLock} vì nhiều thread
 * (engine, registry broadcast) có thể cùng gửi tới một client.
 */
public class ClientHandler implements Runnable {
    /** Không nhận được gì (kể cả PING 10s/lần) trong khoảng này → coi là mất kết nối. */
    private static final int SO_TIMEOUT_MS = 45_000;

    private final Socket socket;
    private final GameServer server;
    private final Object writeLock = new Object();

    private BufferedReader in;
    private BufferedWriter out;

    private volatile UserProfile profile;      // null khi chưa đăng nhập
    private volatile MatchEngine currentMatch; // null khi không trong trận
    private volatile boolean closed = false;

    public ClientHandler(Socket socket, GameServer server) {
        this.socket = socket;
        this.server = server;
    }

    public UserProfile profile() { return profile; }
    public MatchEngine currentMatch() { return currentMatch; }
    public void setCurrentMatch(MatchEngine m) { this.currentMatch = m; }
    public void refreshProfileElo(int newElo) {
        UserProfile p = profile;
        if (p != null) p.elo = newElo;
    }

    @Override
    public void run() {
        String peer = socket.getRemoteSocketAddress().toString();
        try {
            socket.setSoTimeout(SO_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            Log.info("Handler", "Ket noi moi tu " + peer);

            String line;
            while ((line = in.readLine()) != null) {
                Message msg = Message.parse(line);
                if (msg == null) {
                    Log.warn("Handler", "Bo qua dong khong hop le tu " + peer);
                    continue;
                }
                try {
                    dispatch(msg);
                } catch (Exception ex) {
                    // Lỗi xử lý một thông điệp không được làm chết kết nối.
                    Log.error("Handler", "Loi xu ly " + msg.type() + " tu " + who(), ex);
                    send(new Message(Msg.ERROR).put("message", "Lỗi máy chủ khi xử lý yêu cầu."));
                }
            }
            Log.info("Handler", "Client dong ket noi: " + who());
        } catch (SocketTimeoutException ex) {
            Log.warn("Handler", "Het thoi gian cho du lieu (mat ket noi?): " + who());
        } catch (IOException ex) {
            Log.info("Handler", "Mat ket noi: " + who() + " (" + ex.getMessage() + ")");
        } finally {
            cleanup();
        }
    }

    private String who() {
        UserProfile p = profile;
        return p != null ? p.username : socket.getRemoteSocketAddress().toString();
    }

    // ================= gửi =================

    /** Gửi một thông điệp (một dòng JSON). An toàn khi gọi từ nhiều thread. */
    public void send(Message msg) {
        if (closed) return;
        try {
            synchronized (writeLock) {
                out.write(msg.toJsonLine());
                out.write('\n');
                out.flush();
            }
        } catch (IOException ex) {
            // Reader thread sẽ phát hiện và cleanup; ở đây chỉ đóng socket cho readLine thoát ra.
            forceClose();
        }
    }

    private void forceClose() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) {}
    }

    // ================= dispatch =================

    private void dispatch(Message msg) throws Exception {
        String type = msg.type();

        // Chưa đăng nhập: chỉ chấp nhận REGISTER / LOGIN / PING.
        if (profile == null && !Msg.REGISTER.equals(type) && !Msg.LOGIN.equals(type) && !Msg.PING.equals(type)) {
            send(new Message(Msg.ERROR).put("message", "Bạn chưa đăng nhập."));
            return;
        }

        switch (type) {
            case Msg.PING -> send(new Message(Msg.PONG));
            case Msg.REGISTER -> handleRegister(msg);
            case Msg.LOGIN -> handleLogin(msg);
            case Msg.LOGOUT -> handleLogout();
            case Msg.GET_ONLINE -> send(new Message(Msg.ONLINE_LIST).put("players", server.registry().snapshotOnline()));
            case Msg.GET_LEADERBOARD -> send(new Message(Msg.LEADERBOARD).put("rows", server.userDao().leaderboard(100)));
            case Msg.GET_HISTORY -> send(new Message(Msg.HISTORY).put("rows", server.matchDao().historyForUser(profile.id, 50)));
            case Msg.INVITE -> server.handleInvite(this, msg.getString("target", ""));
            case Msg.INVITE_RESPONSE -> server.handleInviteResponse(this,
                    msg.getInt("inviteId", -1), msg.getBool("accepted", false));
            case Msg.GUESS -> {
                MatchEngine m = currentMatch;
                if (m != null && msg.getInt("matchId", -1) == m.matchId()) {
                    m.onGuess(profile.username, msg.getInt("round", -1),
                            msg.getDouble("lat", Double.NaN), msg.getDouble("lng", Double.NaN));
                }
            }
            case Msg.LEAVE_MATCH -> {
                MatchEngine m = currentMatch;
                if (m != null && msg.getInt("matchId", -1) == m.matchId()) {
                    m.onPlayerGone(profile.username, true);
                }
            }
            case Msg.REMATCH_CHOICE -> {
                MatchEngine m = currentMatch;
                if (m != null && msg.getInt("matchId", -1) == m.matchId()) {
                    m.onRematchChoice(profile.username, msg.getBool("again", false));
                }
            }
            default -> Log.warn("Handler", "Thong diep khong ro loai: " + type + " tu " + who());
        }
    }

    private void handleRegister(Message msg) throws Exception {
        String username = msg.getString("username", "").trim();
        String password = msg.getString("password", "");
        String displayName = msg.getString("displayName", "").trim();
        if (displayName.isEmpty()) displayName = username;

        String error = validateCredentials(username, password);
        if (error == null && displayName.length() > 64) error = "Tên hiển thị tối đa 64 ký tự";
        if (error == null) error = server.userDao().register(username, password, displayName);

        send(new Message(Msg.REGISTER_RESULT)
                .put("ok", error == null)
                .put("message", error == null ? "Tạo tài khoản thành công, hãy đăng nhập." : error));
    }

    private static String validateCredentials(String username, String password) {
        if (username.length() < 3 || username.length() > 32) return "Tên đăng nhập phải từ 3 đến 32 ký tự";
        if (!username.matches("[a-zA-Z0-9_]+")) return "Tên đăng nhập chỉ gồm chữ, số và dấu gạch dưới";
        if (password.length() < 4) return "Mật khẩu phải có ít nhất 4 ký tự";
        return null;
    }

    private void handleLogin(Message msg) throws Exception {
        if (profile != null) {
            send(new Message(Msg.LOGIN_RESULT).put("ok", false).put("message", "Bạn đã đăng nhập rồi."));
            return;
        }
        String username = msg.getString("username", "").trim();
        String password = msg.getString("password", "");
        UserProfile p = server.userDao().authenticate(username, password);
        if (p == null) {
            send(new Message(Msg.LOGIN_RESULT).put("ok", false)
                    .put("message", "Sai tên đăng nhập hoặc mật khẩu."));
            return;
        }
        if (!server.registry().login(p, this)) {
            send(new Message(Msg.LOGIN_RESULT).put("ok", false)
                    .put("message", "Tài khoản này đang đăng nhập ở nơi khác."));
            return;
        }
        profile = p;
        com.google.gson.JsonObject prof = new com.google.gson.JsonObject();
        prof.addProperty("username", p.username);
        prof.addProperty("displayName", p.displayName);
        prof.addProperty("elo", p.elo);
        prof.addProperty("wins", p.wins);
        prof.addProperty("losses", p.losses);
        prof.addProperty("draws", p.draws);
        send(new Message(Msg.LOGIN_RESULT).put("ok", true).put("message", "OK").put("profile", prof));
        Log.info("Handler", username + " dang nhap (Elo " + p.elo + ")");
        server.registry().broadcastOnlineList();
    }

    private void handleLogout() {
        MatchEngine m = currentMatch;
        if (m != null) m.onPlayerGone(profile.username, true);
        String username = profile != null ? profile.username : null;
        profile = null;
        currentMatch = null;
        if (username != null) {
            server.inviteManager().removeInvolving(username);
            server.registry().logout(username);
            server.registry().broadcastOnlineList();
            Log.info("Handler", username + " dang xuat");
        }
    }

    /** Dọn dẹp khi kết nối kết thúc (mọi đường thoát của run() đều đi qua đây). */
    private void cleanup() {
        closed = true;
        MatchEngine m = currentMatch;
        UserProfile p = profile;
        if (m != null && p != null) {
            try {
                m.onPlayerGone(p.username, false); // xử như mất kết nối
            } catch (Exception ex) {
                Log.error("Handler", "Loi xu ly mat ket noi cua " + p.username, ex);
            }
        }
        if (p != null) {
            server.inviteManager().removeInvolving(p.username);
            server.registry().logout(p.username);
            server.registry().broadcastOnlineList();
        }
        try { socket.close(); } catch (IOException ignored) {}
    }
}
