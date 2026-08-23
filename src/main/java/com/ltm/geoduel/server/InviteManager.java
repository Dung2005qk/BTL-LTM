package com.ltm.geoduel.server;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Quản lý lời mời thi đấu đang chờ trả lời. */
public class InviteManager {
    public static class Invite {
        public final int id;
        public final String from;
        public final String to;
        public final long createdAt = System.currentTimeMillis();
        Invite(int id, String from, String to) { this.id = id; this.from = from; this.to = to; }
    }

    private static final long EXPIRE_MS = 60_000;

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, Invite> pending = new HashMap<>();

    /** @return lời mời mới, hoặc null nếu đã có lời mời đang chờ giữa đúng cặp này. */
    public synchronized Invite create(String from, String to) {
        purgeExpired();
        for (Invite inv : pending.values()) {
            if (inv.from.equals(from) && inv.to.equals(to)) return null;
        }
        Invite inv = new Invite(nextId.getAndIncrement(), from, to);
        pending.put(inv.id, inv);
        return inv;
    }

    /**
     * Lấy và gỡ lời mời khi người được mời trả lời.
     * @return null nếu lời mời không tồn tại/hết hạn hoặc {@code responder} không phải người được mời.
     */
    public synchronized Invite take(int inviteId, String responder) {
        purgeExpired();
        Invite inv = pending.get(inviteId);
        if (inv == null || !inv.to.equals(responder)) return null;
        pending.remove(inviteId);
        return inv;
    }

    /** Huỷ mọi lời mời liên quan tới một người (khi họ offline hoặc vào trận). */
    public synchronized void removeInvolving(String username) {
        pending.values().removeIf(inv -> inv.from.equals(username) || inv.to.equals(username));
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Invite> it = pending.values().iterator();
        while (it.hasNext()) {
            if (now - it.next().createdAt > EXPIRE_MS) it.remove();
        }
    }
}
