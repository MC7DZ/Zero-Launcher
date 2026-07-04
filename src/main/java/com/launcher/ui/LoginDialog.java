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

public class LoginDialog extends JDialog {

    public static void show(Frame owner, Consumer<Account> onSuccess) {
        LoginDialog dialog = new LoginDialog(owner, onSuccess);
        dialog.setVisible(true);
    }

    private LoginDialog(Frame owner, Consumer<Account> onSuccess) {
        super(owner, "Add Account", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(true);
        setSize(460, 330);
        setLocationRelativeTo(owner);

        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        Color bg = Main.hexToColor(settings.bgColor, new Color(10, 10, 15));
        Color panelBg = Main.hexToColor(settings.panelBgColor, new Color(19, 19, 26));
        Color textColor = Main.hexToColor(settings.textColor, new Color(226, 226, 234));
        Color accent = Main.hexToColor(settings.accentColor, new Color(16, 185, 129));

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BorderLayout());
        mainPanel.setBackground(panelBg);

        // ── Header ────────────────────────────────────────────────────────────
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        headerPanel.setBackground(panelBg);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(40, 40, 50)),
                new EmptyBorder(20, 20, 16, 20)
        ));

        JLabel heading = new JLabel("Add Account");
        heading.setFont(new Font("SansSerif", Font.BOLD, 18));
        heading.setForeground(Color.WHITE);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel sub = new JLabel("<html>Offline accounts work for singleplayer and offline-mode servers.</html>");
        sub.setFont(new Font("SansSerif", Font.PLAIN, 12));
        sub.setForeground(new Color(150, 150, 160));
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);

        headerPanel.add(heading);
        headerPanel.add(Box.createVerticalStrut(4));
        headerPanel.add(sub);
        mainPanel.add(headerPanel, BorderLayout.NORTH);

        // ── Form ──────────────────────────────────────────────────────────────
        JPanel formPanel = new JPanel();
        formPanel.setLayout(new GridBagLayout());
        formPanel.setBackground(panelBg);
        formPanel.setBorder(new EmptyBorder(20, 20, 20, 20));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(0, 0, 6, 0);

        JLabel userLabel = new JLabel("Username");
        userLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        userLabel.setForeground(textColor);
        formPanel.add(userLabel, gbc);

        gbc.gridy++;
        JTextField usernameField = new JTextField();
        usernameField.putClientProperty("JTextField.placeholderText", "Enter a username...");
        usernameField.setPreferredSize(new Dimension(340, 30));
        formPanel.add(usernameField, gbc);

        gbc.gridy++;
        JLabel note = new JLabel("<html><body style='width: 320px;'>⚠  Microsoft authentication is not supported. Use the in-game account switcher if you need it.</body></html>");
        note.setFont(new Font("SansSerif", Font.PLAIN, 11));
        note.setForeground(new Color(150, 150, 160));
        formPanel.add(note, gbc);

        gbc.gridy++;
        JLabel errorLabel = new JLabel(" ");
        errorLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        errorLabel.setForeground(Color.RED);
        formPanel.add(errorLabel, gbc);

        gbc.gridy++;
        gbc.insets = new Insets(10, 0, 0, 0);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnRow.setBackground(panelBg);

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.setPreferredSize(new Dimension(90, 30));
        cancelBtn.addActionListener(e -> dispose());
        btnRow.add(cancelBtn);

        JButton addBtn = new JButton("Add Offline Account");
        addBtn.setPreferredSize(new Dimension(180, 30));
        addBtn.setBackground(accent);
        addBtn.setForeground(Color.WHITE);
        btnRow.add(addBtn);

        formPanel.add(btnRow, gbc);
        mainPanel.add(formPanel, BorderLayout.CENTER);

        addBtn.addActionListener(e -> {
            String u = usernameField.getText().trim();
            if (u.isEmpty()) {
                errorLabel.setText("Username cannot be empty.");
                return;
            }
            try {
                Account acc = new OfflineAuthService().login(u);
                onSuccess.accept(acc);
                dispose();
            } catch (Exception ex) {
                errorLabel.setText(ex.getMessage());
            }
        });

        usernameField.addActionListener(e -> addBtn.doClick());

        setContentPane(mainPanel);
    }
}
