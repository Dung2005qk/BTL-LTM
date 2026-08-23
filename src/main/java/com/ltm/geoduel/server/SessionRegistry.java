package com.ltm.geoduel.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ltm.geoduel.common.Log;
import com.ltm.geoduel.common.Message;
import com.ltm.geoduel.common.Msg;
import com.ltm.geoduel.server.model.UserProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Danh bạ người chơi đang online + trạng thái rảnh/bận.
 * Mọi thao tác đồng bộ trên chính registry; gửi tin ra ngoài khối synchronized khi có thể.
 */
public class SessionRegistry {
    public enum Status { FREE, BUSY }

    private static class Entry {
        final ClientHandler handler;
        final UserProfile profile;
        Status status = Status.FREE;
        Entry(ClientHandler h, UserProfile p) { handler = h; profile = p; }
    }

    private final Map<String, Entry> online = new LinkedHashMap<>();

    /** @return false nếu tài khoản đã đăng nhập ở kết nối khác. */
    public synchronized boolean login(UserProfile profile, ClientHandler handler) {
        if (online.containsKey(profile.username)) return false;
        online.put(profile.username, new Entry(handler, profile));
        return true;
    }

    public synchronized void logout(String username) {
        if (username != null) online.remove(username);
    }

    public synchronized boolean isOnline(String username) {
        return online.containsKey(username);
    }

    public synchronized ClientHandler handlerOf(String username) {
        Entry e = online.get(username);
        return e == null ? null : e.handler;
    }

    public synchronized Status statusOf(String username) {
        Entry e = online.get(username);
        return e == null ? null : e.status;
    }

    public synchronized void setStatus(String username, Status status) {
        Entry e = online.get(username);
        if (e != null) e.status = status;
    }

    /** Cập nhật Elo hiển thị trong danh sách online sau khi trận kết thúc. */
    public synchronized void updateElo(String username, int newElo) {
        Entry e = online.get(username);
        if (e != null) e.profile.elo = newElo;
    }

    public synchronized JsonArray snapshotOnline() {
        JsonArray arr = new JsonArray();
        for (Entry e : online.values()) {
            JsonObject p = new JsonObject();
            p.addProperty("username", e.profile.username);
            p.addProperty("displayName", e.profile.displayName);
            p.addProperty("elo", e.profile.elo);
            p.addProperty("status", e.status.name());
            arr.add(p);
        }
        return arr;
    }

    private synchronized List<ClientHandler> allHandlers() {
        List<ClientHandler> list = new ArrayList<>(online.size());
        for (Entry e : online.values()) list.add(e.handler);
        return list;
    }

    /** Đẩy danh sách online mới nhất cho tất cả client (gọi mỗi khi có thay đổi). */
    public void broadcastOnlineList() {
        Message msg = new Message(Msg.ONLINE_LIST).put("players", snapshotOnline());
        for (ClientHandler h : allHandlers()) {
            try {
                h.send(msg);
            } catch (Exception ex) {
                Log.warn("Registry", "Khong gui duoc ONLINE_LIST toi 1 client: " + ex.getMessage());
            }
        }
    }
}
