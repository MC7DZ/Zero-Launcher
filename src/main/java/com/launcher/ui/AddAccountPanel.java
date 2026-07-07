package com.launcher.ui;

import com.launcher.auth.OfflineAuthService;
import com.launcher.model.Account;
import com.launcher.model.LauncherSettings;
import com.launcher.manager.SettingsManager;
import com.launcher.Main;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

public class AddAccountPanel extends JPanel {

    private Consumer<Account> onSuccess;
    private Runnable onCancel;

    // Store references to components that need dynamic color updates
    private JPanel headerPanel;
    private JLabel heading;
    private JLabel sub;
    private JPanel formPanel;
    private JLabel userLabel;
    private JTextField usernameField;
    private JLabel note;
    private JLabel errorLabel;
    private JPanel btnRow;
    private JButton cancelBtn;
    private JButton addBtn;

    public AddAccountPanel(Consumer<Account> onSuccess, Runnable onCancel) {
        this.onSuccess = onSuccess;
        this.onCancel = onCancel;
        initUI();
        reapplyTheme(); // Apply theme initially
    }

    private void initUI() {
        setLayout(new BorderLayout());

        // ── Header ────────────────────────────────────────────────────────────
        headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(40, 40, 50)), // Placeholder, will be dynamic
                new EmptyBorder(20, 20, 16, 20)
        ));

        heading = new JLabel("Add Account");
        heading.setFont(new Font("SansSerif", Font.BOLD, 18));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);

        sub = new JLabel("<html>Offline accounts work for singleplayer and offline-mode servers.</html>");
        sub.setFont(new Font("SansSerif", Font.PLAIN, 12));
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);

        headerPanel.add(heading);
        headerPanel.add(Box.createVerticalStrut(4));
        headerPanel.add(sub);
        add(headerPanel, BorderLayout.NORTH);

        // ── Form ──────────────────────────────────────────────────────────────
        formPanel = new JPanel();
        formPanel.setLayout(new GridBagLayout());
        formPanel.setBorder(new EmptyBorder(20, 20, 20, 20));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(0, 0, 6, 0);

        userLabel = new JLabel("Username");
        userLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        formPanel.add(userLabel, gbc);

        gbc.gridy++;
        usernameField = new JTextField();
        usernameField.putClientProperty("JTextField.placeholderText", "Enter a username...");
        usernameField.setPreferredSize(new Dimension(340, 30));
        formPanel.add(usernameField, gbc);

        gbc.gridy++;
        note = new JLabel("<html><body style='width: 320px;'>⚠  Microsoft authentication is not supported. Use the in-game account switcher if you need it.</body></html>");
        note.setFont(new Font("SansSerif", Font.PLAIN, 11));
        formPanel.add(note, gbc);

        gbc.gridy++;
        errorLabel = new JLabel(" ");
        errorLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        formPanel.add(errorLabel, gbc);

        gbc.gridy++;
        gbc.insets = new Insets(10, 0, 0, 0);
        btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));

        cancelBtn = new JButton("Cancel");
        cancelBtn.setPreferredSize(new Dimension(90, 30));
        cancelBtn.addActionListener(e -> {
            if (onCancel != null) {
                onCancel.run();
            }
        });
        btnRow.add(cancelBtn);

        addBtn = new JButton("Add Offline Account");
        addBtn.setPreferredSize(new Dimension(180, 30));
        addBtn.setForeground(Color.WHITE); // Keep white for contrast on accent
        btnRow.add(addBtn);

        formPanel.add(btnRow, gbc);
        add(formPanel, BorderLayout.CENTER);

        addBtn.addActionListener(e -> {
            String u = usernameField.getText().trim();
            if (u.isEmpty()) {
                errorLabel.setText("Username cannot be empty.");
                return;
            }
            try {
                Account acc = new OfflineAuthService().login(u);
                if (onSuccess != null) {
                    onSuccess.accept(acc);
                }
            } catch (Exception ex) {
                errorLabel.setText(ex.getMessage());
            }
        });

        usernameField.addActionListener(e -> addBtn.doClick());
    }

    public void reapplyTheme() {
        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        Color bg = Main.hexToColor(settings.bgColor, new Color(10, 10, 15));
        Color panelBg = Main.hexToColor(settings.panelBgColor, new Color(19, 19, 26));
        Color textColor = Main.hexToColor(settings.textColor, new Color(226, 226, 234));
        Color accent = Main.hexToColor(settings.accentColor, new Color(16, 185, 129));
        Color mutedTextColor = Main.hexToColor("#9696a0", new Color(150, 150, 160));
        Color errorColor = Main.hexToColor("#ef4444", Color.RED);
        Color borderColor = Main.hexToColor("#282832", new Color(40, 40, 50));

        setBackground(panelBg);

        // Header
        headerPanel.setBackground(panelBg);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor),
                new EmptyBorder(20, 20, 16, 20)
        ));
        heading.setForeground(textColor);
        sub.setForeground(mutedTextColor);

        // Form
        formPanel.setBackground(panelBg);
        userLabel.setForeground(textColor);
        usernameField.setBackground(bg);
        usernameField.setForeground(textColor);
        usernameField.setCaretColor(textColor);
        note.setForeground(mutedTextColor);
        errorLabel.setForeground(errorColor);

        // Buttons
        btnRow.setBackground(panelBg);
        cancelBtn.setForeground(textColor);
        addBtn.setBackground(accent);
        addBtn.setForeground(Color.WHITE); // Keep white for contrast
    }
}