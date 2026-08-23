package com.ltm.geoduel.sim;

import com.ltm.geoduel.common.Message;
import com.ltm.geoduel.common.Msg;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Client không giao diện dùng cho kiểm thử tích hợp: nói đúng giao thức JSON-từng-dòng,
 * có hàng đợi thông điệp và tiện ích chờ-thông-điệp-loại-X (đệm các loại khác).
 */
public class BotClient implements AutoCloseable {
    public final String name;
    private final Socket socket;
    private final BufferedWriter out;
    private final BlockingQueue<Message> inbox = new LinkedBlockingQueue<>();
    private final List<Message> buffered = new ArrayList<>();
    private volatile boolean closed = false;

    public BotClient(String name, String host, int port) throws IOException {
        this.name = name;
        this.socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    Message m = Message.parse(line);
                    if (m != null && !Msg.PONG.equals(m.type())) inbox.add(m);
                }
            } catch (IOException ignored) {
            }
        }, "bot-" + name);
        reader.setDaemon(true);
        reader.start();
    }

    public void send(Message m) throws IOException {
        synchronized (out) {
            out.write(m.toJsonLine());
            out.write('\n');
            out.flush();
        }
    }

    /**
     * Chờ thông điệp loại {@code type} trong tối đa {@code timeoutMs};
     * các thông điệp loại khác đến trước sẽ được đệm lại cho lần chờ sau.
     */
    public Message waitFor(String type, long timeoutMs) throws InterruptedException {
        for (int i = 0; i < buffered.size(); i++) {
            if (buffered.get(i).type().equals(type)) return buffered.remove(i);
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                throw new AssertionError(name + ": het " + timeoutMs + " ms cho " + type
                        + " (dang dem: " + buffered.stream().map(Message::type).toList() + ")");
            }
            Message m = inbox.poll(remain, TimeUnit.MILLISECONDS);
            if (m == null) continue;
            if (m.type().equals(type)) return m;
            buffered.add(m);
            if (buffered.size() > 200) buffered.remove(0); // tránh phình vô hạn vì ONLINE_LIST
        }
    }

    /** Bỏ mọi thông điệp đã đệm (thường là ONLINE_LIST cũ) trước một pha kiểm thử mới. */
    public void drain() {
        buffered.clear();
        inbox.clear();
    }

    // ================= tiện ích nghiệp vụ =================

    public void registerAndLogin(String password, String displayName) throws Exception {
        send(new Message(Msg.REGISTER).put("username", name).put("password", password)
                .put("displayName", displayName));
        Message reg = waitFor(Msg.REGISTER_RESULT, 5000);
        boolean ok = reg.getBool("ok", false);
        String msgText = reg.getString("message", "");
        if (!ok && !msgText.contains("tồn tại")) {
            throw new AssertionError(name + ": dang ky that bai: " + msgText);
        }
        send(new Message(Msg.LOGIN).put("username", name).put("password", password));
        Message login = waitFor(Msg.LOGIN_RESULT, 5000);
        if (!login.getBool("ok", false)) {
            throw new AssertionError(name + ": dang nhap that bai: " + login.getString("message", ""));
        }
    }

    /** Đóng đột ngột — mô phỏng mất kết nối. */
    public void closeAbruptly() throws IOException {
        closed = true;
        socket.close();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { socket.close(); } catch (IOException ignored) {}
    }
}
