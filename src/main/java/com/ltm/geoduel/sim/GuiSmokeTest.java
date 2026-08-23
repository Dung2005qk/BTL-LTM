package com.ltm.geoduel.sim;

import com.ltm.geoduel.client.ui.MainFrame;
import com.ltm.geoduel.client.ui.MapPanel;
import com.ltm.geoduel.server.GameServer;
import com.ltm.geoduel.server.ServerConfig;
import com.formdev.flatlaf.FlatLightLaf;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Kiểm thử khói giao diện: mở HAI cửa sổ client thật trong một JVM, tự động
 * đăng ký → đăng nhập → mời đấu → chấp nhận → đặt ghim → gửi dự đoán,
 * chụp ảnh màn hình từng bước ra thư mục chỉ định để kiểm tra bằng mắt.
 *
 * Chạy: java -cp target/geoduel.jar com.ltm.geoduel.sim.GuiSmokeTest config-test.properties <thư-mục-ảnh>
 */
public final class GuiSmokeTest {
    private static Robot robot;
    private static Path shotDir;
    private static int shotIndex = 1;

    public static void main(String[] args) throws Exception {
        String cfgPath = args.length > 0 ? args[0] : "config-test.properties";
        shotDir = Path.of(args.length > 1 ? args[1] : "target/gui-shots");
        Files.createDirectories(shotDir);

        ServerConfig config = new ServerConfig(cfgPath);
        Thread server = new Thread(() -> {
            try { new GameServer(config).serve(); } catch (Exception ex) {
                System.out.println("SERVER LOI: " + ex);
                System.exit(2);
            }
        }, "smoke-server");
        server.setDaemon(true);
        server.start();
        Thread.sleep(1000);

        com.ltm.geoduel.client.audio.SoundManager.setMuted(true); // test không phát tiếng
        FlatLightLaf.setup();
        robot = new Robot();

        AtomicReference<MainFrame> f1 = new AtomicReference<>();
        AtomicReference<MainFrame> f2 = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            MainFrame a = new MainFrame(config.host(), config.port(), Path.of(config.assetsDir()));
            a.setSize(1180, 760);
            a.setLocation(30, 20);
            a.setVisible(true);
            f1.set(a);
            MainFrame b = new MainFrame(config.host(), config.port(), Path.of(config.assetsDir()));
            b.setSize(1180, 760);
            b.setLocation(360, 220);
            b.setVisible(true);
            f2.set(b);
        });
        Thread.sleep(1200);
        shot(f1.get(), "01-login");

        // ---- đăng ký + đăng nhập bằng chính giao diện ----
        registerAndLogin(f1.get(), "gui_an", "123456", "An (GUI)");
        registerAndLogin(f2.get(), "gui_binh", "123456", "Bình (GUI)");
        Thread.sleep(1200);
        shot(f2.get(), "02-lobby");

        // ---- Bình chọn An trong danh sách online rồi bấm Mời thi đấu ----
        JList<?> list = find(f2.get(), JList.class, c -> true);
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < list.getModel().getSize(); i++) {
                Object row = list.getModel().getElementAt(i);
                if (row.toString().contains("gui_an")) {
                    list.setSelectedIndex(i);
                    return;
                }
            }
            throw new IllegalStateException("khong thay gui_an trong danh sach online");
        });
        clickButton(f2.get(), "Mời thi đấu");
        Thread.sleep(900);

        // ---- cửa sổ của An hiện hộp thoại lời mời → Chấp nhận ----
        Window inviteDialog = waitFor(() -> {
            for (Window w : Window.getWindows()) {
                if (w instanceof JDialog d && d.isVisible()
                        && findOrNull(d, AbstractButton.class, b -> "Chấp nhận".equals(b.getText())) != null) {
                    return d;
                }
            }
            return null;
        }, 8000, "hop thoai loi moi");
        shot(f1.get(), "03-invite-dialog");
        AbstractButton accept = find(inviteDialog, AbstractButton.class, b -> "Chấp nhận".equals(b.getText()));
        SwingUtilities.invokeAndWait(accept::doClick);

        // ---- vào trận: chờ ảnh manh mối tải xong rồi chụp ----
        Thread.sleep(5000);
        shot(f1.get(), "04-match-guessing");

        // ---- An click bản đồ đặt ghim rồi GỬI ----
        MapPanel map1 = find(f1.get(), MapPanel.class, c -> c.isShowing());
        SwingUtilities.invokeAndWait(() -> clickAt(map1, map1.getWidth() / 2, map1.getHeight() / 3));
        Thread.sleep(400);
        shot(f1.get(), "05-pin-placed");
        clickButton(f1.get(), "GỬI DỰ ĐOÁN");

        // ---- Bình cũng đoán ----
        MapPanel map2 = find(f2.get(), MapPanel.class, c -> c.isShowing());
        SwingUtilities.invokeAndWait(() -> clickAt(map2, map2.getWidth() / 2, 2 * map2.getHeight() / 3));
        Thread.sleep(300);
        clickButton(f2.get(), "GỬI DỰ ĐOÁN");

        // ---- kết quả lượt ----
        Thread.sleep(1200);
        shot(f1.get(), "06-round-result");

        // ---- chơi nhanh các lượt còn lại: chờ nhãn "LƯỢT n/5" xuất hiện rồi đoán ----
        for (int round = 2; round <= 5; round++) {
            final String label = "LƯỢT " + round + "/5";
            waitFor(() -> findOrNull(f1.get(), JLabel.class,
                    l -> label.equals(l.getText())), 30000, "nhan " + label);
            Thread.sleep(600); // chờ ảnh bắt đầu tải, bản đồ đã mở khoá
            SwingUtilities.invokeAndWait(() -> clickAt(map1, map1.getWidth() / 2, map1.getHeight() / 3));
            clickButton(f1.get(), "GỬI DỰ ĐOÁN");
            SwingUtilities.invokeAndWait(() -> clickAt(map2, map2.getWidth() / 2, 2 * map2.getHeight() / 3));
            clickButton(f2.get(), "GỬI DỰ ĐOÁN");
            Thread.sleep(500);
        }

        // ---- màn kết thúc trận ----
        Thread.sleep(3500);
        shot(f1.get(), "07-match-end");

        // ---- Thoát về sảnh (nút "Thoát" trên lớp phủ; khác nút "Thoát trận" ở HUD) ----
        AbstractButton exitBtn = find(f1.get(), AbstractButton.class,
                b -> "Thoát".equals(b.getText()) && b.isShowing());
        SwingUtilities.invokeAndWait(exitBtn::doClick);
        Thread.sleep(1500);
        shot(f1.get(), "08-back-to-lobby");

        System.out.println("GUI SMOKE: HOAN TAT — anh o " + shotDir.toAbsolutePath());
        System.exit(0);
    }

    // ================= tiện ích =================

    private static void registerAndLogin(MainFrame f, String user, String pass, String display) throws Exception {
        // chuyển sang chế độ đăng ký
        clickButton(f, "Tạo tài khoản mới");
        Thread.sleep(300);
        fillFields(f, user, pass, display);
        clickButton(f, "Tạo tài khoản");
        Thread.sleep(1500); // server trả REGISTER_RESULT → form quay về đăng nhập
        // nếu tài khoản đã tồn tại (chạy lại test), form vẫn ở chế độ đăng ký → tự quay về
        if (findOrNull(f, AbstractButton.class,
                b -> "Đã có tài khoản? Đăng nhập".equals(b.getText()) && b.isShowing()) != null) {
            clickButton(f, "Đã có tài khoản? Đăng nhập");
            Thread.sleep(300);
        }
        fillFields(f, user, pass, null);
        clickButton(f, "Đăng nhập");
        Thread.sleep(1500);
    }

    private static void fillFields(MainFrame f, String user, String pass, String display) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<JTextField> fields = findAll(f, JTextField.class, c -> c.isShowing());
            for (JTextField tf : fields) {
                if (tf instanceof JPasswordField) tf.setText(pass);
                else if (display != null && !fields.get(0).equals(tf)) tf.setText(display);
            }
            // field đầu tiên (không phải password) = tên đăng nhập
            for (JTextField tf : fields) {
                if (!(tf instanceof JPasswordField)) { tf.setText(user); break; }
            }
            if (display != null) {
                // field text thường thứ hai = tên hiển thị
                int seen = 0;
                for (JTextField tf : fields) {
                    if (!(tf instanceof JPasswordField) && ++seen == 2) tf.setText(display);
                }
            }
        });
    }

    private static void clickButton(Container root, String text) throws Exception {
        AbstractButton b = waitFor(() -> findOrNull(root, AbstractButton.class,
                x -> text.equals(x.getText()) && x.isShowing() && x.isEnabled()), 10000, "nut '" + text + "'");
        SwingUtilities.invokeAndWait(b::doClick);
    }

    private static void waitForButtonEnabled(Container root, String text, long timeoutMs) throws Exception {
        waitFor(() -> {
            AbstractButton b = findOrNull(root, AbstractButton.class,
                    x -> text.equals(x.getText()) && x.isShowing() && x.isEnabled());
            return b;
        }, timeoutMs, "nut '" + text + "' bat lai");
    }

    /** Mô phỏng click chuột thật lên một component (press + release cùng chỗ). */
    private static void clickAt(Component c, int x, int y) {
        long now = System.currentTimeMillis();
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, now, 0, x, y, 1, false, MouseEvent.BUTTON1));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, now, 0, x, y, 1, false, MouseEvent.BUTTON1));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, now, 0, x, y, 1, false, MouseEvent.BUTTON1));
    }

    private static <T> T waitFor(java.util.function.Supplier<T> probe, long timeoutMs, String what)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<T> ref = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> ref.set(probe.get()));
            if (ref.get() != null) return ref.get();
            Thread.sleep(200);
        }
        throw new AssertionError("Het thoi gian cho: " + what);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> T find(Container root, Class<T> type, Predicate<T> match) {
        T found = findOrNull(root, type, match);
        if (found == null) throw new AssertionError("Khong tim thay " + type.getSimpleName());
        return found;
    }

    private static <T extends Component> T findOrNull(Container root, Class<T> type, Predicate<T> match) {
        List<T> all = findAll(root, type, match);
        return all.isEmpty() ? null : all.get(0);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> List<T> findAll(Container root, Class<T> type, Predicate<T> match) {
        List<T> out = new ArrayList<>();
        collect(root, type, match, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> void collect(Container root, Class<T> type,
                                                      Predicate<T> match, List<T> out) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && match.test((T) c)) out.add((T) c);
            if (c instanceof Container child) collect(child, type, match, out);
        }
    }

    private static void shot(Window w, String name) throws Exception {
        SwingUtilities.invokeAndWait(w::toFront); // tránh bị cửa sổ kia che khi chụp
        Thread.sleep(450);
        Rectangle r = w.getBounds();
        BufferedImage img = robot.createScreenCapture(r);
        ImageIO.write(img, "png", shotDir.resolve(name + ".png").toFile());
        System.out.println("  da chup: " + name);
    }
}
