package com.ltm.geoduel.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ltm.geoduel.common.EloCalculator;
import com.ltm.geoduel.common.GeoUtil;
import com.ltm.geoduel.common.Log;
import com.ltm.geoduel.common.Message;
import com.ltm.geoduel.common.Msg;
import com.ltm.geoduel.server.model.LocationData;
import com.ltm.geoduel.server.model.UserProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Máy trạng thái của MỘT trận đấu 5 lượt giữa hai người chơi.
 * Server giữ toàn quyền: chọn địa điểm, quản thời gian, chấm điểm, cập nhật Elo.
 * Mọi chuyển trạng thái đồng bộ trên {@code lock}; timer chạy trên scheduler dùng chung của server.
 */
public class MatchEngine {
    private enum State { STARTING, ROUND_ACTIVE, ROUND_RESULT, AWAITING_REMATCH, CLOSED }

    /** Trạng thái một người chơi trong trận. */
    private static class Slot {
        final ClientHandler handler;
        final UserProfile profile;   // ảnh chụp hồ sơ lúc vào trận (elo dùng để tính Elo mới)
        int total = 0;
        boolean rematch = false;
        // dữ liệu lượt hiện tại
        boolean locked = false;
        Double gLat = null, gLng = null;
        Double distKm = null;
        int score = 0;

        Slot(ClientHandler handler, UserProfile profile) {
            this.handler = handler;
            this.profile = profile;
        }

        void resetRound() {
            locked = false;
            gLat = null; gLng = null;
            distKm = null;
            score = 0;
        }
    }

    private final GameServer server;
    private final int matchId;
    private final Slot p1, p2;
    private final List<LocationData> locations;
    private final int totalRounds;
    private final Object lock = new Object();

    private State state = State.STARTING;
    private int currentRound = 0; // 1-based sau khi startRound
    private ScheduledFuture<?> pendingTask;
    private long roundDeadlineAtMs; // thời điểm hết hạn lượt hiện tại (server là chuẩn)

    public MatchEngine(GameServer server, int matchId,
                       ClientHandler h1, UserProfile prof1,
                       ClientHandler h2, UserProfile prof2,
                       List<LocationData> locations) {
        this.server = server;
        this.matchId = matchId;
        this.p1 = new Slot(h1, prof1);
        this.p2 = new Slot(h2, prof2);
        this.locations = locations;
        this.totalRounds = locations.size();
    }

    public int matchId() { return matchId; }

    // ================= vòng đời =================

    /** Gửi MATCH_START cho hai bên rồi hẹn giờ bắt đầu lượt 1. */
    public void start() {
        synchronized (lock) {
            sendTo(p1, matchStartMsg(p1, p2, 1));
            sendTo(p2, matchStartMsg(p2, p1, 2));
            pendingTask = server.scheduler().schedule(this::startNextRound, 2, TimeUnit.SECONDS);
        }
        Log.info("Match#" + matchId, p1.profile.username + " vs " + p2.profile.username +
                 " bat dau (" + totalRounds + " luot)");
    }

    private Message matchStartMsg(Slot me, Slot opp, int youAre) {
        JsonObject opponent = new JsonObject();
        opponent.addProperty("username", opp.profile.username);
        opponent.addProperty("displayName", opp.profile.displayName);
        opponent.addProperty("elo", opp.profile.elo);
        return new Message(Msg.MATCH_START)
                .put("matchId", matchId)
                .put("opponent", opponent)
                .put("totalRounds", totalRounds)
                .put("youAre", youAre);
    }

    private void startNextRound() {
        Message toP1, toP2;
        synchronized (lock) {
            if (state == State.CLOSED) return;
            currentRound++;
            p1.resetRound();
            p2.resetRound();
            state = State.ROUND_ACTIVE;

            LocationData loc = locations.get(currentRound - 1);
            JsonArray images = loadImagesBase64(loc);
            if (images.size() == 0) {
                // Dữ liệu hỏng — không thể tiếp tục công bằng: huỷ phiên, không tính Elo.
                Log.error("Match#" + matchId, "Khong doc duoc anh cua dia diem " + loc.slug, null);
                abortSession("Lỗi dữ liệu địa điểm trên server, trận đấu bị huỷ.");
                return;
            }
            int duration = server.config().roundDurationSec();
            Message roundStart = new Message(Msg.ROUND_START)
                    .put("matchId", matchId)
                    .put("round", currentRound)
                    .put("images", images)
                    .put("durationSec", duration);
            toP1 = roundStart;
            toP2 = roundStart;

            final int roundNo = currentRound;
            roundDeadlineAtMs = System.currentTimeMillis() + duration * 1000L;
            pendingTask = server.scheduler().schedule(
                    () -> onDeadline(roundNo), duration, TimeUnit.SECONDS);
            Log.info("Match#" + matchId, "Luot " + currentRound + ": " + loc.slug +
                     " (" + images.size() + " anh)");
        }
        sendTo(p1, toP1);
        sendTo(p2, toP2);
    }

    /** Mỗi phần tử: {"b64": "...", "pano": true/false}. Không bao giờ kèm toạ độ. */
    private JsonArray loadImagesBase64(LocationData loc) {
        JsonArray arr = new JsonArray();
        for (LocationData.ClueImage img : loc.images) {
            Path file = Path.of(server.config().assetsDir()).resolve(img.path());
            try {
                byte[] bytes = Files.readAllBytes(file);
                JsonObject o = new JsonObject();
                o.addProperty("b64", Base64.getEncoder().encodeToString(bytes));
                o.addProperty("pano", img.pano());
                arr.add(o);
            } catch (Exception ex) {
                Log.warn("Match#" + matchId, "Bo qua anh loi " + file + ": " + ex.getMessage());
            }
        }
        return arr;
    }

    // ================= dự đoán =================

    /** Nhận dự đoán từ một người chơi. Kiểm tra mọi điều kiện — client không đáng tin. */
    public void onGuess(String username, int round, double lat, double lng) {
        Slot me, opp;
        boolean bothLocked;
        Message timerSync = null;
        synchronized (lock) {
            if (state != State.ROUND_ACTIVE || round != currentRound) return;
            if (!GeoUtil.isValidCoord(lat, lng)) return;
            me = slotOf(username);
            if (me == null || me.locked) return; // đã gửi rồi thì không được đổi
            opp = other(me);
            me.locked = true;
            me.gLat = lat;
            me.gLng = lng;
            bothLocked = opp.locked;

            // Luật: một bên đã nộp → bên kia chỉ còn tối đa round.snipe.sec giây.
            if (!bothLocked) {
                long snipeMs = server.config().roundSnipeSec() * 1000L;
                long remainMs = roundDeadlineAtMs - System.currentTimeMillis();
                if (remainMs > snipeMs) {
                    if (pendingTask != null) pendingTask.cancel(false);
                    roundDeadlineAtMs = System.currentTimeMillis() + snipeMs;
                    final int roundNo = currentRound;
                    pendingTask = server.scheduler().schedule(
                            () -> onDeadline(roundNo), snipeMs, TimeUnit.MILLISECONDS);
                    timerSync = new Message(Msg.TIMER_SYNC)
                            .put("round", round)
                            .put("remainingSec", server.config().roundSnipeSec());
                }
            }
        }
        sendTo(me, new Message(Msg.GUESS_ACK).put("round", round));
        sendTo(opp, new Message(Msg.OPPONENT_GUESSED).put("round", round));
        if (timerSync != null) {
            sendTo(me, timerSync);
            sendTo(opp, timerSync);
        }
        if (bothLocked) completeRound(round);
    }

    private void onDeadline(int roundNo) {
        completeRound(roundNo);
    }

    /** Chốt lượt: tính khoảng cách + điểm, lưu DB, gửi kết quả, hẹn lượt kế / kết thúc trận. */
    private void completeRound(int roundNo) {
        Message toP1, toP2;
        boolean isLastRound;
        synchronized (lock) {
            if (state != State.ROUND_ACTIVE || roundNo != currentRound) return; // idempotent
            state = State.ROUND_RESULT;
            if (pendingTask != null) pendingTask.cancel(false);

            LocationData loc = locations.get(currentRound - 1);
            score(p1, loc);
            score(p2, loc);
            p1.total += p1.score;
            p2.total += p2.score;

            try {
                server.matchDao().saveRound(matchId, currentRound, loc.id,
                        p1.gLat, p1.gLng, p1.distKm, p1.score,
                        p2.gLat, p2.gLng, p2.distKm, p2.score);
            } catch (Exception ex) {
                Log.error("Match#" + matchId, "Khong luu duoc ket qua luot " + currentRound, ex);
            }

            isLastRound = currentRound >= totalRounds;
            int nextIn = server.config().roundResultSec();
            toP1 = roundResultMsg(p1, p2, loc, nextIn);
            toP2 = roundResultMsg(p2, p1, loc, nextIn);

            if (!isLastRound) {
                pendingTask = server.scheduler().schedule(this::startNextRound, nextIn, TimeUnit.SECONDS);
            } else {
                pendingTask = server.scheduler().schedule(this::finishNormally, nextIn, TimeUnit.SECONDS);
            }
            Log.info("Match#" + matchId, "Luot " + currentRound + " xong: " +
                     p1.profile.username + "=" + p1.score + " (" + fmtKm(p1.distKm) + "), " +
                     p2.profile.username + "=" + p2.score + " (" + fmtKm(p2.distKm) + ")");
        }
        sendTo(p1, toP1);
        sendTo(p2, toP2);
    }

    private void score(Slot s, LocationData loc) {
        if (s.gLat != null && s.gLng != null) {
            s.distKm = GeoUtil.haversineKm(s.gLat, s.gLng, loc.targetLat, loc.targetLng);
            s.score = GeoUtil.roundScore(s.distKm);
        } else {
            s.distKm = null;
            s.score = 0;
        }
    }

    private Message roundResultMsg(Slot me, Slot opp, LocationData loc, int nextInSec) {
        return new Message(Msg.ROUND_RESULT)
                .put("matchId", matchId)
                .put("round", currentRound)
                .put("locationName", loc.name)
                .put("country", loc.country)
                .put("targetLat", loc.targetLat)
                .put("targetLng", loc.targetLng)
                .put("mine", sideJson(me))
                .put("opp", sideJson(opp))
                .put("myTotal", me.total)
                .put("oppTotal", opp.total)
                .put("nextInSec", nextInSec)
                .put("isLastRound", currentRound >= totalRounds);
    }

    private JsonObject sideJson(Slot s) {
        JsonObject o = new JsonObject();
        o.addProperty("guessed", s.gLat != null);
        if (s.gLat != null) {
            o.addProperty("lat", s.gLat);
            o.addProperty("lng", s.gLng);
            o.addProperty("distKm", s.distKm);
        }
        o.addProperty("score", s.score);
        return o;
    }

    // ================= kết thúc trận =================

    /** Kết thúc bình thường sau lượt 5: so tổng điểm, cập nhật Elo, chờ Chơi lại/Thoát. */
    private void finishNormally() {
        Message toP1, toP2;
        synchronized (lock) {
            if (state != State.ROUND_RESULT) return;
            state = State.AWAITING_REMATCH;

            double s1;
            String result;
            if (p1.total > p2.total) { s1 = EloCalculator.SCORE_WIN; result = "P1_WIN"; }
            else if (p1.total < p2.total) { s1 = EloCalculator.SCORE_LOSS; result = "P2_WIN"; }
            else { s1 = EloCalculator.SCORE_DRAW; result = "DRAW"; }

            EloUpdate elo = applyEndOfMatch(result, "NORMAL", s1);
            toP1 = matchEndMsg(p1, p2, s1 == 1 ? "WIN" : s1 == 0 ? "LOSE" : "DRAW", "NORMAL", elo.change1, elo.after1, elo.change2, elo.after2);
            toP2 = matchEndMsg(p2, p1, s1 == 0 ? "WIN" : s1 == 1 ? "LOSE" : "DRAW", "NORMAL", elo.change2, elo.after2, elo.change1, elo.after1);
        }
        sendTo(p1, toP1);
        sendTo(p2, toP2);
    }

    /** Một bên chủ động thoát (voluntary=true) hoặc mất kết nối (voluntary=false) TRONG trận. */
    public void onPlayerGone(String username, boolean voluntary) {
        Message toLeaver = null, toStayer;
        boolean stayerAlsoGetsSessionEnd = false;
        Slot leaver, stayer;
        synchronized (lock) {
            leaver = slotOf(username);
            if (leaver == null || state == State.CLOSED) return;
            stayer = other(leaver);

            if (state == State.AWAITING_REMATCH) {
                // Trận đã xong, đang ở màn Chơi lại/Thoát → chỉ kết thúc phiên.
                closeSessionLocked();
                toStayer = new Message(Msg.SESSION_END)
                        .put("message", voluntary ? "Đối thủ đã rời phiên đấu." : "Đối thủ đã mất kết nối.");
            } else {
                // Trận đang diễn ra → xử thua người rời trận.
                if (pendingTask != null) pendingTask.cancel(false);
                boolean leaverIsP1 = leaver == p1;
                String result = leaverIsP1 ? "P2_WIN" : "P1_WIN";
                String reasonDb = (leaverIsP1 ? "P1_" : "P2_") + (voluntary ? "QUIT" : "DISCONNECT");
                double s1 = leaverIsP1 ? EloCalculator.SCORE_LOSS : EloCalculator.SCORE_WIN;

                EloUpdate elo = applyEndOfMatch(result, reasonDb, s1);
                int stayerChange = leaverIsP1 ? elo.change2 : elo.change1;
                int stayerAfter = leaverIsP1 ? elo.after2 : elo.after1;
                int leaverChange = leaverIsP1 ? elo.change1 : elo.change2;
                int leaverAfter = leaverIsP1 ? elo.after1 : elo.after2;

                toStayer = matchEndMsg(stayer, leaver, "WIN",
                        voluntary ? "OPPONENT_LEFT" : "OPPONENT_DISCONNECTED",
                        stayerChange, stayerAfter, leaverChange, leaverAfter);
                stayerAlsoGetsSessionEnd = true;
                if (voluntary) {
                    toLeaver = matchEndMsg(leaver, stayer, "LOSE", "YOU_LEFT",
                            leaverChange, leaverAfter, stayerChange, stayerAfter);
                }
                closeSessionLocked();
                Log.info("Match#" + matchId, username + (voluntary ? " thoat tran" : " mat ket noi") + " -> xu thua");
            }
        }
        if (toLeaver != null) {
            sendTo(leaver, toLeaver);
            sendTo(leaver, new Message(Msg.SESSION_END).put("message", "Bạn đã rời trận."));
        }
        sendTo(stayer, toStayer);
        if (stayerAlsoGetsSessionEnd) {
            sendTo(stayer, new Message(Msg.SESSION_END)
                    .put("message", voluntary ? "Đối thủ đã rời trận." : "Đối thủ đã mất kết nối."));
        }
        server.onSessionClosed(this, p1.profile.username, p2.profile.username);
    }

    /** Lựa chọn Chơi lại / Thoát sau khi trận kết thúc. */
    public void onRematchChoice(String username, boolean again) {
        boolean startRematch = false;
        Slot me;
        synchronized (lock) {
            if (state != State.AWAITING_REMATCH) return;
            me = slotOf(username);
            if (me == null) return;
            if (!again) {
                closeSessionLocked();
            } else {
                me.rematch = true;
                if (p1.rematch && p2.rematch) {
                    state = State.CLOSED;
                    startRematch = true;
                }
            }
        }
        if (startRematch) {
            server.startRematch(p1.handler, p2.handler);
            return;
        }
        if (!again) {
            Message end = new Message(Msg.SESSION_END).put("message", "Phiên đấu kết thúc.");
            sendTo(p1, end);
            sendTo(p2, end);
            server.onSessionClosed(this, p1.profile.username, p2.profile.username);
        } else {
            sendTo(me, new Message(Msg.REMATCH_WAIT));
        }
    }

    // ================= nội bộ =================

    private record EloUpdate(int change1, int after1, int change2, int after2) {}

    /**
     * Tính Elo mới, cập nhật users + matches trong DB, cập nhật Elo trong registry.
     * Gọi khi đang giữ lock.
     */
    private EloUpdate applyEndOfMatch(String result, String reasonDb, double s1) {
        int r1 = p1.profile.elo, r2 = p2.profile.elo;
        int new1 = EloCalculator.newRating(r1, r2, s1);
        int new2 = EloCalculator.newRating(r2, r1, 1.0 - s1);
        char o1 = s1 == 1.0 ? 'W' : s1 == 0.0 ? 'L' : 'D';
        char o2 = s1 == 1.0 ? 'L' : s1 == 0.0 ? 'W' : 'D';
        try {
            server.userDao().applyMatchOutcome(p1.profile.id, new1, o1, p1.total);
            server.userDao().applyMatchOutcome(p2.profile.id, new2, o2, p2.total);
            server.matchDao().finishMatch(matchId, p1.total, p2.total, result, reasonDb,
                    new1 - r1, new2 - r2, new1, new2);
        } catch (Exception ex) {
            Log.error("Match#" + matchId, "Khong luu duoc ket qua tran", ex);
        }
        server.registry().updateElo(p1.profile.username, new1);
        server.registry().updateElo(p2.profile.username, new2);
        // đồng bộ hồ sơ trong handler để lần vào trận sau dùng Elo mới
        p1.handler.refreshProfileElo(new1);
        p2.handler.refreshProfileElo(new2);
        return new EloUpdate(new1 - r1, new1, new2 - r2, new2);
    }

    private Message matchEndMsg(Slot me, Slot opp, String myResult, String reason,
                                int myChange, int myNew, int oppChange, int oppNew) {
        return new Message(Msg.MATCH_END)
                .put("matchId", matchId)
                .put("result", myResult)
                .put("reason", reason)
                .put("myTotal", me.total)
                .put("oppTotal", opp.total)
                .put("myEloChange", myChange)
                .put("myNewElo", myNew)
                .put("oppEloChange", oppChange)
                .put("oppNewElo", oppNew);
    }

    /** Huỷ phiên do lỗi dữ liệu (không tính Elo). Gọi khi đang giữ lock. */
    private void abortSession(String message) {
        closeSessionLocked();
        Message end = new Message(Msg.SESSION_END).put("message", message);
        sendTo(p1, end);
        sendTo(p2, end);
        server.onSessionClosed(this, p1.profile.username, p2.profile.username);
    }

    private void closeSessionLocked() {
        state = State.CLOSED;
        if (pendingTask != null) pendingTask.cancel(false);
    }

    private Slot slotOf(String username) {
        if (p1.profile.username.equals(username)) return p1;
        if (p2.profile.username.equals(username)) return p2;
        return null;
    }

    private Slot other(Slot s) { return s == p1 ? p2 : p1; }

    private void sendTo(Slot s, Message m) {
        try {
            s.handler.send(m);
        } catch (Exception ex) {
            // Người nhận có thể vừa rớt mạng; sự kiện disconnect sẽ được reader thread xử lý.
            Log.warn("Match#" + matchId, "Khong gui duoc " + m.type() + " toi " + s.profile.username);
        }
    }

    private static String fmtKm(Double km) {
        return km == null ? "khong doan" : String.format("%.1f km", km);
    }
}
