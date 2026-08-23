package com.ltm.geoduel.client.ui;

import com.ltm.geoduel.client.ui.components.Icons;
import com.ltm.geoduel.client.ui.components.PillButton;
import com.ltm.geoduel.client.ui.components.RoundedPanel;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;

/**
 * Màn hình đăng nhập / tạo tài khoản: một thẻ trắng ở giữa nền giấy có
 * lưới kinh vĩ tuyến + đường bay mờ. Mọi thứ canh giữa theo một trục dọc.
 */
public class LoginPanel extends JPanel {
    public interface Actions {
        void doLogin(String username, String password);
        void doRegister(String username, String password, String displayName);
    }

    private final Actions actions;

    private final JTextField userField = field();
    private final JPasswordField passField = passField();
    private final JTextField nameField = field();
    private final JLabel nameLabel = fieldLabel("TÊN HIỂN THỊ");
    private final JLabel error = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel formTitle = new JLabel("Đăng nhập", SwingConstants.CENTER);
    private final PillButton submit = new PillButton("Đăng nhập", PillButton.Kind.PRIMARY);
    private final PillButton toggle = new PillButton("Tạo tài khoản mới", PillButton.Kind.GHOST);
    private boolean registerMode = false;

    public LoginPanel(Actions actions) {
        this.actions = actions;
        setLayout(new GridBagLayout());
        setBackground(Theme.BG);
        add(card(), new GridBagConstraints());
    }

    /** Nền trang trí: bản đồ thế giới chìm + vài cung "đường bay" mờ. */
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = Theme.prep((Graphics2D) g.create());
        int w = getWidth(), h = getHeight();
        WorldBackdrop.paint(this, g2, w, h, 190);
        g2.setColor(Theme.withAlpha(Theme.AMBER, 55));
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                1f, new float[]{7f, 8f}, 0f));
        g2.drawArc(-w / 4, h / 3, w, h, 20, 60);
        g2.drawArc(w / 3, -h / 3, w, h, 200, 55);
        g2.dispose();
    }

    private JPanel card() {
        RoundedPanel card = new RoundedPanel(new GridBagLayout(), Theme.SURFACE, null, 24, true);
        card.setBorder(new EmptyBorder(40, 54, 38, 54));
        card.setPreferredSize(new Dimension(460, 600));

        // logo trong huy hiệu tròn dịu
        JLabel logo = new JLabel(Icons.compass(56, Theme.AMBER, Theme.AMBER), SwingConstants.CENTER) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Theme.prep((Graphics2D) g.create());
                int d = 88;
                int cx = (getWidth() - d) / 2, cy = (getHeight() - d) / 2;
                g2.setColor(Theme.withAlpha(Theme.AMBER, 38));
                g2.fillOval(cx, cy, d, d);
                g2.setColor(Theme.withAlpha(Theme.AMBER, 70));
                g2.drawOval(cx, cy, d, d);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        logo.setPreferredSize(new Dimension(96, 96));
        JLabel title = new JLabel("GeoDuel", SwingConstants.CENTER);
        title.setFont(Theme.bold(38));
        title.setForeground(Theme.TEXT);
        JLabel tagline = new JLabel("Nhìn ảnh thật · Ghim lên bản đồ thế giới · Đấu 5 lượt",
                SwingConstants.CENTER);
        tagline.setFont(Theme.font(13));
        tagline.setForeground(Theme.TEXT_MUTED);

        formTitle.setFont(Theme.bold(19));
        formTitle.setForeground(Theme.TEXT);
        error.setFont(Theme.font(12));
        error.setForeground(Theme.RED);

        submit.addActionListener(e -> submit());
        toggle.addActionListener(e -> setRegisterMode(!registerMode));
        userField.addActionListener(e -> submit());
        passField.addActionListener(e -> submit());
        nameField.addActionListener(e -> submit());

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        int y = 0;
        c.gridy = y++; c.insets = insets(0, 0);   card.add(logo, c);
        c.gridy = y++; c.insets = insets(10, 0);  card.add(title, c);
        c.gridy = y++; c.insets = insets(4, 22);  card.add(tagline, c);
        c.gridy = y++; c.insets = insets(0, 14);  card.add(formTitle, c);
        c.gridy = y++; c.insets = insets(0, 4);   card.add(fieldLabel("TÊN ĐĂNG NHẬP"), c);
        c.gridy = y++; c.insets = insets(0, 12);  card.add(userField, c);
        c.gridy = y++; c.insets = insets(0, 4);   card.add(fieldLabel("MẬT KHẨU"), c);
        c.gridy = y++; c.insets = insets(0, 12);  card.add(passField, c);
        c.gridy = y++; c.insets = insets(0, 4);   card.add(nameLabel, c);
        c.gridy = y++; c.insets = insets(0, 12);  card.add(nameField, c);
        c.gridy = y++; c.insets = insets(0, 8);   card.add(error, c);
        c.gridy = y++; c.insets = insets(0, 10);  card.add(submit, c);
        c.gridy = y;   c.insets = insets(0, 0);   card.add(toggle, c);

        setRegisterMode(false);
        return card;
    }

    private static java.awt.Insets insets(int top, int bottom) {
        return new java.awt.Insets(top, 0, bottom, 0);
    }

    private static JTextField field() {
        JTextField f = new JTextField() {
            @Override protected void paintComponent(Graphics g) {
                paintFieldBackground(this, g);
                super.paintComponent(g);
            }
        };
        styleField(f);
        return f;
    }

    private static JPasswordField passField() {
        JPasswordField f = new JPasswordField() {
            @Override protected void paintComponent(Graphics g) {
                paintFieldBackground(this, g);
                super.paintComponent(g);
            }
        };
        styleField(f);
        return f;
    }

    /** Ô nhập bo tròn mềm, viền chỉ hiện rõ khi focus (màu hổ phách). */
    private static void paintFieldBackground(JTextField f, Graphics g) {
        Graphics2D g2 = Theme.prep((Graphics2D) g.create());
        int w = f.getWidth(), h = f.getHeight();
        g2.setColor(Theme.ELEVATED);
        g2.fillRoundRect(0, 0, w - 1, h - 1, 14, 14);
        g2.setColor(f.hasFocus() ? Theme.withAlpha(Theme.AMBER_DARK, 170)
                                 : Theme.withAlpha(Theme.INK, 26));
        g2.drawRoundRect(0, 0, w - 1, h - 1, 14, 14);
        g2.dispose();
    }

    private static void styleField(JTextField f) {
        f.setFont(Theme.font(14));
        f.setForeground(Theme.TEXT);
        f.setCaretColor(Theme.AMBER_DARK);
        f.setOpaque(false);
        f.setBorder(new EmptyBorder(10, 14, 10, 14));
    }

    private static JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.bold(11));
        l.setForeground(Theme.TEXT_MUTED);
        return l;
    }

    private void setRegisterMode(boolean reg) {
        registerMode = reg;
        formTitle.setText(reg ? "Tạo tài khoản" : "Đăng nhập");
        submit.setText(reg ? "Tạo tài khoản" : "Đăng nhập");
        toggle.setText(reg ? "Đã có tài khoản? Đăng nhập" : "Tạo tài khoản mới");
        nameLabel.setVisible(reg);
        nameField.setVisible(reg);
        error.setText(" ");
        revalidate();
        repaint();
    }

    private void submit() {
        String user = userField.getText().trim();
        String pass = new String(passField.getPassword());
        if (user.isEmpty() || pass.isEmpty()) {
            showError("Hãy nhập tên đăng nhập và mật khẩu.");
            return;
        }
        setBusy(true);
        if (registerMode) {
            actions.doRegister(user, pass, nameField.getText().trim());
        } else {
            actions.doLogin(user, pass);
        }
    }

    public void setBusy(boolean busy) {
        submit.setEnabled(!busy);
        toggle.setEnabled(!busy);
    }

    public void showError(String message) {
        setBusy(false);
        error.setForeground(Theme.RED);
        error.setText(message);
    }

    /** Sau khi đăng ký thành công: quay về chế độ đăng nhập, báo màu dịu. */
    public void showInfo(String message) {
        setBusy(false);
        setRegisterMode(false);
        error.setForeground(Theme.GREEN);
        error.setText(message);
    }

    public void resetForNewSession() {
        setBusy(false);
        passField.setText("");
        error.setText(" ");
    }
}
