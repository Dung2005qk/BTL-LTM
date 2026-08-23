package com.ltm.geoduel.server;

import com.ltm.geoduel.common.Log;
import com.ltm.geoduel.common.Message;
import com.ltm.geoduel.common.Msg;
import com.ltm.geoduel.server.dao.LocationDao;
import com.ltm.geoduel.server.dao.MatchDao;
import com.ltm.geoduel.server.dao.UserDao;
import com.ltm.geoduel.server.model.LocationData;
import com.ltm.geoduel.server.model.UserProfile;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Thành phần trung tâm của server: accept kết nối, giữ các dịch vụ dùng chung
 * (registry, invite, DAO, scheduler) và điều phối việc tạo trận.
 */
public class GameServer {
    private final ServerConfig config;
    private final UserDao userDao;
    private final LocationDao locationDao;
    private final MatchDao matchDao;
    private final SessionRegistry registry = new SessionRegistry();
    private final InviteManager inviteManager = new InviteManager();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Map<Integer, MatchEngine> matches = new ConcurrentHashMap<>();

    public GameServer(ServerConfig config) {
        this.config = config;
        Db db = new Db(config.dbUrl(), config.dbUser(), config.dbPassword());
        this.userDao = new UserDao(db);
        this.locationDao = new LocationDao(db);
        this.matchDao = new MatchDao(db);
    }

    public ServerConfig config() { return config; }
    public UserDao userDao() { return userDao; }
    public LocationDao locationDao() { return locationDao; }
    public MatchDao matchDao() { return matchDao; }
    public SessionRegistry registry() { return registry; }
    public InviteManager inviteManager() { return inviteManager; }
    public ScheduledExecutorService scheduler() { return scheduler; }

    public void serve() throws Exception {
        int locations = locationDao.countActive();
        if (locations < config.roundsPerMatch()) {
            throw new IllegalStateException("Chi co " + locations + " dia diem trong DB, can it nhat " +
                    config.roundsPerMatch() + ". Hay chay seed truoc (scripts\\seed-assets.bat).");
        }
        Log.info("Server", "Du lieu: " + locations + " dia diem hoat dong");
        try (ServerSocket serverSocket = new ServerSocket(config.port())) {
            Log.info("Server", "GeoDuel server lang nghe tai cong " + config.port());
            while (true) {
                Socket socket = serverSocket.accept();
                Thread t = new Thread(new ClientHandler(socket, this));
                t.setDaemon(true);
                t.start();
            }
        }
    }

    // ================= lời mời =================

    public void handleInvite(ClientHandler inviter, String target) {
        UserProfile me = inviter.profile();
        if (me == null) return;
        if (target.equals(me.username)) {
            inviter.send(new Message(Msg.INVITE_RESULT).put("ok", false)
                    .put("message", "Không thể tự mời chính mình."));
            return;
        }
        // Kiểm tra trạng thái CẢ HAI trước khi chuyển lời mời (theo đặc tả).
        SessionRegistry.Status myStatus = registry.statusOf(me.username);
        SessionRegistry.Status targetStatus = registry.statusOf(target);
        if (myStatus != SessionRegistry.Status.FREE) {
            inviter.send(new Message(Msg.INVITE_RESULT).put("ok", false)
                    .put("message", "Bạn đang trong trận, không thể mời."));
            return;
        }
        if (targetStatus == null) {
            inviter.send(new Message(Msg.INVITE_RESULT).put("ok", false)
                    .put("message", "Người chơi này không còn trực tuyến."));
            return;
        }
        if (targetStatus != SessionRegistry.Status.FREE) {
            inviter.send(new Message(Msg.INVITE_RESULT).put("ok", false)
                    .put("message", "Người chơi này đang bận."));
            return;
        }
        InviteManager.Invite inv = inviteManager.create(me.username, target);
        if (inv == null) {
            inviter.send(new Message(Msg.INVITE_RESULT).put("ok", false)
                    .put("message", "Bạn đã gửi lời mời cho người này rồi, hãy chờ trả lời."));
            return;
        }
        ClientHandler targetHandler = registry.handlerOf(target);
        if (targetHandler == null) {
            inviter.send(new Message(Msg.INVITE_RESULT).put("ok", false)
                    .put("message", "Người chơi này không còn trực tuyến."));
            return;
        }
        targetHandler.send(new Message(Msg.INVITE_INCOMING)
                .put("inviteId", inv.id)
                .put("from", me.username)
                .put("fromDisplayName", me.displayName)
                .put("fromElo", me.elo));
        Log.info("Server", me.username + " moi " + target + " thi dau (invite#" + inv.id + ")");
    }

    public void handleInviteResponse(ClientHandler responder, int inviteId, boolean accepted) {
        UserProfile me = responder.profile();
        if (me == null) return;
        InviteManager.Invite inv = inviteManager.take(inviteId, me.username);
        if (inv == null) {
            responder.send(new Message(Msg.ERROR).put("message", "Lời mời không còn hiệu lực."));
            return;
        }
        ClientHandler inviter = registry.handlerOf(inv.from);
        if (!accepted) {
            if (inviter != null) {
                inviter.send(new Message(Msg.INVITE_RESULT).put("ok", false)
                        .put("message", me.displayName + " đã từ chối lời mời."));
            }
            return;
        }
        // Chấp nhận: kiểm tra lại trạng thái cả hai lần cuối rồi tạo trận.
        if (inviter == null || registry.statusOf(inv.from) != SessionRegistry.Status.FREE) {
            responder.send(new Message(Msg.ERROR).put("message", "Người mời không còn sẵn sàng."));
            return;
        }
        if (registry.statusOf(me.username) != SessionRegistry.Status.FREE) {
            responder.send(new Message(Msg.ERROR).put("message", "Bạn đang bận, không thể vào trận."));
            return;
        }
        startMatch(inviter, responder);
    }

    // ================= tạo trận =================

    /** Tạo và khởi động một trận giữa hai handler (cả hai phải FREE và đã đăng nhập). */
    public void startMatch(ClientHandler h1, ClientHandler h2) {
        startMatch(h1, h2, false);
    }

    private synchronized void startMatch(ClientHandler h1, ClientHandler h2, boolean rematch) {
        UserProfile p1 = h1.profile();
        UserProfile p2 = h2.profile();
        if (p1 == null || p2 == null) return;
        // Chặn race: hai lời mời được chấp nhận gần như đồng thời với cùng một người chơi.
        if (!rematch && (h1.currentMatch() != null || h2.currentMatch() != null)) {
            Message err = new Message(Msg.ERROR).put("message", "Một trong hai người vừa vào trận khác.");
            h1.send(err);
            h2.send(err);
            return;
        }
        try {
            List<LocationData> locs = pickPlayableLocations(config.roundsPerMatch());
            int matchId = matchDao.createMatch(p1.id, p2.id);
            MatchEngine engine = new MatchEngine(this, matchId, h1, p1, h2, p2, locs);
            matches.put(matchId, engine);
            h1.setCurrentMatch(engine);
            h2.setCurrentMatch(engine);
            registry.setStatus(p1.username, SessionRegistry.Status.BUSY);
            registry.setStatus(p2.username, SessionRegistry.Status.BUSY);
            inviteManager.removeInvolving(p1.username);
            inviteManager.removeInvolving(p2.username);
            registry.broadcastOnlineList();
            engine.start();
        } catch (Exception ex) {
            Log.error("Server", "Khong tao duoc tran dau", ex);
            Message err = new Message(Msg.ERROR).put("message", "Không thể tạo trận đấu (lỗi máy chủ).");
            h1.send(err);
            h2.send(err);
            registry.setStatus(p1.username, SessionRegistry.Status.FREE);
            registry.setStatus(p2.username, SessionRegistry.Status.FREE);
        }
    }

    /** Cả hai chọn Chơi lại: đóng trận cũ (đã CLOSED) và mở trận mới, giữ nguyên trạng thái BUSY. */
    public void startRematch(ClientHandler h1, ClientHandler h2) {
        MatchEngine old = h1.currentMatch();
        if (old != null) matches.remove(old.matchId());
        Log.info("Server", "Hai ben dong y choi lai");
        startMatch(h1, h2, true);
    }

    /**
     * Chọn địa điểm cho trận: lấy dư rồi loại địa điểm thiếu ảnh trên đĩa
     * để trận không bao giờ gặp lượt trống.
     */
    private List<LocationData> pickPlayableLocations(int needed) throws Exception {
        List<LocationData> candidates = locationDao.pickRandom(needed * 2);
        List<LocationData> good = new ArrayList<>();
        for (LocationData loc : candidates) {
            if (good.size() >= needed) break;
            int readable = 0;
            for (LocationData.ClueImage img : loc.images) {
                if (Files.isReadable(Path.of(config.assetsDir()).resolve(img.path()))) readable++;
            }
            if (readable >= 3) good.add(loc); // đặc tả: mỗi địa điểm 3-5 ảnh
            else Log.warn("Server", "Dia diem " + loc.slug + " chi co " + readable + " anh doc duoc, bo qua");
        }
        if (good.size() < needed) {
            throw new IllegalStateException("Khong du dia diem hop le (can " + needed + ", co " + good.size() + ")");
        }
        return good;
    }

    /** Engine gọi khi phiên giữa hai người chơi kết thúc hẳn: trả cả hai về FREE. */
    public void onSessionClosed(MatchEngine engine, String user1, String user2) {
        matches.remove(engine.matchId());
        clearMatchRef(user1, engine);
        clearMatchRef(user2, engine);
        registry.setStatus(user1, SessionRegistry.Status.FREE);
        registry.setStatus(user2, SessionRegistry.Status.FREE);
        registry.broadcastOnlineList();
    }

    private void clearMatchRef(String username, MatchEngine engine) {
        ClientHandler h = registry.handlerOf(username);
        if (h != null && h.currentMatch() == engine) h.setCurrentMatch(null);
    }
}
