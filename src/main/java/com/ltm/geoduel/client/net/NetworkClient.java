package com.ltm.geoduel.client.net;

import com.ltm.geoduel.common.Log;
import com.ltm.geoduel.common.Message;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kết nối TCP tới server: một thread đọc riêng, mọi thông điệp
 * được đẩy về listener TRÊN EDT (luật: thread mạng không đụng Swing).
 */
public class NetworkClient {
    public interface Listener {
        void onMessage(Message msg);
        void onDisconnected(String reason);
    }

    private final Listener listener;
    private Socket socket;
    private BufferedWriter out;
    private final Object writeLock = new Object();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean disconnectNotified = new AtomicBoolean(false);

    public NetworkClient(Listener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return connected.get();
    }

    /** Kết nối và khởi động thread đọc. Ném IOException nếu không kết nối được. */
    public synchronized void connect(String host, int port) throws IOException {
        if (connected.get()) return;
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        connected.set(true);
        disconnectNotified.set(false);

        Thread reader = new Thread(() -> {
            String reason = "Mất kết nối tới máy chủ.";
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    Message msg = Message.parse(line);
                    if (msg != null) {
                        SwingUtilities.invokeLater(() -> listener.onMessage(msg));
                    }
                }
                reason = "Máy chủ đã đóng kết nối.";
            } catch (IOException ex) {
                if (connected.get()) {
                    Log.warn("Net", "Loi doc: " + ex.getMessage());
                } else {
                    reason = "Đã ngắt kết nối."; // close() chủ động
                }
            } finally {
                boolean wasConnected = connected.getAndSet(false);
                closeQuietly();
                if (wasConnected && disconnectNotified.compareAndSet(false, true)) {
                    String r = reason;
                    SwingUtilities.invokeLater(() -> listener.onDisconnected(r));
                }
            }
        }, "net-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** Gửi một thông điệp; im lặng nếu đã mất kết nối (reader sẽ báo). */
    public void send(Message msg) {
        if (!connected.get()) return;
        try {
            synchronized (writeLock) {
                out.write(msg.toJsonLine());
                out.write('\n');
                out.flush();
            }
        } catch (IOException ex) {
            connected.set(false);
            closeQuietly();
        }
    }

    /** Ngắt kết nối chủ động (không phát sự kiện onDisconnected). */
    public void close() {
        connected.set(false);
        disconnectNotified.set(true);
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
    }
}
