package com.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.launcher.manager.AccountManager;
import com.launcher.manager.InstanceManager;
import com.launcher.minecraft.*;
import com.launcher.model.Account;
import com.launcher.model.Instance;
import com.launcher.model.ModEntry;
import com.launcher.model.ModLoaderType;
import com.launcher.ui.CreateInstanceDialog;
import com.launcher.ui.EditInstanceDialog;
import com.launcher.ui.LoginDialog;
import com.launcher.ui.NotificationCenter;
import com.launcher.ui.WrapLayout;
import com.launcher.util.JsonUtil;
import com.launcher.manager.LauncherPaths;
import com.formdev.flatlaf.FlatDarkLaf;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Main extends JFrame {

    private final AccountManager accountManager = new AccountManager();
    private final InstanceManager instanceManager = new InstanceManager();

    private NotificationCenter notifications;

    private final ConcurrentLinkedQueue<String> logQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean logFlushScheduled = false;
    private volatile boolean isMinimized = false;
    private long lastLogFlushWhenMinimized = 0;

    private JComboBox<Account> accountBox;
    private JList<Instance> instanceList;
    private DefaultListModel<Instance> instanceListModel;
    private JTextArea logArea;
    private JButton playButton;
    private JButton killButton;
    private Process activeProcess;

    // ─── Status bar ───────────────────────────────────────────────────────────
    private JLabel statusLabel;

    // ─── Dawn client ──────────────────────────────────────────────────────────
    private JLabel dawnStatusLabel;
    private JButton installDawnButton;
    private JButton deleteDawnButton;

    private static final String DAWN_JAR_NAME = "dawn-standalone.jar";
    private static final String DAWN_DOWNLOAD_URL = "https://cdn.dawn.gg/files/standalone/libraries/dawn-standalone.jar";

    // ─── Mods tab ─────────────────────────────────────────────────────────────
    private JLabel modsHeaderLabel;
    private JLabel modsCountLabel;
    private JList<ModEntry> modsList;
    private DefaultListModel<ModEntry> modsListModel;
    private JTextField modsSearchField;
    private JButton checkUpdatesBtn;
    private JButton updateAllBtn;
    private JButton updateSelectedBtn;
    private JButton refreshModsBtn;
    private JButton deleteModBtn;
    private JButton dedupeModsBtn;
    private JButton installDependenciesBtn;
    private List<ModEntry> currentModEntries = new ArrayList<>();

    private final Map<String, ImageIcon> modIconCache = new ConcurrentHashMap<>();
    private ImageIcon defaultModIcon;

    // ─── Discover tab (Modrinth browser) ───────────────────────────────────────
    private JComboBox<Instance> discoverInstanceBox;
    private JToggleButton discoverModsToggle;
    private JToggleButton discoverPacksToggle;
    private JTextField discoverSearchField;
    private JButton discoverSearchBtn;
    private JPanel discoverResultsPane;
    private JLabel discoverStatusLabel;
    private final Map<String, ImageIcon> discoverIconCache = new ConcurrentHashMap<>();
    private int discoverOffset = 0;
    private int discoverTotalHits = 0;
    private JLabel discoverPageLabel;
    private JButton discoverPrevPageBtn;
    private JButton discoverNextPageBtn;
    private JButton discoverRefreshBtn;
    private final ModUpdateService discoverModService = new ModUpdateService();

    private record VersionOption(String label, String url, String fileName) {
        @Override public String toString() { return label; }
    }

    // ─── Server section (Discord RPC settings) ─────────────────────────────────
    private JLabel serverInstanceLabel;
    private JLabel serverIpLabel;
    private JLabel serverPrivacyBadge;
    private JButton togglePrivateBtn;
    private JTextField addPrivateIpField;
    private JPanel privateIpsChips;
    private JComboBox<InstanceServerOption> instanceServersBox;

    private record InstanceServerOption(Instance instance, com.launcher.minecraft.ServerListReader.ServerEntry entry) {
        @Override public String toString() {
            return instance.name + "  ›  " + entry.name + "  (" + entry.ip + ")";
        }
    }

    private JTabbedPane mainTabPane;

    public Main() {
        setTitle("Zero Launcher");
        setMinimumSize(new Dimension(820, 560));
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        com.launcher.model.LauncherSettings initSettings = com.launcher.manager.SettingsManager.getInstance().getSettings();
        int initW = (initSettings.launcherWidth >= 820) ? initSettings.launcherWidth : 960;
        int initH = (initSettings.launcherHeight >= 560) ? initSettings.launcherHeight : 660;
        setSize(initW, initH);
        setLocationRelativeTo(null);

        // Load default window icon
        try {
            URL iconUrl = getClass().getResource("/com/launcher/ZeroLauncherIcon.png");
            if (iconUrl != null) {
                setIconImage(new ImageIcon(iconUrl).getImage());
            }
        } catch (Exception ignored) {}

        // Load default mod icon
        try {
            InputStream is = getClass().getResourceAsStream("/com/launcher/minecraft_image.png");
            if (is != null) {
                defaultModIcon = new ImageIcon(ImageIO.read(is));
            }
        } catch (Exception ignored) {}

        // Setup Main Layout
        JPanel rootPanel = new JPanel(new BorderLayout());
        setContentPane(rootPanel);

        rootPanel.add(buildTopBar(), BorderLayout.NORTH);

        mainTabPane = new JTabbedPane();
        mainTabPane.addTab("⚡  Instances", buildInstanceArea());
        mainTabPane.addTab("📦  Mods", buildModsArea());
        mainTabPane.addTab("🌐  Discover", buildDiscoverArea());
        mainTabPane.addTab("⚙  Settings", buildSettingsArea());
        rootPanel.add(mainTabPane, BorderLayout.CENTER);

        rootPanel.add(buildLogArea(), BorderLayout.SOUTH);

        // Toast-notification overlay
        notifications = new NotificationCenter(this);

        applyTheme();

        com.launcher.manager.DiscordRpcManager.getInstance().init();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                com.launcher.model.LauncherSettings s = com.launcher.manager.SettingsManager.getInstance().getSettings();
                if (s.clearSessionOnExit) {
                    for (Account acc : accountManager.getAccounts()) accountManager.addOrUpdate(acc);
                }
                com.launcher.manager.DiscordRpcManager.getInstance().shutdown();
                System.exit(0);
            }

            @Override
            public void windowIconified(WindowEvent e) {
                isMinimized = true;
                logFlushScheduled = false;
                long now = System.currentTimeMillis();
                if (now - lastLogFlushWhenMinimized > 30_000) {
                    lastLogFlushWhenMinimized = now;
                    System.gc();
                }
            }

            @Override
            public void windowDeiconified(WindowEvent e) {
                isMinimized = false;
            }
        });

        // Initial refreshes
        refreshAccounts();
        refreshInstances();
        if (instanceList.getSelectedValue() != null) {
            updateDawnStatus(instanceList.getSelectedValue());
        }

        // Auto-refresh Discover tab with trending content
        refreshDiscoverInstances();
        SwingUtilities.invokeLater(() -> performDiscoverSearch());

        if (com.launcher.manager.SettingsManager.getInstance().getSettings().checkModUpdatesOnStartup) {
            runStartupModUpdateCheck();
        }
    }

    private void runStartupModUpdateCheck() {
        var targets = instanceManager.getInstances().stream().filter(i -> !i.hidden).toList();
        if (targets.isEmpty()) return;

        new Thread(() -> {
            ModUpdateService service = new ModUpdateService();
            Map<String, Integer> updatesByInstance = new LinkedHashMap<>();
            int scannedInstances = 0;

            for (Instance inst : targets) {
                try {
                    Path modsDir = instanceManager.resolveGameDir(inst).resolve("mods");
                    List<ModEntry> mods = service.scanModsDir(modsDir);
                    if (mods.isEmpty()) continue;

                    scannedInstances++;
                    service.identifyMods(mods, msg -> {});
                    String loaderName = inst.modLoader != null && inst.modLoader != ModLoaderType.VANILLA
                            ? inst.modLoader.name().toLowerCase() : null;
                    service.checkUpdates(mods, inst.mcVersion, loaderName, msg -> {});

                    long updatable = mods.stream().filter(m -> "Update available".equals(m.status)).count();
                    if (updatable > 0) updatesByInstance.put(inst.name, (int) updatable);
                } catch (Exception ignored) {}
            }

            final int finalScanned = scannedInstances;
            SwingUtilities.invokeLater(() -> {
                if (finalScanned == 0) return;
                if (updatesByInstance.isEmpty()) {
                    notifications.info("Mods up to date", "Checked " + finalScanned + " instance(s) — no mod updates found.");
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (var entry : updatesByInstance.entrySet()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(entry.getKey()).append(": ").append(entry.getValue()).append(" update(s)");
                    }
                    notifications.success("Mod updates available", sb.toString());
                }
                Instance sel = instanceList.getSelectedValue();
                if (sel != null && updatesByInstance.containsKey(sel.name)) {
                    refreshModsView(sel);
                }
            });
        }, "startup-mod-update-check").start();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TOP BAR — account selector + launcher branding
    // ══════════════════════════════════════════════════════════════════════════
    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(new EmptyBorder(10, 16, 10, 16));

        // Branding
        JPanel brand = new JPanel();
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        JLabel logo = new JLabel("ZERO");
        logo.setFont(new Font("SansSerif", Font.BOLD, 17));
        logo.setForeground(Color.WHITE);
        JLabel logoSub = new JLabel("LAUNCHER");
        logoSub.setFont(new Font("SansSerif", Font.BOLD, 8));
        logoSub.setForeground(new Color(16, 185, 129));
        brand.add(logo);
        brand.add(logoSub);
        bar.add(brand, BorderLayout.WEST);

        // Account box panel
        JPanel accPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        accountBox = new JComboBox<>();
        accountBox.setPreferredSize(new Dimension(210, 30));
        accountBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Account a) {
                    com.launcher.model.LauncherSettings s = com.launcher.manager.SettingsManager.getInstance().getSettings();
                    setText((s.hideUsername ? "●●●●●" : a.username) + "  ·  Offline");
                } else {
                    setText("No account selected");
                }
                return this;
            }
        });
        accountBox.addActionListener(e -> {
            Account a = (Account) accountBox.getSelectedItem();
            if (a != null) {
                accountManager.setActiveAccount(a);
            }
        });

        JButton addAccBtn = new JButton("+");
        addAccBtn.setPreferredSize(new Dimension(30, 30));
        addAccBtn.setToolTipText("Add Account");
        addAccBtn.addActionListener(e -> LoginDialog.show(this, acc -> {
            accountManager.addOrUpdate(acc);
            accountManager.setActiveAccount(acc);
            refreshAccounts();
            notifications.success("Account added", "Added offline account: " + acc.username);
        }));

        JButton rmAccBtn = new JButton("✕");
        rmAccBtn.setPreferredSize(new Dimension(30, 30));
        rmAccBtn.setToolTipText("Remove Account");
        rmAccBtn.addActionListener(e -> {
            Account selected = (Account) accountBox.getSelectedItem();
            if (selected != null) {
                accountManager.remove(selected);
                refreshAccounts();
                notifications.warning("Account removed", "Removed account " + selected.username);
            }
        });

        accPanel.add(accountBox);
        accPanel.add(addAccBtn);
        accPanel.add(rmAccBtn);
        bar.add(accPanel, BorderLayout.EAST);

        return bar;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  INSTANCES TAB
    // ══════════════════════════════════════════════════════════════════════════
    private JPanel buildInstanceArea() {
        JPanel p = new JPanel(new BorderLayout());

        // Left List
        instanceListModel = new DefaultListModel<>();
        instanceList = new JList<>(instanceListModel);
        instanceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        instanceList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Instance inst) {
                    setText(inst.name + " (" + inst.mcVersion + ")");
                    // Try to load custom icon
                    if (inst.imagePath != null && !inst.imagePath.isBlank()) {
                        try {
                            File file = new File(inst.imagePath);
                            if (file.exists()) {
                                setIcon(new ImageIcon(new ImageIcon(file.getAbsolutePath()).getImage().getScaledInstance(24, 24, Image.SCALE_SMOOTH)));
                            } else {
                                setIcon(defaultModIcon != null ? new ImageIcon(defaultModIcon.getImage().getScaledInstance(24, 24, Image.SCALE_SMOOTH)) : null);
                            }
                        } catch (Exception ignored) {}
                    } else {
                        setIcon(defaultModIcon != null ? new ImageIcon(defaultModIcon.getImage().getScaledInstance(24, 24, Image.SCALE_SMOOTH)) : null);
                    }
                }
                return this;
            }
        });

        JScrollPane listScroll = new JScrollPane(instanceList);
        listScroll.setPreferredSize(new Dimension(240, 0));
        p.add(listScroll, BorderLayout.WEST);

        // Center Panel: Selected Instance Info
        JPanel detailsPanel = new JPanel(new BorderLayout());
        detailsPanel.setBorder(new EmptyBorder(16, 16, 16, 16));

        JPanel centerInfo = new JPanel();
        centerInfo.setLayout(new BoxLayout(centerInfo, BoxLayout.Y_AXIS));

        JLabel nameLbl = new JLabel("No instance selected");
        nameLbl.setFont(new Font("SansSerif", Font.BOLD, 20));
        nameLbl.setForeground(Color.WHITE);
        centerInfo.add(nameLbl);
        centerInfo.add(Box.createVerticalStrut(10));

        JLabel infoLbl = new JLabel("");
        infoLbl.setFont(new Font("SansSerif", Font.PLAIN, 13));
        centerInfo.add(infoLbl);
        centerInfo.add(Box.createVerticalStrut(10));

        // Dawn status panel
        JPanel dawnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        dawnStatusLabel = new JLabel("Dawn client: —");
        installDawnButton = new JButton("Install Dawn Client");
        deleteDawnButton = new JButton("Uninstall");
        dawnPanel.add(dawnStatusLabel);
        dawnPanel.add(Box.createHorizontalStrut(10));
        dawnPanel.add(installDawnButton);
        dawnPanel.add(Box.createHorizontalStrut(4));
        dawnPanel.add(deleteDawnButton);
        centerInfo.add(dawnPanel);

        detailsPanel.add(centerInfo, BorderLayout.CENTER);

        // Buttons Panel at the bottom of detail panel
        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        playButton = new JButton("PLAY");
        playButton.setPreferredSize(new Dimension(120, 36));
        playButton.setFont(new Font("SansSerif", Font.BOLD, 14));
        actionRow.add(playButton);

        JButton editBtn = new JButton("Edit");
        editBtn.setPreferredSize(new Dimension(80, 36));
        actionRow.add(editBtn);

        JButton delBtn = new JButton("Delete");
        delBtn.setPreferredSize(new Dimension(80, 36));
        actionRow.add(delBtn);

        JButton manageModsBtn = new JButton("Manage Mods");
        manageModsBtn.setPreferredSize(new Dimension(130, 36));
        actionRow.add(manageModsBtn);

        detailsPanel.add(actionRow, BorderLayout.SOUTH);
        p.add(detailsPanel, BorderLayout.CENTER);

        // Selection Listener
        instanceList.addListSelectionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel != null) {
                nameLbl.setText(sel.name);
                String details = String.format("<html>Minecraft: %s<br>Loader: %s (%s)<br>RAM: %d MB<br>Path: %s</html>",
                        sel.mcVersion,
                        sel.modLoader,
                        sel.modLoaderVersion != null ? sel.modLoaderVersion : "None",
                        sel.ramMb,
                        sel.customDirectoryPath != null ? sel.customDirectoryPath : "Standard");
                infoLbl.setText(details);
                updateDawnStatus(sel);
            } else {
                nameLbl.setText("No instance selected");
                infoLbl.setText("");
            }
        });

        // Add instance button at bottom of list
        JPanel listButtons = new JPanel(new GridLayout(1, 2));
        JButton addInstBtn = new JButton("+ New Instance");
        addInstBtn.addActionListener(e -> {
            Optional<Instance> res = CreateInstanceDialog.show(this);
            res.ifPresent(i -> {
                instanceManager.add(i);
                instanceManager.save();
                refreshInstances();
                notifications.success("Instance created", "Created: " + i.name);
            });
        });
        listButtons.add(addInstBtn);
        p.add(listButtons, BorderLayout.SOUTH);

        // Action Handlers
        playButton.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            Account acc = (Account) accountBox.getSelectedItem();
            if (sel == null) {
                notifications.error("Launch failed", "No instance selected.");
                return;
            }
            if (acc == null) {
                notifications.error("Launch failed", "Please select or create an account first.");
                return;
            }
            playButton.setEnabled(false);
            launchGame(sel, acc);
        });

        editBtn.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel != null) {
                Optional<Instance> res = EditInstanceDialog.show(this, sel);
                res.ifPresent(i -> {
                    instanceManager.save();
                    refreshInstances();
                    notifications.success("Instance updated", "Saved changes for: " + i.name);
                });
            }
        });

        delBtn.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel != null) {
                int res = JOptionPane.showConfirmDialog(this, "Are you sure you want to delete " + sel.name + "?", "Delete Instance", JOptionPane.YES_NO_OPTION);
                if (res == JOptionPane.YES_OPTION) {
                    instanceManager.remove(sel);
                    instanceManager.save();
                    refreshInstances();
                    notifications.warning("Instance deleted", "Deleted: " + sel.name);
                }
            }
        });

        manageModsBtn.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel != null) {
                mainTabPane.setSelectedIndex(1); // Select mods tab
                refreshModsView(sel);
            }
        });

        installDawnButton.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel != null) {
                installDawn(sel);
            }
        });

        deleteDawnButton.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel != null) {
                uninstallDawn(sel);
            }
        });

        return p;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MODS TAB
    // ══════════════════════════════════════════════════════════════════════════
    private JPanel buildModsArea() {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(new EmptyBorder(14, 14, 10, 14));

        // ── Header ──────────────────────────────────────────────────────────
        JPanel header = new JPanel(new BorderLayout(10, 0));
        JPanel headerLeft = new JPanel();
        headerLeft.setLayout(new BoxLayout(headerLeft, BoxLayout.Y_AXIS));
        modsHeaderLabel = new JLabel("Manage Mods");
        modsHeaderLabel.setFont(new Font("SansSerif", Font.BOLD, 20));
        modsHeaderLabel.setForeground(Color.WHITE);
        modsCountLabel = new JLabel("Select an instance to view mods");
        modsCountLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        modsCountLabel.setForeground(new Color(150, 150, 165));
        headerLeft.add(modsHeaderLabel);
        headerLeft.add(modsCountLabel);
        header.add(headerLeft, BorderLayout.WEST);

        // ── Toolbar row ─────────────────────────────────────────────────────
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

        modsSearchField = new JTextField();
        modsSearchField.putClientProperty("JTextField.placeholderText", "Filter mods…");
        modsSearchField.setPreferredSize(new Dimension(170, 30));
        modsSearchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { filterMods(); }
            @Override public void removeUpdate(DocumentEvent e) { filterMods(); }
            @Override public void changedUpdate(DocumentEvent e) { filterMods(); }
        });
        toolbar.add(modsSearchField);

        refreshModsBtn = new JButton("↻ Refresh");
        checkUpdatesBtn = new JButton("Check Updates");
        updateAllBtn = new JButton("Update All");
        updateSelectedBtn = new JButton("Update Selected");
        deleteModBtn = new JButton("Delete");
        dedupeModsBtn = new JButton("Deduplicate");
        installDependenciesBtn = new JButton("Install Deps");

        JButton[] btns = {refreshModsBtn, checkUpdatesBtn, updateAllBtn, updateSelectedBtn,
                           deleteModBtn, dedupeModsBtn, installDependenciesBtn};
        for (JButton b : btns) {
            b.setFont(new Font("SansSerif", Font.PLAIN, 11));
            b.setFocusPainted(false);
            toolbar.add(b);
        }

        JPanel topSection = new JPanel(new BorderLayout());
        topSection.add(header, BorderLayout.NORTH);
        topSection.add(toolbar, BorderLayout.SOUTH);
        p.add(topSection, BorderLayout.NORTH);

        // ── Mod List with rich card renderer ────────────────────────────────
        modsListModel = new DefaultListModel<>();
        modsList = new JList<>(modsListModel);
        modsList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        modsList.setFixedCellHeight(62);
        modsList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            if (!(value instanceof ModEntry)) return new JLabel();
            ModEntry mod = (ModEntry) value;

            JPanel card = new JPanel(new BorderLayout(10, 0));
            card.setBorder(new EmptyBorder(6, 10, 6, 10));
            card.setBackground(isSelected ? new Color(16, 185, 129, 30) : new Color(26, 26, 36));

            if (isSelected) {
                card.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(16, 185, 129, 80), 1),
                        new EmptyBorder(5, 9, 5, 9)));
            }

            // Icon (32×32, async-loaded)
            JLabel iconLbl = new JLabel();
            iconLbl.setPreferredSize(new Dimension(32, 32));
            if (mod.iconUrl != null && !mod.iconUrl.isBlank()) {
                ImageIcon icon = modIconCache.get(mod.iconUrl);
                if (icon != null) {
                    iconLbl.setIcon(icon);
                } else {
                    iconLbl.setIcon(defaultModIcon);
                    final String url = mod.iconUrl;
                    new Thread(() -> {
                        try {
                            ImageIcon ic = new ImageIcon(new ImageIcon(new URI(url).toURL())
                                    .getImage().getScaledInstance(32, 32, Image.SCALE_SMOOTH));
                            modIconCache.put(url, ic);
                            SwingUtilities.invokeLater(() -> modsList.repaint());
                        } catch (Exception ignored) {}
                    }, "mod-icon").start();
                }
            } else {
                iconLbl.setIcon(defaultModIcon);
            }
            card.add(iconLbl, BorderLayout.WEST);

            // Center: name, file name, version info
            JPanel center = new JPanel();
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            center.setOpaque(false);

            JLabel nameLbl = new JLabel(mod.displayName());
            nameLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
            nameLbl.setForeground(Color.WHITE);
            nameLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            center.add(nameLbl);

            // Second row: file name + size
            String infoText = mod.fileName + "  ·  " + mod.formattedSize();
            JLabel infoLbl = new JLabel(infoText);
            infoLbl.setFont(new Font("SansSerif", Font.PLAIN, 10));
            infoLbl.setForeground(new Color(120, 120, 140));
            infoLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            center.add(infoLbl);

            // Third row: version info
            String verText = mod.currentVersion != null ? "v" + mod.currentVersion : "local";
            if (mod.latestVersion != null && !mod.latestVersion.equals(mod.currentVersion)) {
                verText += "  →  v" + mod.latestVersion;
            }
            JLabel verLbl = new JLabel(verText);
            verLbl.setFont(new Font("SansSerif", Font.PLAIN, 10));
            verLbl.setForeground(mod.latestVersion != null ? new Color(245, 158, 11) : new Color(140, 140, 160));
            verLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            center.add(verLbl);

            card.add(center, BorderLayout.CENTER);

            // Right: status pill
            JLabel statusLbl = new JLabel(mod.status);
            statusLbl.setFont(new Font("SansSerif", Font.BOLD, 10));
            statusLbl.setOpaque(true);
            statusLbl.setBorder(new EmptyBorder(3, 8, 3, 8));
            switch (mod.status) {
                case "Up to date" -> {
                    statusLbl.setForeground(new Color(16, 185, 129));
                    statusLbl.setBackground(new Color(16, 185, 129, 25));
                }
                case "Update available" -> {
                    statusLbl.setForeground(new Color(245, 158, 11));
                    statusLbl.setBackground(new Color(245, 158, 11, 25));
                }
                case "Checking…" -> {
                    statusLbl.setForeground(new Color(129, 140, 248));
                    statusLbl.setBackground(new Color(99, 102, 241, 25));
                }
                case "Error" -> {
                    statusLbl.setForeground(new Color(239, 68, 68));
                    statusLbl.setBackground(new Color(239, 68, 68, 20));
                }
                default -> { // Unknown
                    statusLbl.setForeground(new Color(140, 140, 160));
                    statusLbl.setBackground(new Color(255, 255, 255, 10));
                }
            }
            JPanel statusWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 12));
            statusWrap.setOpaque(false);
            statusWrap.add(statusLbl);
            card.add(statusWrap, BorderLayout.EAST);

            return card;
        });

        JScrollPane scroll = new JScrollPane(modsList);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        p.add(scroll, BorderLayout.CENTER);

        // ── Button actions ──────────────────────────────────────────────────
        refreshModsBtn.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel != null) refreshModsView(sel);
        });

        checkUpdatesBtn.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel == null) return;
            checkUpdatesBtn.setEnabled(false);
            notifications.info("Checking Updates", "Scanning Modrinth for mod updates…");
            new Thread(() -> {
                try {
                    ModUpdateService service = new ModUpdateService();
                    service.identifyMods(currentModEntries, msg -> SwingUtilities.invokeLater(() -> setStatus(msg)));
                    String loader = sel.modLoader != null && sel.modLoader != ModLoaderType.VANILLA ? sel.modLoader.name().toLowerCase() : null;
                    service.checkUpdates(currentModEntries, sel.mcVersion, loader, msg -> SwingUtilities.invokeLater(() -> setStatus(msg)));
                    SwingUtilities.invokeLater(() -> {
                        filterMods();
                        checkUpdatesBtn.setEnabled(true);
                        long updatable = currentModEntries.stream().filter(m -> "Update available".equals(m.status)).count();
                        notifications.success("Check completed", updatable + " update(s) available out of " + currentModEntries.size() + " mods.");
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        notifications.error("Update check failed", ex.getMessage());
                        checkUpdatesBtn.setEnabled(true);
                    });
                }
            }, "check-updates").start();
        });

        updateAllBtn.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel == null) return;
            List<ModEntry> updatable = currentModEntries.stream()
                    .filter(m -> "Update available".equals(m.status) && m.updateUrl != null)
                    .toList();
            if (updatable.isEmpty()) {
                notifications.info("No updates", "All mods are up to date.");
                return;
            }
            updateAllBtn.setEnabled(false);
            notifications.info("Updating", "Updating " + updatable.size() + " mod(s)…");
            new Thread(() -> {
                ModUpdateService service = new ModUpdateService();
                Path modsDir = instanceManager.resolveGameDir(sel).resolve("mods");
                int ok = 0;
                for (ModEntry mod : updatable) {
                    if (service.downloadUpdate(mod, modsDir, msg -> SwingUtilities.invokeLater(() -> setStatus(msg)))) ok++;
                }
                final int finalOk = ok;
                SwingUtilities.invokeLater(() -> {
                    filterMods();
                    updateAllBtn.setEnabled(true);
                    notifications.success("Updates complete", "Updated " + finalOk + " of " + updatable.size() + " mods.");
                });
            }, "update-all").start();
        });

        updateSelectedBtn.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel == null) return;
            List<ModEntry> selected = modsList.getSelectedValuesList().stream()
                    .filter(m -> "Update available".equals(m.status) && m.updateUrl != null)
                    .toList();
            if (selected.isEmpty()) {
                notifications.info("No updates", "Selected mods have no available updates.");
                return;
            }
            updateSelectedBtn.setEnabled(false);
            new Thread(() -> {
                ModUpdateService service = new ModUpdateService();
                Path modsDir = instanceManager.resolveGameDir(sel).resolve("mods");
                int ok = 0;
                for (ModEntry mod : selected) {
                    if (service.downloadUpdate(mod, modsDir, msg -> SwingUtilities.invokeLater(() -> setStatus(msg)))) ok++;
                }
                final int finalOk = ok;
                SwingUtilities.invokeLater(() -> {
                    filterMods();
                    updateSelectedBtn.setEnabled(true);
                    notifications.success("Updates complete", "Updated " + finalOk + " of " + selected.size() + " selected mods.");
                });
            }, "update-selected").start();
        });

        deleteModBtn.addActionListener(e -> {
            List<ModEntry> selected = modsList.getSelectedValuesList();
            if (selected.isEmpty()) return;
            int res = JOptionPane.showConfirmDialog(this, "Delete " + selected.size() + " mod(s)?", "Delete Mods", JOptionPane.YES_NO_OPTION);
            if (res == JOptionPane.YES_OPTION) {
                for (ModEntry m : selected) {
                    try { Files.deleteIfExists(Path.of(m.filePath)); } catch (Exception ignored) {}
                }
                Instance sel = instanceList.getSelectedValue();
                if (sel != null) refreshModsView(sel);
                notifications.warning("Mods deleted", "Deleted " + selected.size() + " mod(s).");
            }
        });

        dedupeModsBtn.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel == null) return;
            // Find mods with the same Modrinth project ID (keeping the newest version)
            Map<String, List<ModEntry>> byProject = new LinkedHashMap<>();
            for (ModEntry m : currentModEntries) {
                if (m.modrinthId != null) {
                    byProject.computeIfAbsent(m.modrinthId, k -> new ArrayList<>()).add(m);
                }
            }
            int removed = 0;
            for (var group : byProject.values()) {
                if (group.size() <= 1) continue;
                // Keep the first (typically identified version); delete the rest
                for (int i = 1; i < group.size(); i++) {
                    try {
                        Files.deleteIfExists(Path.of(group.get(i).filePath));
                        removed++;
                    } catch (Exception ignored) {}
                }
            }
            if (removed == 0) {
                notifications.info("No duplicates", "No duplicate mods found.");
            } else {
                refreshModsView(sel);
                notifications.success("Deduplicated", "Removed " + removed + " duplicate mod(s).");
            }
        });

        installDependenciesBtn.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel == null) return;
            installDependenciesBtn.setEnabled(false);
            notifications.info("Finding dependencies", "Scanning for missing required dependencies…");
            new Thread(() -> {
                try {
                    ModUpdateService service = new ModUpdateService();
                    String loader = sel.modLoader != null && sel.modLoader != ModLoaderType.VANILLA ? sel.modLoader.name().toLowerCase() : null;
                    var missing = service.findMissingRequiredDependencies(
                            currentModEntries, loader, sel.mcVersion, msg -> SwingUtilities.invokeLater(() -> setStatus(msg)));
                    if (missing.isEmpty()) {
                        SwingUtilities.invokeLater(() -> {
                            installDependenciesBtn.setEnabled(true);
                            notifications.info("No missing deps", "All required dependencies are already installed.");
                        });
                        return;
                    }
                    // Download each missing dependency
                    Path modsDir = instanceManager.resolveGameDir(sel).resolve("mods");
                    Files.createDirectories(modsDir);
                    int installed = 0;
                    for (var entry : missing.entrySet()) {
                        try {
                            String url = service.getDownloadUrlForProject(entry.getKey(), "mod", loader, sel.mcVersion);
                            if (url == null) continue;
                            String fileName = url.substring(url.lastIndexOf('/') + 1);
                            com.launcher.util.HttpUtil.downloadToFile(url, modsDir.resolve(fileName));
                            installed++;
                            final String depName = entry.getValue();
                            SwingUtilities.invokeLater(() -> setStatus("Installed dependency: " + depName));
                        } catch (Exception ignored) {}
                    }
                    final int finalInstalled = installed;
                    SwingUtilities.invokeLater(() -> {
                        installDependenciesBtn.setEnabled(true);
                        refreshModsView(sel);
                        notifications.success("Dependencies installed", "Installed " + finalInstalled + " of " + missing.size() + " dependencies.");
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        installDependenciesBtn.setEnabled(true);
                        notifications.error("Dependency check failed", ex.getMessage());
                    });
                }
            }, "install-deps").start();
        });

        return p;
    }

    private void refreshModsView(Instance inst) {
        modsHeaderLabel.setText("Mods — " + inst.name);
        modsCountLabel.setText("Scanning…");
        new Thread(() -> {
            try {
                ModUpdateService service = new ModUpdateService();
                Path modsDir = instanceManager.resolveGameDir(inst).resolve("mods");
                List<ModEntry> list = service.scanModsDir(modsDir);
                // Auto-identify mods in background
                service.identifyMods(list, msg -> SwingUtilities.invokeLater(() -> setStatus(msg)));
                SwingUtilities.invokeLater(() -> {
                    currentModEntries = list;
                    filterMods();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> notifications.error("Mod scan failed", ex.getMessage()));
            }
        }, "mod-scan").start();
    }

    private void filterMods() {
        String filter = modsSearchField.getText().toLowerCase().trim();
        modsListModel.clear();
        int count = 0;
        for (ModEntry m : currentModEntries) {
            if (filter.isEmpty() || m.displayName().toLowerCase().contains(filter)) {
                modsListModel.addElement(m);
                count++;
            }
        }
        modsCountLabel.setText(count + " mod" + (count != 1 ? "s" : "") + " shown");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DISCOVER TAB (Modrinth)
    // ══════════════════════════════════════════════════════════════════════════
    private JPanel buildDiscoverArea() {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(new EmptyBorder(12, 14, 10, 14));

        // ── Header row ──────────────────────────────────────────────────────
        JPanel header = new JPanel(new BorderLayout());
        JPanel headerLeft = new JPanel();
        headerLeft.setLayout(new BoxLayout(headerLeft, BoxLayout.Y_AXIS));
        JLabel titleLbl = new JLabel("Discover");
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 20));
        titleLbl.setForeground(Color.WHITE);
        JLabel subtitleLbl = new JLabel("Browse mods & resource packs on Modrinth");
        subtitleLbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
        subtitleLbl.setForeground(new Color(160, 160, 175));
        headerLeft.add(titleLbl);
        headerLeft.add(subtitleLbl);
        header.add(headerLeft, BorderLayout.WEST);

        // ── Filter bar ──────────────────────────────────────────────────────
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));

        filters.add(new JLabel("Target:"));
        discoverInstanceBox = new JComboBox<>();
        discoverInstanceBox.setPreferredSize(new Dimension(180, 30));
        filters.add(discoverInstanceBox);

        // Segmented toggle for Mods / Resource Packs
        discoverModsToggle = new JToggleButton("Mods", true);
        discoverPacksToggle = new JToggleButton("Resource Packs", false);
        discoverModsToggle.setFont(new Font("SansSerif", Font.BOLD, 11));
        discoverPacksToggle.setFont(new Font("SansSerif", Font.BOLD, 11));
        discoverModsToggle.setFocusPainted(false);
        discoverPacksToggle.setFocusPainted(false);
        ButtonGroup discoverGroup = new ButtonGroup();
        discoverGroup.add(discoverModsToggle);
        discoverGroup.add(discoverPacksToggle);
        filters.add(discoverModsToggle);
        filters.add(discoverPacksToggle);

        discoverSearchField = new JTextField();
        discoverSearchField.putClientProperty("JTextField.placeholderText", "Search Modrinth...");
        discoverSearchField.setPreferredSize(new Dimension(220, 30));
        filters.add(discoverSearchField);

        discoverSearchBtn = new JButton("Search");
        filters.add(discoverSearchBtn);

        discoverRefreshBtn = new JButton("↻ Refresh");
        discoverRefreshBtn.setVisible(false); // shown only when results are empty
        filters.add(discoverRefreshBtn);

        JPanel topSection = new JPanel(new BorderLayout());
        topSection.add(header, BorderLayout.NORTH);
        topSection.add(filters, BorderLayout.SOUTH);
        p.add(topSection, BorderLayout.NORTH);

        // ── Results pane (WrapLayout inside JScrollPane) ────────────────────
        discoverResultsPane = new JPanel(new WrapLayout(FlowLayout.LEFT, 10, 10));
        JScrollPane scroll = new JScrollPane(discoverResultsPane);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setBorder(null);
        // Re-layout on resize so WrapLayout recalculates row heights
        scroll.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                discoverResultsPane.revalidate();
            }
        });
        p.add(scroll, BorderLayout.CENTER);

        // ── Bottom pagination row ───────────────────────────────────────────
        JPanel bottomRow = new JPanel(new BorderLayout());
        discoverStatusLabel = new JLabel("Enter a query to discover mods.");
        discoverStatusLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        discoverStatusLabel.setForeground(new Color(160, 160, 175));
        bottomRow.add(discoverStatusLabel, BorderLayout.WEST);

        JPanel pag = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 5));
        discoverPrevPageBtn = new JButton("‹ Prev");
        discoverNextPageBtn = new JButton("Next ›");
        discoverPageLabel = new JLabel("Page 1");
        discoverPageLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        discoverPrevPageBtn.setEnabled(false);
        discoverNextPageBtn.setEnabled(false);
        pag.add(discoverPrevPageBtn);
        pag.add(discoverPageLabel);
        pag.add(discoverNextPageBtn);
        bottomRow.add(pag, BorderLayout.EAST);
        p.add(bottomRow, BorderLayout.SOUTH);

        // ── Action listeners ────────────────────────────────────────────────
        Runnable doSearch = () -> { discoverOffset = 0; performDiscoverSearch(); };
        discoverSearchBtn.addActionListener(e -> doSearch.run());
        discoverSearchField.addActionListener(e -> doSearch.run()); // Enter key
        discoverRefreshBtn.addActionListener(e -> performDiscoverSearch());

        // Toggle also triggers a fresh search when results are already showing
        ActionListener toggleAction = e -> {
            if (discoverTotalHits > 0 || !discoverSearchField.getText().isBlank()) {
                discoverOffset = 0;
                performDiscoverSearch();
            }
        };
        discoverModsToggle.addActionListener(toggleAction);
        discoverPacksToggle.addActionListener(toggleAction);

        discoverPrevPageBtn.addActionListener(e -> {
            if (discoverOffset >= ModUpdateService.DISCOVER_PAGE_SIZE) {
                discoverOffset -= ModUpdateService.DISCOVER_PAGE_SIZE;
                performDiscoverSearch();
            }
        });

        discoverNextPageBtn.addActionListener(e -> {
            if (discoverOffset + ModUpdateService.DISCOVER_PAGE_SIZE < discoverTotalHits) {
                discoverOffset += ModUpdateService.DISCOVER_PAGE_SIZE;
                performDiscoverSearch();
            }
        });

        return p;
    }

    private void refreshDiscoverInstances() {
        if (discoverInstanceBox == null) return;
        discoverInstanceBox.removeAllItems();
        for (Instance inst : instanceManager.getInstances()) {
            discoverInstanceBox.addItem(inst);
        }
    }

    // ── Skeleton loading placeholders ────────────────────────────────────────
    private void showDiscoverSkeletons() {
        discoverResultsPane.removeAll();
        for (int i = 0; i < 6; i++) {
            discoverResultsPane.add(buildDiscoverSkeletonCard());
        }
        discoverResultsPane.revalidate();
        discoverResultsPane.repaint();
    }

    private JPanel buildDiscoverSkeletonCard() {
        JPanel card = new JPanel(new BorderLayout(8, 6)) {
            private float phase = 0f;
            {
                // Subtle shimmer animation
                javax.swing.Timer shimmer = new javax.swing.Timer(50, e -> {
                    phase += 0.08f;
                    if (phase > 2f) phase = 0f;
                    repaint();
                });
                shimmer.start();
            }
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                int w = getWidth();
                // Shimmer overlay
                float shimmerX = (phase - 1f) * w;
                java.awt.GradientPaint gp = new java.awt.GradientPaint(
                        shimmerX, 0, new Color(255, 255, 255, 0),
                        shimmerX + w * 0.4f, 0, new Color(255, 255, 255, 12));
                g2.setPaint(gp);
                g2.fillRect(0, 0, w, getHeight());
                g2.dispose();
            }
        };
        card.setPreferredSize(new Dimension(310, 160));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(55, 55, 65), 1),
                new EmptyBorder(10, 10, 10, 10)
        ));
        card.setBackground(new Color(30, 30, 40));

        // Placeholder icon
        JPanel iconPlaceholder = new JPanel();
        iconPlaceholder.setPreferredSize(new Dimension(48, 48));
        iconPlaceholder.setBackground(new Color(45, 45, 58));
        card.add(iconPlaceholder, BorderLayout.WEST);

        // Placeholder text lines
        JPanel textCol = new JPanel();
        textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
        textCol.setBackground(new Color(30, 30, 40));
        for (int i = 0; i < 3; i++) {
            JPanel line = new JPanel();
            line.setMaximumSize(new Dimension(i == 0 ? 160 : (i == 1 ? 200 : 80), 12));
            line.setPreferredSize(new Dimension(i == 0 ? 160 : (i == 1 ? 200 : 80), 12));
            line.setBackground(new Color(45, 45, 58));
            textCol.add(line);
            textCol.add(Box.createVerticalStrut(6));
        }
        card.add(textCol, BorderLayout.CENTER);
        return card;
    }

    // ── Search ──────────────────────────────────────────────────────────────
    private void performDiscoverSearch() {
        String query = discoverSearchField.getText().trim();
        boolean isPack = discoverPacksToggle.isSelected();
        String projectType = isPack ? "resourcepack" : "mod";

        Instance target = (Instance) discoverInstanceBox.getSelectedItem();
        String loader = null;
        String gameVersion = null;
        if (target != null) {
            gameVersion = target.mcVersion;
            if (target.modLoader != null && target.modLoader != ModLoaderType.VANILLA) {
                loader = target.modLoader.name().toLowerCase();
            }
        }

        discoverStatusLabel.setText("Searching Modrinth…");
        discoverSearchBtn.setEnabled(false);
        showDiscoverSkeletons();

        final String fLoader = loader;
        final String fGameVersion = gameVersion;

        new Thread(() -> {
            try {
                JsonObject result = discoverModService.searchProjectsPage(
                        query, projectType, fLoader, fGameVersion,
                        discoverOffset, ModUpdateService.DISCOVER_PAGE_SIZE);

                JsonArray hits = result.getAsJsonArray("hits");
                discoverTotalHits = result.get("total_hits").getAsInt();

                SwingUtilities.invokeLater(() -> {
                    discoverResultsPane.removeAll();
                    for (var el : hits) {
                        JsonObject hit = el.getAsJsonObject();
                        discoverResultsPane.add(buildDiscoverCard(hit, projectType, fLoader, fGameVersion));
                    }
                    discoverResultsPane.revalidate();
                    discoverResultsPane.repaint();

                    updateDiscoverPagination();
                    discoverStatusLabel.setText("Found " + discoverTotalHits + " result(s).");
                    discoverSearchBtn.setEnabled(true);
                    discoverRefreshBtn.setVisible(hits.isEmpty());
                });

            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    discoverResultsPane.removeAll();
                    discoverResultsPane.revalidate();
                    discoverResultsPane.repaint();
                    discoverStatusLabel.setText("Search failed: " + ex.getMessage());
                    discoverSearchBtn.setEnabled(true);
                    discoverRefreshBtn.setVisible(true);
                });
            }
        }, "modrinth-search").start();
    }

    private void updateDiscoverPagination() {
        int pageSize = ModUpdateService.DISCOVER_PAGE_SIZE;
        int page = (discoverOffset / pageSize) + 1;
        int totalPages = Math.max(1, (discoverTotalHits + pageSize - 1) / pageSize);
        discoverPageLabel.setText("Page " + page + " of " + totalPages);
        discoverPrevPageBtn.setEnabled(discoverOffset >= pageSize);
        discoverNextPageBtn.setEnabled(discoverOffset + pageSize < discoverTotalHits);
    }

    // ── Rich Discover Card ──────────────────────────────────────────────────
    private JPanel buildDiscoverCard(JsonObject hit, String projectType, String loader, String gameVersion) {
        JPanel card = new JPanel(new BorderLayout(8, 6));
        card.setPreferredSize(new Dimension(310, 175));
        Border defaultBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(55, 55, 65), 1),
                new EmptyBorder(10, 10, 10, 10));
        Border hoverBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 100), 1),
                new EmptyBorder(10, 10, 10, 10));
        card.setBorder(defaultBorder);
        card.setBackground(new Color(28, 28, 38));

        // Hover effect
        card.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                card.setBorder(hoverBorder);
                card.setBackground(new Color(32, 32, 44));
            }
            @Override public void mouseExited(MouseEvent e) {
                card.setBorder(defaultBorder);
                card.setBackground(new Color(28, 28, 38));
            }
        });

        String title = hit.get("title").getAsString();
        String desc = hit.has("description") ? hit.get("description").getAsString() : "";
        String author = hit.has("author") && !hit.get("author").isJsonNull() ? hit.get("author").getAsString() : "";
        String iconUrl = hit.has("icon_url") && !hit.get("icon_url").isJsonNull() ? hit.get("icon_url").getAsString() : null;
        String slug = hit.has("slug") ? hit.get("slug").getAsString() : "";
        String projectId = hit.has("project_id") ? hit.get("project_id").getAsString() : slug;
        int downloads = hit.has("downloads") ? hit.get("downloads").getAsInt() : 0;

        // Version compatibility — check if any of the project's listed versions match
        // the target instance's Minecraft version.
        boolean compatible = false;
        if (gameVersion != null && hit.has("versions") && hit.get("versions").isJsonArray()) {
            for (var v : hit.getAsJsonArray("versions")) {
                if (gameVersion.equals(v.getAsString())) { compatible = true; break; }
            }
        }

        // ── Left: icon (rounded corners) ────────────────────────────────────
        JLabel iconLabel = new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                Icon ic = getIcon();
                if (ic != null) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int s = Math.min(getWidth(), getHeight());
                    g2.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, s, s, 12, 12));
                    ic.paintIcon(this, g2, 0, 0);
                    g2.dispose();
                } else {
                    super.paintComponent(g);
                }
            }
        };
        iconLabel.setPreferredSize(new Dimension(48, 48));
        iconLabel.setVerticalAlignment(SwingConstants.TOP);
        if (iconUrl != null) {
            final String fIconUrl = iconUrl;
            // Load icon async to avoid blocking EDT
            ImageIcon cached = discoverIconCache.get(fIconUrl);
            if (cached != null) {
                iconLabel.setIcon(cached);
            } else {
                iconLabel.setIcon(defaultModIcon);
                new Thread(() -> {
                    try {
                        ImageIcon icon = new ImageIcon(new ImageIcon(new URI(fIconUrl).toURL())
                                .getImage().getScaledInstance(48, 48, Image.SCALE_SMOOTH));
                        discoverIconCache.put(fIconUrl, icon);
                        SwingUtilities.invokeLater(() -> iconLabel.setIcon(icon));
                    } catch (Exception ignored) {}
                }, "icon-load").start();
            }
        } else {
            iconLabel.setIcon(defaultModIcon);
        }
        card.add(iconLabel, BorderLayout.WEST);

        // ── Center: text info ───────────────────────────────────────────────
        JPanel textCol = new JPanel();
        textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
        textCol.setBackground(card.getBackground());

        // Title
        JLabel titleLbl = new JLabel("<html><b>" + escapeHtml(title) + "</b></html>");
        titleLbl.setForeground(Color.WHITE);
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        titleLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        textCol.add(titleLbl);

        // Author + download count
        String meta = "";
        if (!author.isEmpty()) meta += "by " + author;
        if (downloads > 0) {
            if (!meta.isEmpty()) meta += "  ·  ";
            meta += "⬇ " + formatCount(downloads);
        }
        if (!meta.isEmpty()) {
            JLabel metaLbl = new JLabel(meta);
            metaLbl.setFont(new Font("SansSerif", Font.PLAIN, 10));
            metaLbl.setForeground(new Color(140, 140, 155));
            metaLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            textCol.add(metaLbl);
        }

        // Compatibility badge
        if (gameVersion != null) {
            JLabel badge;
            if (compatible) {
                badge = new JLabel("✓ Supports " + gameVersion);
                badge.setForeground(new Color(80, 200, 120));
            } else {
                badge = new JLabel("⚠ Not your version");
                badge.setForeground(new Color(230, 170, 60));
            }
            badge.setFont(new Font("SansSerif", Font.PLAIN, 10));
            badge.setAlignmentX(Component.LEFT_ALIGNMENT);
            textCol.add(badge);
        }

        // Description
        JLabel descLbl = new JLabel("<html><body style='width: 180px;'>" + escapeHtml(desc) + "</body></html>");
        descLbl.setFont(new Font("SansSerif", Font.PLAIN, 10));
        descLbl.setForeground(new Color(170, 170, 180));
        descLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        textCol.add(Box.createVerticalStrut(2));
        textCol.add(descLbl);

        card.add(textCol, BorderLayout.CENTER);

        // ── Bottom: version picker + download button ────────────────────────
        JPanel bottomRow = new JPanel(new BorderLayout(6, 0));
        bottomRow.setBackground(card.getBackground());

        JComboBox<VersionOption> versionPicker = new JComboBox<>();
        versionPicker.setPreferredSize(new Dimension(140, 28));
        versionPicker.setFont(new Font("SansSerif", Font.PLAIN, 10));
        versionPicker.addItem(new VersionOption("Loading versions…", null, null));
        versionPicker.setEnabled(false);
        bottomRow.add(versionPicker, BorderLayout.CENTER);

        JButton downloadBtn = new JButton("Download");
        downloadBtn.setEnabled(false);
        bottomRow.add(downloadBtn, BorderLayout.EAST);

        card.add(bottomRow, BorderLayout.SOUTH);

        // ── Async: load versions for the picker ─────────────────────────────
        loadDiscoverCardVersions(projectId, projectType, loader, gameVersion, versionPicker, downloadBtn, title);

        return card;
    }

    /** Asynchronously loads version list into a card's dropdown and wires the download button. */
    private void loadDiscoverCardVersions(String projectId, String projectType,
                                           String loader, String gameVersion,
                                           JComboBox<VersionOption> picker,
                                           JButton downloadBtn, String title) {
        new Thread(() -> {
            try {
                JsonArray versions = discoverModService.listVersions(projectId, projectType, loader, gameVersion);
                List<VersionOption> options = new ArrayList<>();
                for (var el : versions) {
                    JsonObject v = el.getAsJsonObject();
                    String versionNumber = v.has("version_number") ? v.get("version_number").getAsString() : "?";
                    String versionName = v.has("name") && !v.get("name").isJsonNull() ? v.get("name").getAsString() : versionNumber;

                    // Check game version support for the label
                    boolean supportsTarget = false;
                    if (gameVersion != null && v.has("game_versions") && v.get("game_versions").isJsonArray()) {
                        for (var gv : v.getAsJsonArray("game_versions")) {
                            if (gameVersion.equals(gv.getAsString())) { supportsTarget = true; break; }
                        }
                    }

                    String[] file = ModUpdateService.primaryFileOf(v);
                    if (file == null) continue;

                    String label = versionNumber;
                    if (supportsTarget) label += "  ✓";
                    if (versionName != null && !versionName.equals(versionNumber)) {
                        // Truncate long names
                        String displayName = versionName.length() > 25 ? versionName.substring(0, 22) + "…" : versionName;
                        label += "  (" + displayName + ")";
                    }
                    options.add(new VersionOption(label, file[0], file[1]));
                }

                SwingUtilities.invokeLater(() -> {
                    picker.removeAllItems();
                    if (options.isEmpty()) {
                        picker.addItem(new VersionOption("No versions available", null, null));
                    } else {
                        for (VersionOption opt : options) picker.addItem(opt);
                        picker.setEnabled(true);
                        downloadBtn.setEnabled(true);
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    picker.removeAllItems();
                    picker.addItem(new VersionOption("Failed to load", null, null));
                });
            }
        }, "load-versions-" + projectId).start();

        // Wire download action
        downloadBtn.addActionListener(e -> {
            VersionOption selected = (VersionOption) picker.getSelectedItem();
            if (selected == null || selected.url() == null) return;
            Instance target = (Instance) discoverInstanceBox.getSelectedItem();
            if (target == null) {
                notifications.error("No target", "Select a target instance first.");
                return;
            }
            boolean isPack = "resourcepack".equals(projectType);
            downloadBtn.setEnabled(false);
            downloadBtn.setText("…");
            new Thread(() -> {
                try {
                    String subDir = isPack ? "resourcepacks" : "mods";
                    Path destDir = instanceManager.resolveGameDir(target).resolve(subDir);
                    Files.createDirectories(destDir);
                    Path destFile = destDir.resolve(selected.fileName());

                    com.launcher.util.HttpUtil.downloadFile(selected.url(), destFile,
                            msg -> SwingUtilities.invokeLater(() -> setStatus(msg)));
                    SwingUtilities.invokeLater(() -> {
                        String kind = isPack ? "resource pack" : "mod";
                        notifications.success("Downloaded " + kind, title + " installed into " + target.name);
                        downloadBtn.setText("✓ Done");
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        notifications.error("Download failed", ex.getMessage());
                        downloadBtn.setEnabled(true);
                        downloadBtn.setText("Download");
                    });
                }
            }, "discover-download").start();
        });
    }

    /** Formats a download count into a compact human-readable string (1.2K, 3.4M). */
    private static String formatCount(int count) {
        if (count >= 1_000_000) return String.format("%.1fM", count / 1_000_000.0);
        if (count >= 1_000) return String.format("%.1fK", count / 1_000.0);
        return String.valueOf(count);
    }

    /** Simple HTML-escape to prevent injection via project titles/descriptions. */
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SETTINGS TAB
    // ══════════════════════════════════════════════════════════════════════════
    private JPanel createCard(String title) {
        JPanel card = new JPanel();
        card.setLayout(new GridBagLayout());
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(60, 60, 70), 1, true),
                title,
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font("SansSerif", Font.BOLD, 13),
                new Color(16, 185, 129)
            ),
            BorderFactory.createEmptyBorder(10, 12, 10, 12)
        ));
        card.setBackground(new Color(19, 19, 26));
        return card;
    }

    private JScrollPane buildSettingsArea() {
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(new EmptyBorder(16, 16, 16, 16));

        com.launcher.model.LauncherSettings s = com.launcher.manager.SettingsManager.getInstance().getSettings();
        com.launcher.manager.SettingsManager mgr = com.launcher.manager.SettingsManager.getInstance();

        // ── 1. APPEARANCE CARD ────────────────────────────────────────────────
        JPanel appearanceCard = createCard("Appearance");
        GridBagConstraints gbc = createGbc();

        JTextField accentField = new JTextField(s.accentColor);
        accentField.addActionListener(e -> { s.accentColor = accentField.getText().trim(); mgr.save(); applyTheme(); });
        accentField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { s.accentColor = accentField.getText().trim(); mgr.save(); applyTheme(); }
        });
        addSettingsRow(appearanceCard, "Accent Color (Hex)", accentField, gbc);

        JTextField bgField = new JTextField(s.bgColor);
        bgField.addActionListener(e -> { s.bgColor = bgField.getText().trim(); mgr.save(); applyTheme(); });
        bgField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { s.bgColor = bgField.getText().trim(); mgr.save(); applyTheme(); }
        });
        addSettingsRow(appearanceCard, "Background Color (Hex)", bgField, gbc);

        JTextField panelBgField = new JTextField(s.panelBgColor);
        panelBgField.addActionListener(e -> { s.panelBgColor = panelBgField.getText().trim(); mgr.save(); applyTheme(); });
        panelBgField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { s.panelBgColor = panelBgField.getText().trim(); mgr.save(); applyTheme(); }
        });
        addSettingsRow(appearanceCard, "Panel Background (Hex)", panelBgField, gbc);

        JTextField textColorField = new JTextField(s.textColor);
        textColorField.addActionListener(e -> { s.textColor = textColorField.getText().trim(); mgr.save(); applyTheme(); });
        textColorField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { s.textColor = textColorField.getText().trim(); mgr.save(); applyTheme(); }
        });
        addSettingsRow(appearanceCard, "Text Color (Hex)", textColorField, gbc);

        JTextField logBgField = new JTextField(s.logBgColor);
        logBgField.addActionListener(e -> { s.logBgColor = logBgField.getText().trim(); mgr.save(); applyTheme(); });
        logBgField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { s.logBgColor = logBgField.getText().trim(); mgr.save(); applyTheme(); }
        });
        addSettingsRow(appearanceCard, "Log Background (Hex)", logBgField, gbc);

        JCheckBox enableBlurCb = new JCheckBox("Enable transparent/blur effect");
        enableBlurCb.setSelected(s.enableBlurEffect);
        
        JSlider blurSlider = new JSlider(1, 40, s.blurStrength > 0 ? s.blurStrength : 10);
        blurSlider.setEnabled(s.enableBlurEffect);
        JLabel blurValLabel = new JLabel(String.valueOf(blurSlider.getValue()));
        blurValLabel.setForeground(Color.LIGHT_GRAY);

        enableBlurCb.addActionListener(e -> {
            s.enableBlurEffect = enableBlurCb.isSelected();
            blurSlider.setEnabled(s.enableBlurEffect);
            mgr.save();
            applyTheme();
        });

        blurSlider.addChangeListener(e -> {
            s.blurStrength = blurSlider.getValue();
            blurValLabel.setText(String.valueOf(s.blurStrength));
            mgr.save();
            applyTheme();
        });

        addSettingsRow(appearanceCard, "Transparent/Blur", enableBlurCb, gbc);
        JPanel sliderPane = new JPanel(new BorderLayout(8, 0));
        sliderPane.setOpaque(false);
        sliderPane.add(blurSlider, BorderLayout.CENTER);
        sliderPane.add(blurValLabel, BorderLayout.EAST);
        addSettingsRow(appearanceCard, "Blur Strength", sliderPane, gbc);

        mainPanel.add(appearanceCard);
        mainPanel.add(Box.createVerticalStrut(12));

        // ── 2. BEHAVIOR CARD ──────────────────────────────────────────────────
        JPanel behaviorCard = createCard("Behavior");
        gbc = createGbc();

        JCheckBox minimizeCb = new JCheckBox("Minimize launcher while game is running");
        minimizeCb.setSelected(s.minimizeOnLaunch);
        minimizeCb.addActionListener(e -> { s.minimizeOnLaunch = minimizeCb.isSelected(); mgr.save(); });
        addSettingsRow(behaviorCard, "", minimizeCb, gbc);

        JCheckBox closeCb = new JCheckBox("Close launcher after game starts");
        closeCb.setSelected(s.closeAfterLaunch);
        closeCb.addActionListener(e -> { s.closeAfterLaunch = closeCb.isSelected(); mgr.save(); });
        addSettingsRow(behaviorCard, "", closeCb, gbc);

        JCheckBox showConsoleCb = new JCheckBox("Keep console visible while game is running");
        showConsoleCb.setSelected(s.showConsoleOnLaunch);
        showConsoleCb.addActionListener(e -> { s.showConsoleOnLaunch = showConsoleCb.isSelected(); mgr.save(); });
        addSettingsRow(behaviorCard, "", showConsoleCb, gbc);

        JCheckBox scanCb = new JCheckBox("Scan .minecraft folder for installed versions on startup");
        scanCb.setSelected(s.scanOnStartup);
        scanCb.addActionListener(e -> { s.scanOnStartup = scanCb.isSelected(); mgr.save(); });
        addSettingsRow(behaviorCard, "", scanCb, gbc);

        JCheckBox showHiddenCb = new JCheckBox("Show hidden instances");
        showHiddenCb.setSelected(s.showHiddenInstances);
        showHiddenCb.addActionListener(e -> { s.showHiddenInstances = showHiddenCb.isSelected(); mgr.save(); refreshInstances(); });
        addSettingsRow(behaviorCard, "", showHiddenCb, gbc);

        JCheckBox checkModUpdatesCb = new JCheckBox("Check for mod updates when launcher starts");
        checkModUpdatesCb.setSelected(s.checkModUpdatesOnStartup);
        checkModUpdatesCb.addActionListener(e -> { s.checkModUpdatesOnStartup = checkModUpdatesCb.isSelected(); mgr.save(); });
        addSettingsRow(behaviorCard, "", checkModUpdatesCb, gbc);

        mainPanel.add(behaviorCard);
        mainPanel.add(Box.createVerticalStrut(12));

        // ── 3. WINDOW SIZE CARD ───────────────────────────────────────────────
        JPanel sizeCard = createCard("Window Size");
        gbc = createGbc();

        int savedW = (s.launcherWidth >= 820) ? s.launcherWidth : 960;
        int savedH = (s.launcherHeight >= 560) ? s.launcherHeight : 660;

        SpinnerModel widthModel = new SpinnerNumberModel(savedW, 820, 3840, 10);
        JSpinner widthSpinner = new JSpinner(widthModel);
        widthSpinner.addChangeListener(e -> { s.launcherWidth = (int) widthSpinner.getValue(); mgr.save(); });
        addSettingsRow(sizeCard, "Width (px)", widthSpinner, gbc);

        SpinnerModel heightModel = new SpinnerNumberModel(savedH, 560, 2160, 10);
        JSpinner heightSpinner = new JSpinner(heightModel);
        heightSpinner.addChangeListener(e -> { s.launcherHeight = (int) heightSpinner.getValue(); mgr.save(); });
        addSettingsRow(sizeCard, "Height (px)", heightSpinner, gbc);

        JButton applySizeBtn = new JButton("Apply Now");
        applySizeBtn.addActionListener(e -> {
            if ((getExtendedState() & JFrame.MAXIMIZED_BOTH) == 0) {
                setSize(s.launcherWidth >= 820 ? s.launcherWidth : 960, s.launcherHeight >= 560 ? s.launcherHeight : 660);
                setLocationRelativeTo(null);
            }
        });
        addSettingsRow(sizeCard, "", applySizeBtn, gbc);

        mainPanel.add(sizeCard);
        mainPanel.add(Box.createVerticalStrut(12));

        // ── 4. PERFORMANCE CARD ───────────────────────────────────────────────
        JPanel performanceCard = createCard("Performance");
        gbc = createGbc();

        SpinnerModel ramModel = new SpinnerNumberModel(s.defaultRamGb > 0 ? s.defaultRamGb : 3, 1, 64, 1);
        JSpinner ramSpinner = new JSpinner(ramModel);
        ramSpinner.addChangeListener(e -> { s.defaultRamGb = (int) ramSpinner.getValue(); mgr.save(); });
        addSettingsRow(performanceCard, "Default RAM (GB)", ramSpinner, gbc);

        JTextField extraJvmField = new JTextField(s.extraJvmArgs != null ? s.extraJvmArgs : "");
        extraJvmField.addActionListener(e -> { s.extraJvmArgs = extraJvmField.getText().trim(); s.jvmArgs = s.extraJvmArgs; mgr.save(); });
        extraJvmField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { s.extraJvmArgs = extraJvmField.getText().trim(); s.jvmArgs = s.extraJvmArgs; mgr.save(); }
        });
        addSettingsRow(performanceCard, "Extra JVM Arguments", extraJvmField, gbc);

        JTextField javaPathField = new JTextField(s.javaPath != null ? s.javaPath : "");
        javaPathField.addActionListener(e -> { s.javaPath = javaPathField.getText().trim(); mgr.save(); });
        javaPathField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { s.javaPath = javaPathField.getText().trim(); mgr.save(); }
        });
        addSettingsRow(performanceCard, "Java Executable Path", javaPathField, gbc);

        mainPanel.add(performanceCard);
        mainPanel.add(Box.createVerticalStrut(12));

        // ── 5. PRIVACY & SECURITY CARD ────────────────────────────────────────
        JPanel privacyCard = createCard("Privacy & Security");
        gbc = createGbc();

        JCheckBox hideUserCb = new JCheckBox("Hide username in launcher UI");
        hideUserCb.setSelected(s.hideUsername);
        hideUserCb.addActionListener(e -> { s.hideUsername = hideUserCb.isSelected(); mgr.save(); refreshAccounts(); });
        addSettingsRow(privacyCard, "", hideUserCb, gbc);

        JCheckBox redactPathsCb = new JCheckBox("Redact OS username from log paths");
        redactPathsCb.setSelected(s.redactPaths);
        redactPathsCb.addActionListener(e -> { s.redactPaths = redactPathsCb.isSelected(); mgr.save(); refreshInstances(); });
        addSettingsRow(privacyCard, "", redactPathsCb, gbc);

        JCheckBox redactTokensCb = new JCheckBox("Redact Minecraft session tokens in logs");
        redactTokensCb.setSelected(s.redactTokens);
        redactTokensCb.addActionListener(e -> { s.redactTokens = redactTokensCb.isSelected(); mgr.save(); });
        addSettingsRow(privacyCard, "", redactTokensCb, gbc);

        JCheckBox clearSessionCb = new JCheckBox("Clear account sessions when the launcher closes");
        clearSessionCb.setSelected(s.clearSessionOnExit);
        clearSessionCb.addActionListener(e -> { s.clearSessionOnExit = clearSessionCb.isSelected(); mgr.save(); });
        addSettingsRow(privacyCard, "", clearSessionCb, gbc);

        mainPanel.add(privacyCard);
        mainPanel.add(Box.createVerticalStrut(12));

        // ── 6. DISCORD RPC CARD ───────────────────────────────────────────────
        JPanel discordCard = createCard("Discord RPC (Forced Off)");
        gbc = createGbc();

        JCheckBox enableRpcCb = new JCheckBox("Enable Discord Rich Presence");
        enableRpcCb.setSelected(false);
        enableRpcCb.setEnabled(false);
        addSettingsRow(discordCard, "", enableRpcCb, gbc);

        JCheckBox showServerCb = new JCheckBox("Show connected server IP in Discord status");
        showServerCb.setSelected(false);
        showServerCb.setEnabled(false);
        addSettingsRow(discordCard, "", showServerCb, gbc);

        JTextField rpcNameField = new JTextField(s.customDiscordRpcName != null ? s.customDiscordRpcName : "Zero Launcher");
        rpcNameField.setEnabled(false);
        addSettingsRow(discordCard, "Custom RPC Name", rpcNameField, gbc);

        JLabel discordNote = new JLabel("<html><body style='color:#ef4444;'>⚠ Currently this is not available. Try using a mod like Vanilla RPC instead.</body></html>");
        addSettingsRow(discordCard, "", discordNote, gbc);

        mainPanel.add(discordCard);

        JScrollPane scroll = new JScrollPane(mainPanel);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private GridBagConstraints createGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.weightx = 1.0;
        gbc.gridx = 0; gbc.gridy = 0;
        return gbc;
    }

    private void addSettingsRow(JPanel panel, String label, JComponent comp, GridBagConstraints gbc) {
        gbc.gridx = 0;
        if (label != null && !label.isEmpty()) {
            JLabel lbl = new JLabel(label);
            lbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
            lbl.setForeground(new Color(226, 226, 234));
            gbc.weightx = 0.3;
            panel.add(lbl, gbc);
            gbc.gridx = 1;
            gbc.weightx = 0.7;
            panel.add(comp, gbc);
        } else {
            gbc.gridwidth = 2;
            gbc.weightx = 1.0;
            panel.add(comp, gbc);
            gbc.gridwidth = 1;
        }
        gbc.gridy++;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CONSOLE LOG AREA
    // ══════════════════════════════════════════════════════════════════════════
    private JPanel buildLogArea() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new EmptyBorder(10, 16, 10, 16));

        // Console area
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setRows(6);
        logArea.setBackground(new Color(6, 6, 8));
        logArea.setForeground(new Color(226, 226, 234));
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));

        JScrollPane scroll = new JScrollPane(logArea);
        p.add(scroll, BorderLayout.CENTER);

        // Control Panel
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        statusLabel = new JLabel("Ready.");
        statusLabel.setForeground(new Color(156, 163, 175));

        JButton clearBtn = new JButton("Clear Console");
        clearBtn.addActionListener(e -> logArea.setText(""));

        killButton = new JButton("Kill Game");
        killButton.setEnabled(false);
        killButton.setBackground(new Color(239, 68, 68));
        killButton.setForeground(Color.WHITE);
        killButton.addActionListener(e -> {
            if (activeProcess != null && activeProcess.isAlive()) {
                activeProcess.destroyForcibly();
                notifications.warning("Game terminated", "Minecraft process killed.");
            }
        });

        controls.add(statusLabel);
        controls.add(clearBtn);
        controls.add(killButton);
        p.add(controls, BorderLayout.SOUTH);

        return p;
    }

    private void refreshAccounts() {
        accountBox.removeAllItems();
        for (Account a : accountManager.getAccounts()) {
            accountBox.addItem(a);
        }
        accountManager.getActiveAccount().ifPresent(accountBox::setSelectedItem);
    }

    private void refreshInstances() {
        boolean showHidden = com.launcher.manager.SettingsManager.getInstance().getSettings().showHiddenInstances;
        instanceListModel.clear();
        for (Instance i : instanceManager.getInstances()) {
            if (showHidden || !i.hidden) {
                instanceListModel.addElement(i);
            }
        }
        refreshDiscoverInstances();
    }

    // ─── Dawn Install / Delete ────────────────────────────────────────────────
    private void updateDawnStatus(Instance inst) {
        if (dawnStatusLabel == null) return;
        if (inst == null) {
            dawnStatusLabel.setText("Dawn client: —");
            installDawnButton.setEnabled(false);
            deleteDawnButton.setEnabled(false);
            return;
        }
        Path jar = instanceManager.resolveGameDir(inst).resolve("mods").resolve(DAWN_JAR_NAME);
        if (Files.exists(jar)) {
            dawnStatusLabel.setText("Dawn client: Installed");
            installDawnButton.setEnabled(false);
            deleteDawnButton.setEnabled(true);
        } else {
            dawnStatusLabel.setText("Dawn client: Not Installed");
            installDawnButton.setEnabled(true);
            deleteDawnButton.setEnabled(false);
        }
    }

    private void installDawn(Instance inst) {
        installDawnButton.setEnabled(false);
        setStatus("Downloading Dawn standalone client…");
        new Thread(() -> {
            try {
                Path mods = instanceManager.resolveGameDir(inst).resolve("mods");
                Files.createDirectories(mods);
                Path jar = mods.resolve(DAWN_JAR_NAME);
                com.launcher.util.HttpUtil.downloadFile(DAWN_DOWNLOAD_URL, jar, msg -> SwingUtilities.invokeLater(() -> setStatus(msg)));
                SwingUtilities.invokeLater(() -> {
                    notifications.success("Dawn client installed", "Standalone JAR placed in mods.");
                    updateDawnStatus(inst);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    notifications.error("Dawn install failed", ex.getMessage());
                    updateDawnStatus(inst);
                });
            }
        }, "dawn-install").start();
    }

    private void uninstallDawn(Instance inst) {
        try {
            Path jar = instanceManager.resolveGameDir(inst).resolve("mods").resolve(DAWN_JAR_NAME);
            Files.deleteIfExists(jar);
            notifications.warning("Dawn client removed", "Removed Standalone JAR.");
            updateDawnStatus(inst);
        } catch (Exception ex) {
            notifications.error("Uninstall failed", ex.getMessage());
        }
    }

    // ─── Launcher Game Launch Thread ──────────────────────────────────────────
    private void launchGame(Instance instance, Account account) {
        setStatus("Preparing files…");
        log("----------------------------------------------------------------");
        log("Launching " + instance.name + " (" + instance.mcVersion + ") as " + account.username);
        log("----------------------------------------------------------------");

        new Thread(() -> {
            try {
                Path gameDir = instanceManager.resolveGameDir(instance);
                Path nativesDir = instanceManager.resolveNativesDir(instance);
                Files.createDirectories(gameDir);
                Files.createDirectories(nativesDir);

                GameInstaller installer = new GameInstaller();
                VersionManifestService manifestService = new VersionManifestService();
                JsonObject versionJson = null;

                if (instance.modLoader == ModLoaderType.VANILLA) {
                    Path localJson = LauncherPaths.findLocalVersionJson(instance.mcVersion, gameDir);
                    if (localJson != null) {
                        log("Loading local version JSON for " + instance.mcVersion + "…");
                        try {
                            versionJson = JsonUtil.parse(Files.readString(localJson)).getAsJsonObject();
                        } catch (Exception ex) {
                            log("Local JSON unreadable, fetching from network.");
                        }
                    }
                    if (versionJson == null) {
                        log("Fetching vanilla version " + instance.mcVersion + "…");
                        SwingUtilities.invokeLater(() -> setStatus("Fetching version data…"));
                        var urls = manifestService.fetchVersionUrls();
                        String url = urls.get(instance.mcVersion);
                        if (url == null) throw new RuntimeException("Unknown version: " + instance.mcVersion);
                        versionJson = manifestService.fetchVersionJson(url);
                    }

                } else if (instance.modLoader == ModLoaderType.FABRIC) {
                    log("Fetching Fabric profile " + instance.modLoaderVersion + "…");
                    SwingUtilities.invokeLater(() -> setStatus("Fetching Fabric profile…"));
                    versionJson = new FabricInstaller().fetchProfileJson(instance.mcVersion, instance.modLoaderVersion);

                } else if (instance.modLoader == ModLoaderType.QUILT) {
                    log("Fetching Quilt profile " + instance.modLoaderVersion + "…");
                    SwingUtilities.invokeLater(() -> setStatus("Fetching Quilt profile…"));
                    versionJson = new QuiltInstaller().fetchProfileJson(instance.mcVersion, instance.modLoaderVersion);

                } else if (instance.modLoader == ModLoaderType.NEOFORGE) {
                    log("Installing NeoForge " + instance.modLoaderVersion + "…");
                    SwingUtilities.invokeLater(() -> setStatus("Installing NeoForge…"));
                    NeoForgeInstaller nfi = new NeoForgeInstaller();
                    String vid = nfi.installClient(instance.mcVersion, instance.modLoaderVersion, gameDir, this::log);
                    versionJson = nfi.loadGeneratedVersionJson(gameDir, vid);

                } else {
                    // FORGE
                    ForgeInstaller fi = new ForgeInstaller();
                    String fv = instance.modLoaderVersion;
                    if ("Recommended".equals(fv) || "Latest".equals(fv)) {
                        fv = fi.fetchPromotedLatest(instance.mcVersion, "Recommended".equals(fv));
                    }
                    String vid = fi.installClient(instance.mcVersion, fv, gameDir, this::log);
                    versionJson = fi.loadGeneratedVersionJson(gameDir, vid);
                }

                SwingUtilities.invokeLater(() -> setStatus("Resolving dependencies…"));
                JsonObject merged = installer.resolveInheritance(versionJson, this::log);
                log("Downloading/verifying files…");
                SwingUtilities.invokeLater(() -> setStatus("Installing files…"));
                ResolvedVersion resolved = installer.installAndResolve(merged, nativesDir, this::log);

                instance.installed = true;
                instanceManager.save();
                SwingUtilities.invokeLater(() -> instanceList.repaint());

                log("Launching Minecraft in separate window…");
                SwingUtilities.invokeLater(() -> setStatus("Running " + instance.name));

                GameLauncher launcher = new GameLauncher();
                Process process = launcher.launch(instance, gameDir, nativesDir, resolved, account, this::log);
                this.activeProcess = process;

                SwingUtilities.invokeLater(() -> {
                    killButton.setEnabled(true);
                    com.launcher.model.LauncherSettings cs = com.launcher.manager.SettingsManager.getInstance().getSettings();
                    if (cs.minimizeOnLaunch) {
                        setExtendedState(JFrame.ICONIFIED);
                    }
                    if (com.launcher.manager.SettingsManager.getInstance().getSettings().closeAfterLaunch) {
                        System.exit(0);
                    }
                });
                System.gc();

                com.launcher.manager.DiscordRpcManager.getInstance().updatePlaying(instance, resolved.id);
                try (var reader = process.inputReader()) {
                    String line;
                    java.util.regex.Pattern serverRegex = java.util.regex.Pattern.compile("Connecting to (\\S+),");
                    while ((line = reader.readLine()) != null) {
                        log("[game] " + line);
                        java.util.regex.Matcher matcher = serverRegex.matcher(line);
                        if (matcher.find()) {
                            String serverIp = matcher.group(1);
                            instance.lastServerIp = serverIp;
                            instance.lastServerConnectedAt = System.currentTimeMillis();
                            instanceManager.save();
                            com.launcher.manager.DiscordRpcManager.getInstance().updatePlayingServer(instance, resolved.id, serverIp);
                        }
                    }
                }
                int exit = process.waitFor();
                log("Minecraft exited with code " + exit);
                SwingUtilities.invokeLater(() -> setStatus(exit == 0 ? "Game closed normally." : "Game exited (code " + exit + ")"));
                com.launcher.manager.DiscordRpcManager.getInstance().updateIdle();

            } catch (Exception ex) {
                log("ERROR: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> setStatus("Launch failed — see console."));
            } finally {
                this.activeProcess = null;
                SwingUtilities.invokeLater(() -> {
                    playButton.setEnabled(true);
                    killButton.setEnabled(false);
                    setExtendedState(JFrame.NORMAL);
                    toFront();
                });
                System.gc();
            }
        }, "launch-thread").start();
    }

    private static double blurAlpha(com.launcher.model.LauncherSettings settings) {
        int s = settings.blurStrength <= 0 ? 10 : settings.blurStrength;
        s = Math.min(40, Math.max(1, s));
        return Math.max(0.30, 1.0 - (s / 40.0) * 0.6);
    }

    private void applyTheme() {
        com.launcher.model.LauncherSettings settings = com.launcher.manager.SettingsManager.getInstance().getSettings();
        Color bg = hexToColor(settings.bgColor, new Color(10, 10, 15));
        
        if (settings.enableBlurEffect) {
            double alpha = blurAlpha(settings);
            Color translucentBg = new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), (int) (alpha * 255));
            getContentPane().setBackground(translucentBg);
            
            // Set window opacity for the decorated frame if supported
            try {
                setOpacity((float) Math.min(1.0f, Math.max(0.60f, alpha)));
            } catch (Exception ignored) {}
        } else {
            getContentPane().setBackground(bg);
            try {
                setOpacity(1.0f);
            } catch (Exception ignored) {}
        }
        
        // Ensure child components are non-opaque if transparent is on, so background shows through
        setComponentTranslucent(getContentPane(), settings.enableBlurEffect);
        repaint();
    }

    private void setComponentTranslucent(Component comp, boolean transparent) {
        if (comp instanceof JPanel panel) {
            // Let text fields, lists, buttons keep their opacity for readability
            if (panel.getClass() == JPanel.class || panel.getLayout() instanceof BoxLayout || panel.getLayout() instanceof GridBagLayout || panel.getLayout() instanceof BorderLayout) {
                panel.setOpaque(!transparent);
            }
        } else if (comp instanceof JScrollPane scroll) {
            scroll.setOpaque(!transparent);
            scroll.getViewport().setOpaque(!transparent);
        } else if (comp instanceof JTabbedPane tab) {
            tab.setOpaque(!transparent);
        }
        if (comp instanceof Container container) {
            for (Component child : container.getComponents()) {
                setComponentTranslucent(child, transparent);
            }
        }
    }

    private void setStatus(String msg) {
        SwingUtilities.invokeLater(() -> {
            if (statusLabel != null) statusLabel.setText(msg);
        });
    }

    private void log(String msg) {
        logQueue.add(sanitizePrivacy(msg));
        scheduleLogFlush();
    }

    private void scheduleLogFlush() {
        if (logFlushScheduled) return;
        if (isMinimized) {
            long now = System.currentTimeMillis();
            if (now - lastLogFlushWhenMinimized < 2000) return;
            lastLogFlushWhenMinimized = now;
        }
        logFlushScheduled = true;
        SwingUtilities.invokeLater(() -> {
            logFlushScheduled = false;
            StringBuilder sb = new StringBuilder();
            String line; int count = 0;
            while ((line = logQueue.poll()) != null) {
                sb.append(line).append("\n");
                if (++count > 400) break;
            }
            if (sb.length() > 0) {
                logArea.append(sb.toString());
                String text = logArea.getText();
                if (text.length() > 30000) {
                    logArea.setText(text.substring(text.length() - 20000));
                }
                logArea.setCaretPosition(logArea.getText().length());
            }
            if (!logQueue.isEmpty()) scheduleLogFlush();
        });
    }

    private String sanitizePrivacy(String message) {
        if (message == null) return "";
        com.launcher.model.LauncherSettings s = com.launcher.manager.SettingsManager.getInstance().getSettings();
        return s.redactPaths ? message.replace(System.getProperty("user.name"), "unnamed_user") : message;
    }

    public static Color hexToColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        try {
            return Color.decode(hex);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(new FlatDarkLaf());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> {
            new Main().setVisible(true);
        });
    }
}
