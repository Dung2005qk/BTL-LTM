package com.ltm.geoduel.sim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ltm.geoduel.common.Message;
import com.ltm.geoduel.common.Msg;
import com.ltm.geoduel.server.GameServer;
import com.ltm.geoduel.server.ServerConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Kiểm thử tích hợp đầu-cuối: khởi động server thật (DB geoduel_test, cổng test),
 * cho các bot thi đấu qua TCP thật và kiểm tra từng quy tắc của đặc tả.
 *
 * Chạy: java -cp target/geoduel.jar com.ltm.geoduel.sim.IntegrationSim config-test.properties
 * (yêu cầu: schema đã tạo + AssetSeeder đã nạp địa điểm vào geoduel_test)
 */
public final class IntegrationSim {
    private static final List<String> passed = new ArrayList<>();
    private static final List<String> failed = new ArrayList<>();

    private static ServerConfig config;
    private static String host;
    private static int port;

    public static void main(String[] args) throws Exception {
        String cfgPath = args.length > 0 ? args[0] : "config-test.properties";
        config = new ServerConfig(cfgPath);
        host = config.host();
        port = config.port();

        prepareDatabase();
        startServer();
        Thread.sleep(800);

        runScenario("A. Tran dau day du 5 luot + Elo + DB", IntegrationSim::scenarioFullMatch);
        runScenario("B. Het gio khong doan -> 0 diem; thoat giua tran -> xu thua", IntegrationSim::scenarioTimeoutAndQuit);
        runScenario("C. Mat ket noi giua tran -> doi thu thang", IntegrationSim::scenarioDisconnect);
        runScenario("D. Tu choi loi moi", IntegrationSim::scenarioDecline);
        runScenario("E. Khong the moi nguoi dang ban", IntegrationSim::scenarioInviteBusy);
        runScenario("F. Choi lai: mot ben thoat -> phien ket thuc", IntegrationSim::scenarioRematchDeclined);
        runScenario("G. Bang xep hang va lich su", IntegrationSim::scenarioLeaderboardHistory);
        runScenario("H. Nguoi moi thoat truoc khi ben kia tra loi", IntegrationSim::scenarioInviterGone);

        System.out.println();
        System.out.println("==================== KET QUA ====================");
        passed.forEach(s -> System.out.println("  PASS  " + s));
        failed.forEach(s -> System.out.println("  FAIL  " + s));
        System.out.println("Tong: " + passed.size() + " PASS, " + failed.size() + " FAIL");
        System.exit(failed.isEmpty() ? 0 : 1);
    }

    // ================= hạ tầng =================

    private static void prepareDatabase() throws Exception {
        try (Connection c = DriverManager.getConnection(config.dbUrl(), config.dbUser(), config.dbPassword());
             Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM match_rounds");
            st.executeUpdate("DELETE FROM matches");
            st.executeUpdate("DELETE FROM users");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM locations WHERE active = 1")) {
                rs.next();
                int locs = rs.getInt(1);
                if (locs < config.roundsPerMatch()) {
                    throw new IllegalStateException("geoduel_test chi co " + locs +
                            " dia diem. Hay chay scripts\\seed-assets.bat truoc.");
                }
                System.out.println("[Sim] DB test san sang (" + locs + " dia diem)");
            }
        }
    }

    private static void startServer() {
        Thread t = new Thread(() -> {
            try {
                new GameServer(config).serve();
            } catch (Exception ex) {
                System.out.println("[Sim] SERVER CHET: " + ex);
                ex.printStackTrace();
                System.exit(2);
            }
        }, "test-server");
        t.setDaemon(true);
        t.start();
    }

    private static void runScenario(String name, Scenario body) {
        System.out.println();
        System.out.println("--- " + name + " ---");
        try {
            body.run();
            passed.add(name);
            System.out.println("--- PASS ---");
        } catch (Throwable ex) {
            failed.add(name + "  (" + ex.getMessage() + ")");
            System.out.println("--- FAIL: " + ex + " ---");
            ex.printStackTrace(System.out);
        }
    }

    @FunctionalInterface private interface Scenario { void run() throws Exception; }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
        System.out.println("    ok: " + what);
    }

    /** A mời B và B chấp nhận; trả về [msgStartA, msgStartB]. */
    private static Message[] startMatch(BotClient a, BotClient b) throws Exception {
        a.send(new Message(Msg.INVITE).put("target", b.name));
        Message incoming = b.waitFor(Msg.INVITE_INCOMING, 5000);
        b.send(new Message(Msg.INVITE_RESPONSE)
                .put("inviteId", incoming.getInt("inviteId", -1)).put("accepted", true));
        Message sa = a.waitFor(Msg.MATCH_START, 5000);
        Message sb = b.waitFor(Msg.MATCH_START, 5000);
        return new Message[]{sa, sb};
    }

    // ================= kịch bản =================

    private static void scenarioFullMatch() throws Exception {
        try (BotClient a = new BotClient("bot_a", host, port);
             BotClient b = new BotClient("bot_b", host, port)) {
            a.registerAndLogin("123456", "Bot A");
            b.registerAndLogin("123456", "Bot B");

            Message[] starts = startMatch(a, b);
            int matchId = starts[0].getInt("matchId", -1);
            check(matchId > 0, "MATCH_START co matchId");
            check(starts[0].getObject("opponent").get("username").getAsString().equals("bot_b"),
                    "A thay dung doi thu");

            int totalA = 0, totalB = 0;
            for (int round = 1; round <= config.roundsPerMatch(); round++) {
                Message ra = a.waitFor(Msg.ROUND_START, 15000);
                Message rb = b.waitFor(Msg.ROUND_START, 15000);
                check(ra.getInt("round", -1) == round, "luot " + round + " bat dau dung so");
                JsonArray imgs = ra.getArray("images");
                check(imgs != null && imgs.size() >= 3 && imgs.size() <= 5,
                        "luot " + round + ": 3-5 anh manh moi (" + (imgs == null ? 0 : imgs.size()) + ")");
                check(!ra.data().has("targetLat") && !ra.data().has("lat"),
                        "ROUND_START khong lo toa do dich");
                JsonObject img0 = imgs.get(0).getAsJsonObject();
                check(img0.get("b64").getAsString().length() > 10_000, "anh Base64 co du lieu that");
                check(img0.has("pano"), "moi anh co co pano (phang/360)");

                // A đoán gần Hà Nội, B đoán gần TP.HCM
                a.send(new Message(Msg.GUESS).put("matchId", matchId).put("round", round)
                        .put("lat", 21.0285).put("lng", 105.8522));
                a.waitFor(Msg.GUESS_ACK, 5000);
                b.waitFor(Msg.OPPONENT_GUESSED, 5000);

                // A gửi lần 2 với toạ độ khác — server phải giữ dự đoán ban đầu (khoá sau khi gửi)
                a.send(new Message(Msg.GUESS).put("matchId", matchId).put("round", round)
                        .put("lat", 0.0).put("lng", 0.0));

                b.send(new Message(Msg.GUESS).put("matchId", matchId).put("round", round)
                        .put("lat", 10.7769).put("lng", 106.7009));
                b.waitFor(Msg.GUESS_ACK, 5000);

                Message resA = a.waitFor(Msg.ROUND_RESULT, 10000);
                Message resB = b.waitFor(Msg.ROUND_RESULT, 10000);
                JsonObject mineA = resA.getObject("mine");
                check(Math.abs(mineA.get("lat").getAsDouble() - 21.0285) < 1e-9,
                        "du doan cua A giu nguyen lan gui dau (khoa sau Send)");
                check(resA.data().has("targetLat"), "ROUND_RESULT co vi tri dich");
                int scoreA = mineA.get("score").getAsInt();
                int scoreB = resB.getObject("mine").get("score").getAsInt();
                check(scoreA >= 0 && scoreA <= 5000, "diem A trong [0,5000]: " + scoreA);
                check(resA.getObject("opp").get("score").getAsInt() == scoreB,
                        "hai phia thay cung mot ket qua");
                totalA += scoreA;
                totalB += scoreB;
                check(resA.getInt("myTotal", -1) == totalA, "tong diem A cong don dung");
            }

            Message endA = a.waitFor(Msg.MATCH_END, 15000);
            Message endB = b.waitFor(Msg.MATCH_END, 15000);
            String resultA = endA.getString("result", "");
            String resultB = endB.getString("result", "");
            check(endA.getInt("myTotal", -1) == totalA && endA.getInt("oppTotal", -1) == totalB,
                    "tong diem cuoi tran khop");
            boolean consistent = (resultA.equals("WIN") && resultB.equals("LOSE"))
                    || (resultA.equals("LOSE") && resultB.equals("WIN"))
                    || (resultA.equals("DRAW") && resultB.equals("DRAW"));
            check(consistent, "ket qua hai phia nhat quan: " + resultA + "/" + resultB);

            int changeA = endA.getInt("myEloChange", 0);
            int changeB = endB.getInt("myEloChange", 0);
            // hai bên cùng Elo 1000, K=32: thắng +16, thua -16, hoà 0
            if (resultA.equals("WIN")) check(changeA == 16 && changeB == -16, "Elo +16/-16 (A thang)");
            else if (resultA.equals("LOSE")) check(changeA == -16 && changeB == 16, "Elo -16/+16 (B thang)");
            else check(changeA == 0 && changeB == 0, "Elo 0/0 (hoa)");
            check(endA.getInt("myNewElo", 0) == 1000 + changeA, "Elo moi = 1000 + thay doi");

            // DB: 5 dòng lượt + kết quả trận + Elo người dùng
            try (Connection c = DriverManager.getConnection(config.dbUrl(), config.dbUser(), config.dbPassword())) {
                try (PreparedStatement st = c.prepareStatement(
                        "SELECT COUNT(*) FROM match_rounds WHERE match_id = ?")) {
                    st.setInt(1, matchId);
                    try (ResultSet rs = st.executeQuery()) {
                        rs.next();
                        check(rs.getInt(1) == config.roundsPerMatch(), "DB luu du " + config.roundsPerMatch() + " luot");
                    }
                }
                try (PreparedStatement st = c.prepareStatement(
                        "SELECT result, end_reason, total_score1, total_score2 FROM matches WHERE id = ?")) {
                    st.setInt(1, matchId);
                    try (ResultSet rs = st.executeQuery()) {
                        rs.next();
                        check(rs.getString("end_reason").equals("NORMAL"), "DB end_reason = NORMAL");
                        check(rs.getInt("total_score1") + rs.getInt("total_score2") == totalA + totalB,
                                "DB tong diem khop");
                    }
                }
                try (PreparedStatement st = c.prepareStatement(
                        "SELECT elo FROM users WHERE username = 'bot_a'")) {
                    try (ResultSet rs = st.executeQuery()) {
                        rs.next();
                        check(rs.getInt(1) == endA.getInt("myNewElo", -1), "DB Elo cua A da cap nhat");
                    }
                }
            }

            // Chơi lại: A đồng ý, B thoát → cả hai về sảnh
            a.send(new Message(Msg.REMATCH_CHOICE).put("matchId", matchId).put("again", true));
            a.waitFor(Msg.REMATCH_WAIT, 5000);
            b.send(new Message(Msg.REMATCH_CHOICE).put("matchId", matchId).put("again", false));
            a.waitFor(Msg.SESSION_END, 5000);
            b.waitFor(Msg.SESSION_END, 5000);
            check(true, "mot ben Thoat -> SESSION_END cho ca hai");
        }
    }

    private static void scenarioTimeoutAndQuit() throws Exception {
        try (BotClient a = new BotClient("bot_c", host, port);
             BotClient b = new BotClient("bot_d", host, port)) {
            a.registerAndLogin("123456", "Bot C");
            b.registerAndLogin("123456", "Bot D");
            Message[] starts = startMatch(a, b);
            int matchId = starts[0].getInt("matchId", -1);

            Message ra = a.waitFor(Msg.ROUND_START, 15000);
            b.waitFor(Msg.ROUND_START, 15000);
            int round = ra.getInt("round", -1);
            // chỉ A đoán; B để hết giờ
            long guessAt = System.currentTimeMillis();
            a.send(new Message(Msg.GUESS).put("matchId", matchId).put("round", round)
                    .put("lat", 16.06).put("lng", 108.22));
            a.waitFor(Msg.GUESS_ACK, 5000);

            // Luật mới: A đã nộp → server ép B chỉ còn round.snipe.sec giây, báo cả hai
            Message syncB = b.waitFor(Msg.TIMER_SYNC, 5000);
            check(syncB.getInt("remainingSec", -1) == config.roundSnipeSec(),
                    "TIMER_SYNC ep thoi han con " + config.roundSnipeSec() + " s");
            a.waitFor(Msg.TIMER_SYNC, 5000);

            Message resA = a.waitFor(Msg.ROUND_RESULT, (config.roundDurationSec() + 5) * 1000L);
            long roundEndedAfterMs = System.currentTimeMillis() - guessAt;
            check(roundEndedAfterMs < (config.roundSnipeSec() + 4) * 1000L,
                    "luot ket thuc som theo dong ho ep (" + roundEndedAfterMs + " ms)");
            check(!resA.getObject("opp").get("guessed").getAsBoolean(), "B khong doan (het gio)");
            check(resA.getObject("opp").get("score").getAsInt() == 0, "het gio -> 0 diem");
            check(resA.getObject("mine").get("score").getAsInt() > 0, "A co diem binh thuong");

            // B chủ động thoát giữa trận
            b.waitFor(Msg.ROUND_RESULT, 5000);
            b.send(new Message(Msg.LEAVE_MATCH).put("matchId", matchId));
            Message endB = b.waitFor(Msg.MATCH_END, 5000);
            check(endB.getString("result", "").equals("LOSE")
                    && endB.getString("reason", "").equals("YOU_LEFT"), "nguoi thoat bi xu thua");
            Message endA = a.waitFor(Msg.MATCH_END, 5000);
            check(endA.getString("result", "").equals("WIN")
                    && endA.getString("reason", "").equals("OPPONENT_LEFT"), "nguoi o lai duoc xu thang");
            check(endA.getInt("myEloChange", 0) == 16, "Elo van cap nhat khi doi thu bo tran");
            a.waitFor(Msg.SESSION_END, 5000);
            b.waitFor(Msg.SESSION_END, 5000);

            try (Connection c = DriverManager.getConnection(config.dbUrl(), config.dbUser(), config.dbPassword());
                 PreparedStatement st = c.prepareStatement("SELECT end_reason FROM matches WHERE id = ?")) {
                st.setInt(1, matchId);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    check(rs.getString(1).endsWith("_QUIT"), "DB ghi nhan ly do QUIT");
                }
            }
        }
    }

    private static void scenarioDisconnect() throws Exception {
        BotClient a = new BotClient("bot_e", host, port);
        BotClient b = new BotClient("bot_f", host, port);
        try {
            a.registerAndLogin("123456", "Bot E");
            b.registerAndLogin("123456", "Bot F");
            startMatch(a, b);
            a.waitFor(Msg.ROUND_START, 15000);
            b.waitFor(Msg.ROUND_START, 15000);

            b.closeAbruptly(); // mất kết nối đột ngột

            Message endA = a.waitFor(Msg.MATCH_END, 10000);
            check(endA.getString("result", "").equals("WIN")
                    && endA.getString("reason", "").equals("OPPONENT_DISCONNECTED"),
                    "server phat hien mat ket noi va xu thang nguoi con lai");
            a.waitFor(Msg.SESSION_END, 5000);

            // A phải trở lại trạng thái rảnh trong danh sách online
            a.drain();
            a.send(new Message(Msg.GET_ONLINE));
            Message online = a.waitFor(Msg.ONLINE_LIST, 5000);
            boolean aFree = false, fGone = true;
            for (JsonElement e : online.getArray("players")) {
                JsonObject p = e.getAsJsonObject();
                if (p.get("username").getAsString().equals("bot_e"))
                    aFree = p.get("status").getAsString().equals("FREE");
                if (p.get("username").getAsString().equals("bot_f")) fGone = false;
            }
            check(aFree, "nguoi con lai tro ve trang thai ranh");
            check(fGone, "nguoi mat ket noi bien khoi danh sach online");
        } finally {
            a.close();
            b.close();
        }
    }

    private static void scenarioDecline() throws Exception {
        try (BotClient a = new BotClient("bot_g", host, port);
             BotClient b = new BotClient("bot_h", host, port)) {
            a.registerAndLogin("123456", "Bot G");
            b.registerAndLogin("123456", "Bot H");
            a.send(new Message(Msg.INVITE).put("target", "bot_h"));
            Message inc = b.waitFor(Msg.INVITE_INCOMING, 5000);
            b.send(new Message(Msg.INVITE_RESPONSE).put("inviteId", inc.getInt("inviteId", -1))
                    .put("accepted", false));
            Message res = a.waitFor(Msg.INVITE_RESULT, 5000);
            check(!res.getBool("ok", true), "nguoi moi nhan duoc thong bao tu choi");
        }
    }

    private static void scenarioInviteBusy() throws Exception {
        try (BotClient a = new BotClient("bot_i", host, port);
             BotClient b = new BotClient("bot_j", host, port);
             BotClient c = new BotClient("bot_k", host, port)) {
            a.registerAndLogin("123456", "Bot I");
            b.registerAndLogin("123456", "Bot J");
            c.registerAndLogin("123456", "Bot K");
            startMatch(a, b); // a, b BUSY

            // đồng bộ trạng thái: danh sách online phải cho thấy a, b BUSY còn c FREE
            c.drain();
            c.send(new Message(Msg.GET_ONLINE));
            Message online = c.waitFor(Msg.ONLINE_LIST, 5000);
            String stA = null, stC = null;
            for (JsonElement e : online.getArray("players")) {
                JsonObject p = e.getAsJsonObject();
                if (p.get("username").getAsString().equals("bot_i")) stA = p.get("status").getAsString();
                if (p.get("username").getAsString().equals("bot_k")) stC = p.get("status").getAsString();
            }
            check("BUSY".equals(stA), "nguoi trong tran hien BUSY trong danh sach online");
            check("FREE".equals(stC), "nguoi ngoai tran hien FREE");

            c.send(new Message(Msg.INVITE).put("target", "bot_i"));
            Message res = c.waitFor(Msg.INVITE_RESULT, 5000);
            check(!res.getBool("ok", true), "khong the moi nguoi dang ban");

            // tự mời chính mình phải bị chặn
            c.send(new Message(Msg.INVITE).put("target", "bot_k"));
            Message selfRes = c.waitFor(Msg.INVITE_RESULT, 5000);
            check(!selfRes.getBool("ok", true), "khong the tu moi chinh minh");
            // dọn: a thoát trận
            a.send(new Message(Msg.LEAVE_MATCH).put("matchId",
                    -1 /* sai matchId: server phai bo qua */));
            check(true, "gui LEAVE_MATCH voi matchId sai khong lam server chet");
        }
    }

    private static void scenarioRematchDeclined() throws Exception {
        // đã kiểm trong scenario A phần cuối; ở đây kiểm REMATCH cả hai đồng ý → trận mới
        try (BotClient a = new BotClient("bot_l", host, port);
             BotClient b = new BotClient("bot_m", host, port)) {
            a.registerAndLogin("123456", "Bot L");
            b.registerAndLogin("123456", "Bot M");
            Message[] starts = startMatch(a, b);
            int matchId = starts[0].getInt("matchId", -1);
            // bỏ trận nhanh bằng cách cả 2 cùng đoán mọi lượt
            for (int round = 1; round <= config.roundsPerMatch(); round++) {
                Message ra = a.waitFor(Msg.ROUND_START, 15000);
                b.waitFor(Msg.ROUND_START, 15000);
                int r = ra.getInt("round", -1);
                a.send(new Message(Msg.GUESS).put("matchId", matchId).put("round", r)
                        .put("lat", 21.0).put("lng", 105.8));
                b.send(new Message(Msg.GUESS).put("matchId", matchId).put("round", r)
                        .put("lat", 21.0).put("lng", 105.8));
                a.waitFor(Msg.ROUND_RESULT, 10000);
                b.waitFor(Msg.ROUND_RESULT, 10000);
            }
            Message endA = a.waitFor(Msg.MATCH_END, 15000);
            check(endA.getString("result", "").equals("DRAW"), "cung du doan -> hoa");
            b.waitFor(Msg.MATCH_END, 15000);

            // cả hai chọn Chơi lại → trận mới bắt đầu
            a.send(new Message(Msg.REMATCH_CHOICE).put("matchId", matchId).put("again", true));
            b.send(new Message(Msg.REMATCH_CHOICE).put("matchId", matchId).put("again", true));
            Message newStartA = a.waitFor(Msg.MATCH_START, 10000);
            check(newStartA.getInt("matchId", -1) != matchId, "tran moi co matchId moi");
            b.waitFor(Msg.MATCH_START, 10000);
            // dọn
            a.send(new Message(Msg.LEAVE_MATCH).put("matchId", newStartA.getInt("matchId", -1)));
            a.waitFor(Msg.SESSION_END, 8000);
            b.waitFor(Msg.SESSION_END, 8000);
        }
    }

    private static void scenarioInviterGone() throws Exception {
        BotClient a = new BotClient("bot_n", host, port);
        try (BotClient b = new BotClient("bot_o", host, port)) {
            a.registerAndLogin("123456", "Bot N");
            b.registerAndLogin("123456", "Bot O");
            a.send(new Message(Msg.INVITE).put("target", "bot_o"));
            Message inc = b.waitFor(Msg.INVITE_INCOMING, 5000);

            a.closeAbruptly(); // người mời rớt mạng TRƯỚC khi B trả lời
            Thread.sleep(600);

            b.send(new Message(Msg.INVITE_RESPONSE)
                    .put("inviteId", inc.getInt("inviteId", -1)).put("accepted", true));
            Message err = b.waitFor(Msg.ERROR, 5000);
            check(!err.getString("message", "").isEmpty(),
                    "chap nhan loi moi cua nguoi da thoat -> bao loi ro rang, khong treo");

            // B vẫn phải FREE để tiếp tục được mời
            b.drain();
            b.send(new Message(Msg.GET_ONLINE));
            Message online = b.waitFor(Msg.ONLINE_LIST, 5000);
            boolean bFree = false;
            for (JsonElement e : online.getArray("players")) {
                JsonObject p = e.getAsJsonObject();
                if (p.get("username").getAsString().equals("bot_o")) {
                    bFree = "FREE".equals(p.get("status").getAsString());
                }
            }
            check(bFree, "nguoi duoc moi van FREE sau loi moi hong");
        } finally {
            a.close();
        }
    }

    private static void scenarioLeaderboardHistory() throws Exception {
        try (BotClient a = new BotClient("bot_a", host, port)) {
            a.send(new Message(Msg.LOGIN).put("username", "bot_a").put("password", "123456"));
            Message login = a.waitFor(Msg.LOGIN_RESULT, 5000);
            check(login.getBool("ok", false), "bot_a dang nhap lai duoc");

            a.send(new Message(Msg.GET_LEADERBOARD));
            Message lb = a.waitFor(Msg.LEADERBOARD, 5000);
            JsonArray rows = lb.getArray("rows");
            check(rows.size() >= 2, "bang xep hang co du nguoi choi");
            int prevElo = Integer.MAX_VALUE;
            for (JsonElement e : rows) {
                int elo = e.getAsJsonObject().get("elo").getAsInt();
                check(elo <= prevElo, "bang xep hang sap theo Elo giam dan");
                prevElo = elo;
            }

            a.send(new Message(Msg.GET_HISTORY));
            Message hist = a.waitFor(Msg.HISTORY, 5000);
            check(hist.getArray("rows").size() >= 1, "lich su cua bot_a co it nhat 1 tran");
            JsonObject h0 = hist.getArray("rows").get(0).getAsJsonObject();
            check(h0.has("eloChange") && h0.has("result"), "dong lich su co ket qua + thay doi Elo");
        }
    }
}
