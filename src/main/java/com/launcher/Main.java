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
import com.launcher.ui.CustomSpinner;
import com.launcher.ui.CustomTextField;
import com.launcher.ui.CustomToggle;
import com.launcher.ui.NotificationCenter;
import com.launcher.ui.WrapLayout;
import com.launcher.ui.CreateInstancePanel;
import com.launcher.ui.EditInstancePanel;
import com.launcher.util.JsonUtil;
import com.launcher.manager.LauncherPaths;
import com.formdev.flatlaf.FlatDarkLaf;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Point2D;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class Main extends JFrame {

    private final AccountManager accountManager = new AccountManager();

    /**
     * The currently active launcher window, if any — used so a second launch
     * attempt can ask
     * this instance to bring itself to the front instead of opening a duplicate
     * window.
     */
    private static volatile Main activeInstance;
    private final InstanceManager instanceManager = new InstanceManager();

    private NotificationCenter notifications;
    /**
     * Fixed width/height reserved for the notification stack — see the setup
     * comment where it's used.
     */
    private static final int NOTIF_AREA_WIDTH = 450;
    private static final int NOTIF_AREA_HEIGHT = 700;
    private static final int LOG_TOGGLE_SIZE = 34;
    private static final int DOWNLOAD_TOGGLE_HEIGHT = 40;

    private final ConcurrentLinkedQueue<String> logQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean logFlushScheduled = false;
    private volatile boolean isMinimized = false;
    private volatile boolean wasMaximizedBeforeHide = false;
    /**
     * Last known bounds while the window was in plain NORMAL state (not
     * maximized/minimized).
     */
    private Rectangle normalBounds;
    /**
     * True while the user is actively dragging a resize handle or the title bar.
     */
    private volatile boolean userAdjustingWindow = false;
    private long lastLogFlushWhenMinimized = 0;

    // Shared corner radius for the PLAY button.
    private static final int PLAY_BUTTON_ARC = 14;
    // Fully pill-shaped corner radius — used on Install Dawn Client / Uninstall and
    // the
    // whole Mods toolbar (Refresh, Check Updates, Update All, Update Selected,
    // Install Deps,
    // Deduplicate, Delete) for a noticeably rounded, capsule-shaped button look.
    private static final int ROUNDED_BUTTON_ARC = 999;

    private JComboBox<Account> accountBox;
    private JButton accountBtn;
    private RoundedPanel accountDropdown;
    private RoundedPanel createAccountPopover;
    private CustomTextField createAccountUsernameField;
    private JLabel createAccountErrorLabel;
    private JList<Instance> instanceList;
    private DefaultListModel<Instance> instanceListModel;
    private JTextPane logArea;
    // Cached style attributes for the colorized console — recomputed whenever theme
    // colors
    // change (see restyle section) so log text stays legible/on-theme across
    // accent/text edits.
    private SimpleAttributeSet logAttrDefault, logAttrBracket, logAttrError, logAttrWarn, logAttrDebug, logAttrInfo;
    private JButton playButton;
    private JPanel logAreaPanel;
    private RoundedPanel logCard;
    private JButton clearLogBtn;
    private RoundedPanel logToggleWrap;
    private RoundedPanel downloadsToggleWrap;
    private RoundedPanel downloadsPopover;
    private JPanel downloadsListPanel;
    private RoundedPanel killInstancesPopover;
    private JPanel killInstancesListPanel;
    private javax.swing.Timer downloadsRefreshTimer;
    private boolean downloadsToggleShown = false;
    private JButton killInstanceButton;
    private JButton offlinePlayButton;

    // ─── Multi-instance tracking ─────────────────────────────────────────────
    /**
     * Represents a single running Minecraft process so we can track multiple
     * simultaneous launches.
     */
    private record RunningInstance(String id, String label, Instance instance, Process process) {
    }

    private final List<RunningInstance> runningInstances = new CopyOnWriteArrayList<>();

    public boolean isAnyGameRunning() {
        return !runningInstances.isEmpty();
    }

    // CardLayout for switching views
    private CardLayout cardLayout;
    private JPanel cardPanel;
    private static final String MAIN_VIEW = "MainView";
    private static final String CREATE_INSTANCE_VIEW = "CreateInstanceView";
    private static final String EDIT_INSTANCE_VIEW = "EditInstanceView";

    // ─── Status bar ───────────────────────────────────────────────────────────
    private JLabel statusLabel;

    // ─── Dawn client ──────────────────────────────────────────────────────────
    private JLabel dawnStatusLabel;
    private JButton installDawnButton;
    private RoundedPanel dawnCard;
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
    private JButton fixModsBtn;
    private RoundedPanel fixModsDropdown;
    private List<ModEntry> currentModEntries = new ArrayList<>();

    // ─── Export / Import Mods ─────────────────────────────────────────────────
    private JButton exportModsBtn;
    private JButton importModsBtn;
    private RoundedPanel exportOverlay;
    private RoundedPanel importOverlay;

    private final Map<String, ImageIcon> modIconCache = new ConcurrentHashMap<>();
    private ImageIcon defaultModIcon;
    private ImageIcon defaultModIcon24;
    private ImageIcon defaultModIcon48;
    private ImageIcon dawnClientIcon32;

    // ─── Discover tab (Modrinth browser) ───────────────────────────────────────

    private JToggleButton discoverModsToggle;
    private JToggleButton discoverPacksToggle;
    private JTextField discoverSearchField;
    private RoundedPanel discoverSearchWrapPanel;
    private JButton discoverSearchBtn;
    private JPanel discoverResultsPane;
    private final Map<String, ImageIcon> discoverIconCache = new ConcurrentHashMap<>();
    /**
     * URLs that failed to load/decode once (e.g. WebP images Java can't decode,
     * blocked
     * requests, timeouts) — remembered so we don't keep retrying every re-render.
     */
    private final Set<String> iconLoadFailures = ConcurrentHashMap.newKeySet();
    private int discoverOffset = 0;
    private int discoverTotalHits = 0;
    private JLabel discoverPageLabel;
    private JButton discoverPrevPageBtn;
    private JButton discoverNextPageBtn;
    private JButton discoverRefreshBtn;
    // Extra refs kept so the Discover tab's palette can be re-applied live when the
    // user changes Accent/Background/Text colors in Settings — previously this tab
    // was hardcoded and completely ignored theme changes.
    private JPanel discoverRootPanel;
    private RoundedPanel discoverFiltersPanel;
    private JScrollPane discoverScrollPane;
    private JLabel discoverTitleLbl;
    private JLabel discoverSubtitleLbl;
    private final ModUpdateService discoverModService = new ModUpdateService();

    private record VersionOption(String label, String url, String fileName, boolean matchesTarget, boolean failed) {
        @Override
        public String toString() {
            return label;
        }
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
        @Override
        public String toString() {
            return instance.name + "  ›  " + entry.name + "  (" + entry.ip + ")";
        }
    }

    private PillTabbedPane mainTabPane;
    private com.launcher.ui.CustomTitleBar customTitleBar;
    private GradientBackgroundPane layeredPane;
    private JLabel logoSub; // the small "LAUNCHER" wordmark under the "ZERO" logo — tinted with the accent
                            // color
    private static final int RESIZE_MARGIN = 5;

    public Main() {
        activeInstance = this;

        setTitle("Zero Launcher");
        setMinimumSize(new Dimension(820, 560));
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        com.launcher.model.LauncherSettings initSettings = com.launcher.manager.SettingsManager.getInstance()
                .getSettings();
        // Set default width and height to 1400x800
        int initW = (initSettings.launcherWidth >= 820) ? initSettings.launcherWidth : 1400;
        int initH = (initSettings.launcherHeight >= 560) ? initSettings.launcherHeight : 800;

        // Must be set before the frame becomes displayable, so this has to
        // happen here rather than later on in the constructor.
        setUndecorated(initSettings.useCustomTitleBar);
        if (initSettings.useCustomTitleBar) {
            setMaximizedBounds(GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds());
        }

        setSize(initW, initH);
        setLocationRelativeTo(null);
        normalBounds = getBounds();

        // Always launch maximized when the user has this setting enabled (default: on).
        if (initSettings.startMaximized) {
            setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);
        }

        // Load default window icon
        Image windowIcon = null;
        try {
            URL iconUrl = getClass().getResource("/com/launcher/ZeroLauncherIcon.png");
            if (iconUrl != null) {
                windowIcon = new ImageIcon(iconUrl).getImage();
                setIconImage(windowIcon);
            }
        } catch (Exception ignored) {
        }

        com.launcher.model.LauncherSettings settings = com.launcher.manager.SettingsManager.getInstance().getSettings();
        if (settings.enableSystemTray) {
            dorkbox.systemTray.SystemTray systemTray = dorkbox.systemTray.SystemTray.get();
            URL trayUrl = null;
            try {
                trayUrl = getClass().getResource("/com/launcher/Tray_icon.png");
                if (trayUrl == null) {
                    trayUrl = getClass().getResource("/com/launcher/ZeroLauncherIcon.png");
                }
            } catch (Exception ignored) {
            }
            if (systemTray != null && trayUrl != null) {
                try {
                    systemTray.setImage(trayUrl);
                    systemTray.setTooltip("Zero Launcher");

                    systemTray.getMenu().add(new dorkbox.systemTray.MenuItem("Show Launcher", e -> {
                        SwingUtilities.invokeLater(this::restoreFromTray);
                    }));

                    systemTray.getMenu().add(new dorkbox.systemTray.Separator());

                    systemTray.getMenu().add(new dorkbox.systemTray.MenuItem("Exit", e -> {
                        com.launcher.manager.DiscordRpcManager.getInstance().shutdown();
                        System.exit(0);
                    }));
                } catch (Exception e) {
                    System.err.println("Could not add tray icon: " + e.getMessage());
                }
            }
        }

        // Load default mod icon (scaled down — the source asset is 1024x1024 and must
        // never be assigned to a label at full size, or it renders as a giant image).
        try {
            InputStream is = getClass().getResourceAsStream("/com/launcher/minecraft_image.png");
            if (is != null) {
                Image raw = ImageIO.read(is);
                defaultModIcon = new ImageIcon(raw.getScaledInstance(32, 32, Image.SCALE_SMOOTH));
                defaultModIcon24 = new ImageIcon(raw.getScaledInstance(24, 24, Image.SCALE_SMOOTH));
                defaultModIcon48 = new ImageIcon(raw.getScaledInstance(48, 48, Image.SCALE_SMOOTH));
            }
        } catch (Exception ignored) {
        }

        // Load the Dawn Client icon, used to special-case the Dawn standalone mod jar
        // in the
        // mods list so it shows a proper icon/name instead of the raw jar filename.
        try {
            InputStream is = getClass().getResourceAsStream("/com/launcher/dawn_client_icon.png");
            if (is != null) {
                Image raw = ImageIO.read(is);
                dawnClientIcon32 = new ImageIcon(raw.getScaledInstance(32, 32, Image.SCALE_SMOOTH));
            }
        } catch (Exception ignored) {
        }

        // Setup Main Layout
        JPanel rootPanel = new JPanel(new BorderLayout());

        rootPanel.add(buildTopBar(), BorderLayout.NORTH);

        mainTabPane = new PillTabbedPane();
        mainTabPane.addTab(" Instances", buildInstanceArea());
        mainTabPane.addTab(" Mods", buildModsArea());
        mainTabPane.addTab(" Discover", buildDiscoverArea());
        mainTabPane.addTab(" Settings", buildSettingsArea());
        rootPanel.add(mainTabPane, BorderLayout.CENTER);

        logAreaPanel = buildLogArea();
        logAreaPanel.setVisible(com.launcher.manager.SettingsManager.getInstance().getSettings().logConsoleVisible);
        rootPanel.add(logAreaPanel, BorderLayout.SOUTH);

        // Setup CardLayout
        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);

        JPanel mainContentPanel; // This will hold either rootPanel or marginPanel

        // Add main content to cardPanel
        if (initSettings.useCustomTitleBar) {
            customTitleBar = new com.launcher.ui.CustomTitleBar(this, "Zero Launcher", windowIcon);

            JPanel decorated = new JPanel(new BorderLayout());
            decorated.add(customTitleBar, BorderLayout.NORTH);
            decorated.add(rootPanel, BorderLayout.CENTER);

            // A thin margin panel around everything is what gives an undecorated
            // frame its resize handles back — see WindowResizer's class comment.
            JPanel marginPanel = new JPanel(new BorderLayout());
            marginPanel.setBorder(new EmptyBorder(RESIZE_MARGIN, RESIZE_MARGIN, RESIZE_MARGIN, RESIZE_MARGIN));
            marginPanel.add(decorated, BorderLayout.CENTER);
            mainContentPanel = marginPanel; // Assign marginPanel to mainContentPanel
            new com.launcher.ui.WindowResizer(this, marginPanel, RESIZE_MARGIN);
        } else {
            mainContentPanel = rootPanel; // Assign rootPanel to mainContentPanel
        }
        cardPanel.add(mainContentPanel, MAIN_VIEW); // Add the main content to the cardPanel

        // Add CreateInstancePanel to cardPanel
        CreateInstancePanel createInstancePanel = new CreateInstancePanel(
                inst -> { // On create
                    instanceManager.add(inst);
                    instanceManager.save();
                    refreshInstances();
                    notifications.success("Instance created", "Created: " + inst.name);
                    cardLayout.show(cardPanel, MAIN_VIEW); // Switch back to main view

                    Account activeAccount = (Account) accountBox.getSelectedItem();
                    if (activeAccount != null) {
                        launchGame(inst, activeAccount, true);
                    }
                },
                () -> { // On cancel
                    cardLayout.show(cardPanel, MAIN_VIEW); // Switch back to main view
                });
        cardPanel.add(createInstancePanel, CREATE_INSTANCE_VIEW);

        // Initialize NotificationCenter
        notifications = new NotificationCenter();

        // Setup JLayeredPane — a soft gradient backdrop instead of a flat color, for a
        // more
        // modern feel in any gaps around the UI (e.g. the resize margin in undecorated
        // mode).
        layeredPane = new GradientBackgroundPane();
        layeredPane.setPreferredSize(new Dimension(initW, initH)); // Set initial size

        // Now that the backdrop pane exists, wire the Dawn Client card's frosted-glass
        // interior to blur a live crop of it.
        if (dawnCard != null) {
            dawnCard.setFrostedGlass(layeredPane, 8, new Color(0xDE, 0x80, 0x47, 30));
        }
        wireFrostedGlassBackdrops();

        // Add cardPanel to the default layer
        layeredPane.add(cardPanel, JLayeredPane.DEFAULT_LAYER);
        cardPanel.setBounds(0, 0, initW, initH); // Ensure cardPanel fills the layeredPane

        // Add notifications panel to a higher layer
        layeredPane.add(notifications, JLayeredPane.PALETTE_LAYER);
        // Position notifications panel (e.g., top-right, with some padding).
        // NOTE: the notification area is given a fixed, generous height
        // (NOTIF_AREA_HEIGHT)
        // rather than being sized to notifications.getPreferredSize(). The stack's
        // preferred
        // size only changes when a toast is added/removed, which does NOT fire a resize
        // on
        // layeredPane — so if we sized this to the (empty, zero-height) preferred size
        // at
        // startup, every future toast would render outside the container's bounds and
        // be
        // clipped away invisibly. Reserving a fixed area sidesteps that entirely; the
        // panel
        // is non-opaque so the unused space is invisible when there's nothing to show.
        notifications.setBounds(initW - NOTIF_AREA_WIDTH - 16, 60, NOTIF_AREA_WIDTH, NOTIF_AREA_HEIGHT); // Initial
                                                                                                         // position

        // Floating "show/hide log" button, bottom-left corner — kept in its own layer
        // so it
        // stays reachable even while the log panel itself is collapsed.
        logToggleWrap = buildTerminalToggleButton();
        layeredPane.add(logToggleWrap, JLayeredPane.PALETTE_LAYER);
        logToggleWrap.setBounds(16, initH - LOG_TOGGLE_SIZE - 16, LOG_TOGGLE_SIZE, LOG_TOGGLE_SIZE);

        // Floating "downloads in progress" button, top-center — only visible while at
        // least
        // one tracked download is running; opens the downloads dialog when clicked.
        downloadsToggleWrap = buildDownloadsToggleButton();
        layeredPane.add(downloadsToggleWrap, JLayeredPane.PALETTE_LAYER);
        repositionDownloadsToggle();
        downloadsToggleWrap.setVisible(com.launcher.manager.DownloadManager.getInstance().hasActive());
        com.launcher.manager.DownloadManager.getInstance()
                .addListener(() -> SwingUtilities.invokeLater(this::refreshDownloadsToggleVisibility));

        // Downloads popover — an in-window panel (not a separate popup window) anchored
        // just below the toggle button. Sits above everything else in the modal layer.
        downloadsPopover = buildDownloadsPopover();
        layeredPane.add(downloadsPopover, JLayeredPane.MODAL_LAYER);
        downloadsPopover.setVisible(false);
        positionDownloadsPopover();

        killInstancesPopover = buildKillInstancesPopover();
        layeredPane.add(killInstancesPopover, JLayeredPane.MODAL_LAYER);
        killInstancesPopover.setVisible(false);
        positionKillInstancesPopover();

        // Ticks twice a second so the toggle pill's label/width and the popover's
        // progress
        // bars stay live while downloads are running, without waiting for the next
        // explicit
        // DownloadManager change event.
        downloadsRefreshTimer = new javax.swing.Timer(500, e -> {
            repositionDownloadsToggle();
            refreshDownloadsToggleVisibility();
            if (downloadsPopover.isVisible()) {
                positionDownloadsPopover();
            }
        });
        downloadsRefreshTimer.start();

        // Loading overlay — shown immediately, dismissed once data is ready.
        // Supports fade-in / fade-out via an "overlayAlpha" client property that
        // controls the AlphaComposite used to paint the entire panel.
        JPanel loadingOverlay = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Apply overall alpha for fade-in / fade-out
                Object alphaObj = getClientProperty("overlayAlpha");
                float alpha = (alphaObj instanceof Number) ? ((Number) alphaObj).floatValue() : 1.0f;
                g2.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, alpha))));
                g2.setColor(new Color(28, 28, 35));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }

            @Override
            public void paint(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                Object alphaObj = getClientProperty("overlayAlpha");
                float alpha = (alphaObj instanceof Number) ? ((Number) alphaObj).floatValue() : 1.0f;
                g2.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, alpha))));
                super.paint(g2);
                g2.dispose();
            }
        };
        loadingOverlay.setOpaque(false);

        JPanel loadingCard = new JPanel();
        loadingCard.setLayout(new BoxLayout(loadingCard, BoxLayout.Y_AXIS));
        loadingCard.setOpaque(false);

        JLabel loadingIcon = new JLabel();
        loadingIcon.setHorizontalAlignment(SwingConstants.CENTER);
        loadingIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
        try {
            URL loadIconUrl = getClass().getResource("/com/launcher/ZeroLauncherIcon.png");
            if (loadIconUrl != null) {
                java.awt.image.BufferedImage rawImg = ImageIO.read(loadIconUrl);
                Image scaledImg = rawImg.getScaledInstance(100, 100, Image.SCALE_SMOOTH);
                loadingIcon.setIcon(new ImageIcon(scaledImg));
            }
        } catch (Exception ignored) {
        }

        JLabel loadingTitle = new JLabel("Zero Launcher", SwingConstants.CENTER);
        loadingTitle.setFont(new Font("SansSerif", Font.BOLD, 26));
        loadingTitle.setForeground(Color.WHITE);
        loadingTitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel loadingStatus = new JLabel("Loading...", SwingConstants.CENTER);
        loadingStatus.setFont(new Font("SansSerif", Font.PLAIN, 14));
        loadingStatus.setForeground(new Color(156, 163, 175));
        loadingStatus.setAlignmentX(Component.CENTER_ALIGNMENT);

        // Spinning arc indicator — draws a rotating gradient arc below the status text.
        JComponent spinner = new JComponent() {
            private float angle = 0f;

            {
                setPreferredSize(new Dimension(48, 48));
                setMaximumSize(new Dimension(48, 48));
                setMinimumSize(new Dimension(48, 48));
                javax.swing.Timer spinTimer = new javax.swing.Timer(16, e -> {
                    angle += 6f;
                    if (angle >= 360f) angle -= 360f;
                    repaint();
                });
                spinTimer.start();
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int size = Math.min(getWidth(), getHeight());
                int pad = 4;
                g2.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                // Draw a faint track circle
                g2.setColor(new Color(255, 255, 255, 25));
                g2.drawArc(pad, pad, size - pad * 2, size - pad * 2, 0, 360);
                // Draw the spinning arc with accent color gradient
                g2.setColor(new Color(16, 185, 129)); // accent green
                g2.drawArc(pad, pad, size - pad * 2, size - pad * 2, (int) angle, 90);
                // Draw a smaller secondary arc for visual interest
                g2.setColor(new Color(16, 185, 129, 90));
                g2.drawArc(pad, pad, size - pad * 2, size - pad * 2, (int) angle + 180, 60);
                g2.dispose();
            }
        };
        spinner.setAlignmentX(Component.CENTER_ALIGNMENT);

        loadingCard.add(loadingIcon);
        loadingCard.add(Box.createVerticalStrut(14));
        loadingCard.add(loadingTitle);
        loadingCard.add(Box.createVerticalStrut(8));
        loadingCard.add(loadingStatus);
        loadingCard.add(Box.createVerticalStrut(18));
        loadingCard.add(spinner);

        loadingOverlay.add(loadingCard);

        // Start with alpha=0 for fade-in effect
        loadingOverlay.putClientProperty("overlayAlpha", 0.0f);
        layeredPane.add(loadingOverlay, JLayeredPane.POPUP_LAYER);
        loadingOverlay.setBounds(0, 0, initW, initH);

        // Fade-in animation: 0 → 1 over ~300ms
        {
            final long fadeInStart = System.currentTimeMillis();
            final int fadeInDuration = 300;
            javax.swing.Timer fadeInTimer = new javax.swing.Timer(16, null);
            fadeInTimer.addActionListener(ev -> {
                float progress = Math.min(1f, (System.currentTimeMillis() - fadeInStart) / (float) fadeInDuration);
                // Ease-out curve for smooth appearance
                float eased = 1f - (1f - progress) * (1f - progress);
                loadingOverlay.putClientProperty("overlayAlpha", eased);
                loadingOverlay.repaint();
                if (progress >= 1f) fadeInTimer.stop();
            });
            fadeInTimer.start();
        }

        // Pulsing "Loading..." text opacity animation
        {
            final long pulseStart = System.currentTimeMillis();
            javax.swing.Timer pulseTimer = new javax.swing.Timer(40, ev -> {
                float t = ((System.currentTimeMillis() - pulseStart) % 2000) / 2000f;
                // Sine wave pulse between 0.45 and 1.0 opacity
                float opacity = 0.45f + 0.55f * (0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI));
                loadingStatus.setForeground(new Color(156, 163, 175, (int) (opacity * 255)));
            });
            pulseTimer.start();
            // Store for cleanup when overlay is removed
            loadingOverlay.putClientProperty("pulseTimer", pulseTimer);
        }

        // Set the layeredPane as the content pane
        setContentPane(layeredPane);

        // Add a ComponentListener to the layeredPane to update notification position on
        // resize
        layeredPane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                // Update bounds of cardPanel to fill the layeredPane
                cardPanel.setBounds(0, 0, layeredPane.getWidth(), layeredPane.getHeight());

                // Update bounds of notifications panel (fixed size, see note above)
                int x = layeredPane.getWidth() - NOTIF_AREA_WIDTH - 16; // 16px right padding
                int y = 60; // 60px top padding
                notifications.setBounds(x, y, NOTIF_AREA_WIDTH, NOTIF_AREA_HEIGHT);

                // Keep the log toggle button pinned to the bottom-left corner.
                logToggleWrap.setBounds(16, layeredPane.getHeight() - LOG_TOGGLE_SIZE - 16, LOG_TOGGLE_SIZE,
                        LOG_TOGGLE_SIZE);

                // Keep the downloads toggle button pinned top-center.
                repositionDownloadsToggle();
                positionDownloadsPopover();
                positionKillInstancesPopover();
                // Keep loading overlay filling the whole window.
                if (loadingOverlay.isDisplayable()) {
                    loadingOverlay.setBounds(0, 0, layeredPane.getWidth(), layeredPane.getHeight());
                }
            }
        });

        applyTheme();

        // applyTheme() above runs before the frame is displayable (setVisible hasn't
        // been
        // called yet by the caller), so the layered pane's gradient/background-image
        // cache
        // and the content pane's translucent blend get computed against a
        // not-yet-realized
        // component tree. That's what made the background render "goofy" on a fresh
        // restart
        // until the user re-toggled Transparency (which simply re-ran applyTheme()
        // later, once
        // everything was actually on screen). Scheduling a second pass for right after
        // the
        // window is realized fixes it without needing that manual re-toggle.
        SwingUtilities.invokeLater(this::applyTheme);

        com.launcher.manager.DiscordRpcManager.getInstance().init();

        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                if (!runningInstances.isEmpty()) {
                    hideToTray();
                    return;
                }

                com.launcher.model.LauncherSettings s = com.launcher.manager.SettingsManager.getInstance()
                        .getSettings();
                boolean isMax = (getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
                if (s.startMaximized != isMax) {
                    s.startMaximized = isMax;
                    com.launcher.manager.SettingsManager.getInstance().save();
                }

                if (s.clearSessionOnExit) {
                    for (Account acc : accountManager.getAccounts())
                        accountManager.addOrUpdate(acc);
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
        if (!instanceListModel.isEmpty() && instanceList.getSelectedValue() == null) {
            instanceList.setSelectedIndex(0);
        }
        if (instanceList.getSelectedValue() != null) {
            updateDawnStatus(instanceList.getSelectedValue());
            refreshModsView(instanceList.getSelectedValue());
        }

        // Auto-refresh Discover tab with trending content
        refreshDiscoverInstances();
        if (com.launcher.manager.SettingsManager.getInstance().getSettings().refreshDiscoverOnLaunch) {
            SwingUtilities.invokeLater(() -> performDiscoverSearch());
        }

        if (com.launcher.manager.SettingsManager.getInstance().getSettings().checkModUpdatesOnStartup) {
            runStartupModUpdateCheck();
        }

        checkNetworkAndShowOfflineButton();

        // Dismiss the loading overlay with a short fade-out after all data is ready.
        // We schedule this in two invokeLater calls so the window is fully painted
        // first (at least one frame visible) before we start the timer.
        SwingUtilities.invokeLater(() -> {
            // Animate alpha from 1→0 over ~400ms then remove overlay.
            final long fadeOutStart = System.currentTimeMillis();
            final int fadeOutDuration = 400;
            javax.swing.Timer fadeTimer = new javax.swing.Timer(16, null);
            fadeTimer.addActionListener(ev -> {
                float progress = Math.min(1f, (System.currentTimeMillis() - fadeOutStart) / (float) fadeOutDuration);
                // Ease-in curve for smooth disappearance
                float eased = progress * progress;
                float alpha = 1f - eased;
                if (progress >= 1f) {
                    fadeTimer.stop();
                    // Stop the pulsing text timer
                    Object pt = loadingOverlay.getClientProperty("pulseTimer");
                    if (pt instanceof javax.swing.Timer) ((javax.swing.Timer) pt).stop();
                    layeredPane.remove(loadingOverlay);
                    layeredPane.repaint();
                } else {
                    loadingOverlay.putClientProperty("overlayAlpha", alpha);
                    loadingOverlay.repaint();
                }
            });
            fadeTimer.start();
        });

        // Workaround for UI freeze after resizing/maximizing the window
        javax.swing.Timer uiRefreshTimer = new javax.swing.Timer(50, e -> {
            if (!userAdjustingWindow && !isMinimized) {
                revalidate();
                repaint();
            }
        });
        uiRefreshTimer.start();
    }

    public void hideToTray() {
        // Remember whether the window was maximized so restoreFromTray() can
        // bring it back the same way, instead of always snapping to a normal
        // (unmaximized) window.
        wasMaximizedBeforeHide = (getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
        setVisible(false);
        dispose(); // Releases native window resources
        isMinimized = true;
        System.gc(); // Force GC to clean up RAM completely
    }

    public void restoreFromTray() {
        setVisible(true);
        setExtendedState(wasMaximizedBeforeHide ? JFrame.MAXIMIZED_BOTH : JFrame.NORMAL);
        toFront();
        requestFocus();
        isMinimized = false;
    }

    private void checkNetworkAndShowOfflineButton() {
        new Thread(() -> {
            try {
                // Try a quick connection to a Mojang/Minecraft API
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(
                        "https://launchermeta.mojang.com").openConnection();
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                connection.setRequestMethod("HEAD");
                int responseCode = connection.getResponseCode();
                if (responseCode >= 200 && responseCode < 400) {
                    // Success, online
                    SwingUtilities.invokeLater(() -> offlinePlayButton.setVisible(false));
                } else {
                    // Reached but bad status
                    SwingUtilities.invokeLater(() -> offlinePlayButton.setVisible(true));
                }
            } catch (Exception e) {
                // Exception implies no connection (timeout, unknown host, etc)
                SwingUtilities.invokeLater(() -> offlinePlayButton.setVisible(true));
            }
        }, "network-check").start();
    }

    private void runStartupModUpdateCheck() {
        var targets = instanceManager.getInstances().stream().filter(i -> !i.hidden).toList();
        if (targets.isEmpty())
            return;

        new Thread(() -> {
            ModUpdateService service = new ModUpdateService();
            Map<String, Integer> updatesByInstance = new LinkedHashMap<>();
            int scannedInstances = 0;

            for (Instance inst : targets) {
                try {
                    Path modsDir = instanceManager.resolveGameDir(inst).resolve("mods");
                    List<ModEntry> mods = service.scanModsDir(modsDir);
                    if (mods.isEmpty())
                        continue;

                    scannedInstances++;
                    service.identifyMods(mods, msg -> {
                    });
                    String loaderName = inst.modLoader != null && inst.modLoader != ModLoaderType.VANILLA
                            ? inst.modLoader.name().toLowerCase()
                            : null;
                    service.checkUpdates(mods, inst.mcVersion, loaderName, msg -> {
                    });

                    long updatable = mods.stream().filter(m -> "Update available".equals(m.status)).count();
                    if (updatable > 0)
                        updatesByInstance.put(inst.name, (int) updatable);
                } catch (Exception ignored) {
                }
            }

            final int finalScanned = scannedInstances;
            SwingUtilities.invokeLater(() -> {
                if (finalScanned == 0)
                    return;
                if (updatesByInstance.isEmpty()) {
                    notifications.info("Mods up to date",
                            "Checked " + finalScanned + " instance(s) — no mod updates found.");
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (var entry : updatesByInstance.entrySet()) {
                        if (sb.length() > 0)
                            sb.append("\n");
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
    // TOP BAR — account selector + launcher branding
    // ══════════════════════════════════════════════════════════════════════════
    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(255, 255, 255, 18)),
                new EmptyBorder(10, 16, 10, 16)));

        // Branding
        JPanel brand = new JPanel();
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        JLabel logo = new JLabel("ZERO");
        logo.setFont(new Font("SansSerif", Font.BOLD, 17));
        logo.setForeground(Color.WHITE);
        logoSub = new JLabel("LAUNCHER");
        logoSub.setFont(new Font("SansSerif", Font.BOLD, 8));
        logoSub.setForeground(hexToColor(com.launcher.manager.SettingsManager.getInstance().getSettings().accentColor,
                new Color(16, 185, 129)));
        brand.add(logo);
        brand.add(logoSub);
        bar.add(brand, BorderLayout.WEST);

        // Account box panel — accountBox itself is never added to the visible UI; it
        // just stays around as the existing selected-account data model that the rest
        // of the app (launch actions, refreshAccounts, etc.) already reads from.
        JPanel accPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        accPanel.setOpaque(false);
        accPanel.putClientProperty("keepCustomBg", Boolean.TRUE);
        accountBox = new JComboBox<>();
        accountBox.addActionListener(e -> {
            Account a = (Account) accountBox.getSelectedItem();
            if (a != null) {
                accountManager.setActiveAccount(a);
            }
        });

        // ── Account dropdown button (styled like the "Fix Mods" dropdown button) ──
        accountBtn = new JButton(accountButtonLabel());
        accountBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
        accountBtn.setFocusPainted(false);
        accountBtn.setForeground(Color.WHITE);
        accountBtn.setBackground(new Color(255, 255, 255, 30));
        accountBtn.setMargin(new Insets(8, 16, 8, 16));
        accountBtn.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
        accountBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                toggleAccountDropdown();
            }
        });

        accPanel.add(accountBtn);
        bar.add(accPanel, BorderLayout.EAST);

        return bar;
    }

    /** Label shown on the account dropdown button — current account, or a prompt if none. */
    private String accountButtonLabel() {
        com.launcher.model.LauncherSettings s = com.launcher.manager.SettingsManager.getInstance().getSettings();
        return accountManager.getActiveAccount()
                .map(a -> "👤  " + (s.hideUsername ? "●●●●●" : a.username) + "  |  ▾")
                .orElse("👤  No account  |  ▾");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // INSTANCES TAB
    // ══════════════════════════════════════════════════════════════════════════
    private JPanel buildInstanceArea() {
        JPanel p = new JPanel(new BorderLayout());

        // Left List
        instanceListModel = new DefaultListModel<>();
        instanceList = new JList<>(instanceListModel);
        instanceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        instanceList.setOpaque(false);
        instanceList.setFixedCellHeight(64);
        instanceList.setBorder(new EmptyBorder(4, 4, 4, 4));
        instanceList.setCellRenderer(new ListCellRenderer<Instance>() {
            private final RoundedPanel card = new RoundedPanel(14, new Color(255, 255, 255, 10),
                    new Color(255, 255, 255, 18));

            @Override
            public Component getListCellRendererComponent(JList<? extends Instance> list, Instance inst, int index,
                    boolean isSelected, boolean cellHasFocus) {
                var settings = com.launcher.manager.SettingsManager.getInstance().getSettings();
                Color accent = hexToColor(settings.accentColor, new Color(16, 185, 129));
                Color textColor = hexToColor(settings.textColor, new Color(226, 226, 234));

                card.removeAll();
                card.setLayout(new BorderLayout(10, 0));
                card.setBorder(new EmptyBorder(8, 10, 8, 10));
                int cardWidth = list.getWidth() > 0 ? list.getWidth() - 8 : 208;
                card.setPreferredSize(new Dimension(cardWidth, 58));

                if (isSelected) {
                    card.setColors(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 45),
                            new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 200));
                } else {
                    card.setColors(new Color(255, 255, 255, 10), new Color(255, 255, 255, 18));
                }

                ImageIcon icon;
                if (inst.imagePath != null && !inst.imagePath.isBlank()) {
                    File file = new File(inst.imagePath);
                    if (file.exists()) {
                        icon = new ImageIcon(new ImageIcon(file.getAbsolutePath()).getImage().getScaledInstance(36, 36,
                                Image.SCALE_SMOOTH));
                    } else {
                        icon = defaultModIcon != null
                                ? new ImageIcon(defaultModIcon.getImage().getScaledInstance(36, 36, Image.SCALE_SMOOTH))
                                : null;
                    }
                } else {
                    icon = defaultModIcon != null
                            ? new ImageIcon(defaultModIcon.getImage().getScaledInstance(36, 36, Image.SCALE_SMOOTH))
                            : null;
                }
                JLabel iconLbl = new JLabel(icon);
                card.add(iconLbl, BorderLayout.WEST);

                JPanel textCol = new JPanel();
                textCol.setOpaque(false);
                textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
                JLabel nameLine = new JLabel(inst.name);
                nameLine.setFont(new Font("SansSerif", Font.BOLD, 14));
                nameLine.setForeground(isSelected ? textColor
                        : new Color(textColor.getRed(), textColor.getGreen(), textColor.getBlue(), 220));
                JLabel verLine = new JLabel(
                        inst.mcVersion + (inst.modLoader != null ? "  •  " + inst.modLoader.name() : ""));
                verLine.setFont(new Font("SansSerif", Font.PLAIN, 11));
                verLine.setForeground(new Color(textColor.getRed(), textColor.getGreen(), textColor.getBlue(), 150));
                textCol.add(nameLine);
                textCol.add(Box.createVerticalStrut(2));
                textCol.add(verLine);
                card.add(textCol, BorderLayout.CENTER);

                return card;
            }
        });

        JScrollPane listScroll = new JScrollPane(instanceList);
        com.launcher.ui.SmoothScroll.install(listScroll);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.setOpaque(false);
        listScroll.getViewport().setOpaque(false);
        listScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        instanceList.setLayoutOrientation(JList.VERTICAL);
        instanceList.setVisibleRowCount(-1);

        // West panel: header with "New Instance" action + the list below it
        JPanel westPanel = new JPanel(new BorderLayout(0, 8));
        westPanel.setPreferredSize(new Dimension(240, 0));
        westPanel.setMinimumSize(new Dimension(240, 0));
        westPanel.setMaximumSize(new Dimension(240, Integer.MAX_VALUE));
        westPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JButton newInstanceBtn = new JButton("+  New Instance");
        newInstanceBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
        newInstanceBtn.setForeground(Color.WHITE);
        newInstanceBtn.setBackground(new Color(16, 185, 129));
        newInstanceBtn.setFocusPainted(false);
        newInstanceBtn.setMargin(new Insets(10, 10, 10, 10));
        newInstanceBtn.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
        newInstanceBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        newInstanceBtn.addActionListener(e -> {
            cardLayout.show(cardPanel, CREATE_INSTANCE_VIEW); // Switch to CreateInstancePanel
        });
        westPanel.add(newInstanceBtn, BorderLayout.NORTH);
        westPanel.add(listScroll, BorderLayout.CENTER);

        p.add(westPanel, BorderLayout.WEST);

        // Center Panel: Selected Instance Info
        JPanel detailsPanel = new JPanel(new BorderLayout());
        detailsPanel.setBorder(new EmptyBorder(16, 16, 16, 16));

        JPanel centerInfo = new JPanel();
        centerInfo.setOpaque(false);
        centerInfo.setLayout(new BoxLayout(centerInfo, BoxLayout.Y_AXIS));

        // ── Header card: icon + name + quick badges ─────────────────────────
        RoundedPanel headerCard = new RoundedPanel(16, new Color(255, 255, 255, 10), new Color(255, 255, 255, 18));
        headerCard.setLayout(new BorderLayout(14, 0));
        headerCard.setBorder(new EmptyBorder(16, 18, 16, 18));
        headerCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));

        JLabel headerIconLbl = new JLabel();
        headerIconLbl.setPreferredSize(new Dimension(48, 48));
        headerCard.add(headerIconLbl, BorderLayout.WEST);

        JPanel headerTextCol = new JPanel();
        headerTextCol.setOpaque(false);
        headerTextCol.setLayout(new BoxLayout(headerTextCol, BoxLayout.Y_AXIS));
        JLabel nameLbl = new JLabel("No instance selected");
        nameLbl.setFont(new Font("SansSerif", Font.BOLD, 20));
        nameLbl.setForeground(Color.WHITE);
        JLabel versionBadge = new JLabel(" ");
        versionBadge.setFont(new Font("SansSerif", Font.PLAIN, 12));
        headerTextCol.add(nameLbl);
        headerTextCol.add(Box.createVerticalStrut(4));
        headerTextCol.add(versionBadge);
        headerCard.add(headerTextCol, BorderLayout.CENTER);

        centerInfo.add(headerCard);
        centerInfo.add(Box.createVerticalStrut(12));

        // ── Details card: loader / RAM / path, one row each ──────────────────
        RoundedPanel infoCard = new RoundedPanel(16, new Color(255, 255, 255, 10), new Color(255, 255, 255, 18));
        infoCard.setLayout(new BorderLayout());
        infoCard.setBorder(new EmptyBorder(16, 18, 16, 18));
        infoCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        infoCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));

        JLabel infoLbl = new JLabel("");
        infoLbl.setFont(new Font("SansSerif", Font.PLAIN, 13));
        infoLbl.setVerticalAlignment(SwingConstants.TOP);
        infoCard.add(infoLbl, BorderLayout.CENTER);

        centerInfo.add(infoCard);
        centerInfo.add(Box.createVerticalStrut(12));

        // ── Dawn client card ─────────────────────────────────────────────────
        // Outlined in the amber → burnt-orange gradient (#ffc15e top, #de8047 bottom)
        // with a
        // frosted-glass blurred interior (a blurred crop of the app's own background),
        // so the
        // card reads as its own branded "glass" panel rather than a flat gray box.
        RoundedPanel dawnCard = new RoundedPanel(16, new Color(255, 255, 255, 10), null);
        dawnCard.setBorderGradient(new Color(0xFF, 0xC1, 0x5E), new Color(0xDE, 0x80, 0x47), 2);
        this.dawnCard = dawnCard;
        // Keeps its own gradient/text/button colors — don't let applyTheme()'s
        // recursive
        // accent/panel-background restyling pass overwrite this card.
        dawnCard.putClientProperty("keepCustomBg", Boolean.TRUE);
        dawnCard.setLayout(new BorderLayout(10, 0));
        dawnCard.setBorder(new EmptyBorder(12, 18, 12, 18));
        dawnCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        dawnCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 68));

        dawnStatusLabel = new JLabel("Dawn client: —");
        dawnStatusLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        dawnStatusLabel.setForeground(Color.WHITE);
        dawnCard.add(dawnStatusLabel, BorderLayout.WEST);

        JPanel dawnButtonsRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        dawnButtonsRow.setOpaque(false);
        installDawnButton = new JButton("Install Dawn Client");
        deleteDawnButton = new JButton("Uninstall");

        JLabel dawnNote = new JLabel(
                "dawn client disabled offline mode players for no resoun + fix that problem when playing");
        dawnNote.setFont(new Font("SansSerif", Font.PLAIN, 10));
        dawnNote.setForeground(new Color(255, 255, 255, 150));
        dawnButtonsRow.add(dawnNote);

        for (JButton b : new JButton[] { installDawnButton, deleteDawnButton }) {
            b.setFont(new Font("SansSerif", Font.BOLD, 13));
            b.setFocusPainted(false);
            b.setForeground(Color.WHITE);
            b.setBackground(new Color(0xDE, 0x80, 0x47, 130));
            b.setMargin(new Insets(10, 20, 10, 20));
            b.putClientProperty("JButton.borderColor", new Color(0xFF, 0xC1, 0x5E, 160));
            b.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
        }

        // Gray out Dawn Install Button
        installDawnButton.setBackground(new Color(150, 150, 150, 130));
        installDawnButton.putClientProperty("JButton.borderColor", new Color(150, 150, 150, 160));
        installDawnButton.setEnabled(false);

        dawnButtonsRow.add(installDawnButton);
        dawnButtonsRow.add(deleteDawnButton);
        dawnCard.add(dawnButtonsRow, BorderLayout.EAST);

        centerInfo.add(dawnCard);

        detailsPanel.add(centerInfo, BorderLayout.CENTER);

        // Buttons Panel at the bottom of detail panel — primary actions on the
        // left, destructive/secondary actions grouped on the right.
        JPanel actionRow = new JPanel(new BorderLayout());

        JPanel primaryActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        JPanel destructiveActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        actionRow.add(primaryActions, BorderLayout.WEST);
        actionRow.add(destructiveActions, BorderLayout.EAST);

        playButton = new JButton("PLAY");
        playButton.setPreferredSize(new Dimension(130, 36));
        playButton.setFont(new Font("SansSerif", Font.BOLD, 14));
        playButton.setBackground(new Color(16, 185, 129));
        playButton.setForeground(Color.WHITE);
        playButton.setFocusPainted(false);
        playButton.putClientProperty("JButton.arc", PLAY_BUTTON_ARC);
        primaryActions.add(playButton);

        offlinePlayButton = new JButton("Offline Play");
        offlinePlayButton.setPreferredSize(new Dimension(130, 36));
        offlinePlayButton.setFont(new Font("SansSerif", Font.BOLD, 14));
        offlinePlayButton.setBackground(new Color(100, 116, 139)); // Slate gray
        offlinePlayButton.setForeground(Color.WHITE);
        offlinePlayButton.setFocusPainted(false);
        offlinePlayButton.putClientProperty("JButton.arc", PLAY_BUTTON_ARC);
        offlinePlayButton.setVisible(false); // Hidden by default
        primaryActions.add(offlinePlayButton);

        JButton manageModsBtn = new JButton("Manage Mods");
        manageModsBtn.setPreferredSize(new Dimension(130, 36));
        primaryActions.add(manageModsBtn);

        JButton editBtn = new JButton("Edit");
        editBtn.setPreferredSize(new Dimension(80, 36));
        primaryActions.add(editBtn);

        JButton killInstanceBtn = new JButton("Kill Instance");
        killInstanceBtn.setPreferredSize(new Dimension(110, 36));
        killInstanceBtn.setBackground(new Color(239, 68, 68)); // Red color
        killInstanceBtn.setForeground(Color.WHITE);
        killInstanceBtn.setFocusPainted(false);
        killInstanceBtn.setEnabled(false);
        killInstanceBtn.addActionListener(e -> killActiveGame());
        destructiveActions.add(killInstanceBtn);
        this.killInstanceButton = killInstanceBtn;

        JButton delBtn = new JButton("Delete");
        delBtn.setPreferredSize(new Dimension(80, 36));
        delBtn.setBackground(new Color(239, 68, 68));
        delBtn.setForeground(Color.WHITE);
        delBtn.setFocusPainted(false);
        destructiveActions.add(delBtn);

        detailsPanel.add(actionRow, BorderLayout.SOUTH);
        p.add(detailsPanel, BorderLayout.CENTER);

        // Selection Listener
        instanceList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting())
                return; // JList fires twice per click otherwise
            Instance sel = instanceList.getSelectedValue();
            if (sel != null) {
                nameLbl.setText(sel.name);
                versionBadge.setText(sel.mcVersion + "  •  " + sel.modLoader);
                Color accent = hexToColor(com.launcher.manager.SettingsManager.getInstance().getSettings().accentColor,
                        new Color(16, 185, 129));
                versionBadge.setForeground(accent);
                ImageIcon headerIcon;
                if (sel.imagePath != null && !sel.imagePath.isBlank()) {
                    File file = new File(sel.imagePath);
                    headerIcon = file.exists()
                            ? new ImageIcon(new ImageIcon(file.getAbsolutePath()).getImage().getScaledInstance(48, 48,
                                    Image.SCALE_SMOOTH))
                            : (defaultModIcon != null
                                    ? new ImageIcon(
                                            defaultModIcon.getImage().getScaledInstance(48, 48, Image.SCALE_SMOOTH))
                                    : null);
                } else {
                    headerIcon = defaultModIcon != null
                            ? new ImageIcon(defaultModIcon.getImage().getScaledInstance(48, 48, Image.SCALE_SMOOTH))
                            : null;
                }
                headerIconLbl.setIcon(headerIcon);
                String details = String.format(
                        "<html>Minecraft: %s<br>Loader: %s (%s)<br>RAM: %d MB<br>Path: %s</html>",
                        sel.mcVersion,
                        sel.modLoader,
                        sel.modLoaderVersion != null ? sel.modLoaderVersion : "None",
                        sel.ramMb,
                        escapeHtml(sanitizePrivacy(
                                sel.customDirectoryPath != null ? sel.customDirectoryPath : "Standard")));
                infoLbl.setText(details);
                updateDawnStatus(sel);
                if (logArea != null)
                    log("Selected instance \"" + sel.name + "\" (" + sel.mcVersion + ", " + sel.modLoader + ").");
            } else {
                nameLbl.setText("No instance selected");
                versionBadge.setText(" ");
                headerIconLbl.setIcon(null);
                infoLbl.setText("");
            }
        });

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
            launchGame(sel, acc, false);
        });

        offlinePlayButton.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            Account acc = (Account) accountBox.getSelectedItem();
            if (sel == null || acc == null)
                return;
            offlinePlayButton.setEnabled(false);
            launchGameOffline(sel, acc);
        });

        editBtn.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel != null) {
                // Remove any old EditInstancePanel before adding a new one
                for (Component comp : cardPanel.getComponents()) {
                    if (comp instanceof EditInstancePanel) {
                        cardPanel.remove(comp);
                        break;
                    }
                }
                EditInstancePanel editInstancePanel = new EditInstancePanel(
                        sel,
                        editedInst -> { // On save
                            instanceManager.save();
                            refreshInstances();
                            notifications.success("Instance updated", "Saved changes for: " + editedInst.name);
                            cardLayout.show(cardPanel, MAIN_VIEW); // Switch back to main view
                        },
                        () -> { // On cancel
                            cardLayout.show(cardPanel, MAIN_VIEW); // Switch back to main view
                        });
                cardPanel.add(editInstancePanel, EDIT_INSTANCE_VIEW);
                cardLayout.show(cardPanel, EDIT_INSTANCE_VIEW); // Switch to EditInstancePanel
            }
        });

        delBtn.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel != null) {
                int res = JOptionPane.showConfirmDialog(this, "Are you sure you want to delete " + sel.name + "?",
                        "Delete Instance", JOptionPane.YES_NO_OPTION);
                if (res == JOptionPane.YES_OPTION) {
                    Path gameDir = instanceManager.resolveGameDir(sel);
                    String dirName = gameDir.getFileName().toString();
                    Path instanceJson = gameDir.resolve(dirName + ".json");
                    try {
                        Files.deleteIfExists(instanceJson);
                    } catch (Exception ex) {
                        log("Failed to delete instance JSON: " + ex.getMessage());
                    }
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
    // MODS TAB
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

        // ── Toolbar: search bar on its own row, actions wrap below it ────────
        // (Previously a single cramped FlowLayout row mixed the search field in with
        // every
        // action button; this splits them so the search bar reads as the primary
        // control and
        // the buttons form a clear, wrapping action group under it.)
        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));
        toolbar.setOpaque(false);

        JPanel searchRow = new JPanel(new BorderLayout());
        searchRow.setOpaque(false);
        searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchRow.setBorder(new EmptyBorder(0, 0, 8, 0));

        modsSearchField = new JTextField();
        modsSearchField.putClientProperty("JTextField.placeholderText", "🔍  Search installed mods…");
        modsSearchField.setPreferredSize(new Dimension(280, 30));
        modsSearchField.setFont(new Font("SansSerif", Font.PLAIN, 13));
        modsSearchField.setForeground(Color.WHITE);
        modsSearchField.setCaretColor(Color.WHITE);
        modsSearchField.setBorder(new EmptyBorder(4, 4, 4, 4));
        modsSearchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filterMods();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filterMods();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filterMods();
            }
        });
        // Frosted-glass pill instead of a flat gray box: blurred crop of the app's own
        // background behind the field, with a soft outline.
        RoundedPanel searchWrap = wrapInFrostedGlass(modsSearchField, 20, new Color(255, 255, 255, 45));
        searchWrap.setPreferredSize(new Dimension(280, 34));
        searchRow.add(searchWrap, BorderLayout.WEST);
        toolbar.add(searchRow);

        JPanel actionsWrapper = new JPanel(new BorderLayout());
        actionsWrapper.setOpaque(false);
        actionsWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel leftActions = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 6));
        leftActions.setOpaque(false);

        JPanel rightActions = new JPanel(new WrapLayout(FlowLayout.RIGHT, 8, 6));
        rightActions.setOpaque(false);

        refreshModsBtn = new JButton("↻  Refresh");
        checkUpdatesBtn = new JButton("⟳  Check Updates");
        deleteModBtn = new JButton("🗑  Delete");

        // These are still used internally by the Fix Mods popup actions, but no
        // longer placed directly on the toolbar.
        updateAllBtn = new JButton("⬆  Update All");
        updateSelectedBtn = new JButton("⬆  Update Selected");
        installDependenciesBtn = new JButton("🔗  Install Deps");
        dedupeModsBtn = new JButton("🧹  Deduplicate");

        // Toolbar buttons (only Refresh, Check Updates, Delete shown directly)
        JButton[] btns = { refreshModsBtn, checkUpdatesBtn, deleteModBtn };
        for (JButton b : btns) {
            b.setFont(new Font("SansSerif", Font.BOLD, 11));
            b.setFocusPainted(false);
            b.setMargin(new Insets(8, 16, 8, 16));
            b.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
            leftActions.add(b);
        }

        // ── Fix Mods dropdown button ─────────────────────────────────────────
        fixModsBtn = new JButton("🔧  Fix Mods  |  ▾");
        fixModsBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
        fixModsBtn.setFocusPainted(false);
        fixModsBtn.setForeground(Color.WHITE);
        com.launcher.model.LauncherSettings fixModsSettings = com.launcher.manager.SettingsManager.getInstance().getSettings();
        Color accentColor = hexToColor(fixModsSettings.accentColor, new Color(16, 185, 129));
        fixModsBtn.setBackground(accentColor);
        fixModsBtn.setMargin(new Insets(8, 16, 8, 16));
        fixModsBtn.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
        fixModsBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // If clicked on the right side (the arrow), toggle dropdown
                if (e.getX() >= fixModsBtn.getWidth() - 32) {
                    toggleFixModsDropdown();
                } else {
                    // Clicked on the left side: execute all 3 actions
                    updateAllBtn.doClick();
                    installDependenciesBtn.doClick();
                    dedupeModsBtn.doClick();
                }
            }
        });
        leftActions.add(fixModsBtn);

        // ── Export / Import Mods buttons (distinct colors, right side) ────────
        exportModsBtn = new JButton("📤  Export Mods");
        exportModsBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
        exportModsBtn.setFocusPainted(false);
        exportModsBtn.setForeground(Color.WHITE);
        exportModsBtn.setBackground(new Color(6, 182, 212));  // cyan #06b6d4
        exportModsBtn.setMargin(new Insets(8, 16, 8, 16));
        exportModsBtn.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
        rightActions.add(exportModsBtn);

        importModsBtn = new JButton("📥  Import Mods");
        importModsBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
        importModsBtn.setFocusPainted(false);
        importModsBtn.setForeground(Color.WHITE);
        importModsBtn.setBackground(new Color(245, 158, 11)); // amber #f59e0b
        importModsBtn.setMargin(new Insets(8, 16, 8, 16));
        importModsBtn.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
        rightActions.add(importModsBtn);

        actionsWrapper.add(leftActions, BorderLayout.WEST);
        actionsWrapper.add(rightActions, BorderLayout.EAST);
        toolbar.add(actionsWrapper);

        JPanel topSection = new JPanel(new BorderLayout());
        topSection.add(header, BorderLayout.NORTH);
        topSection.add(toolbar, BorderLayout.SOUTH);
        p.add(topSection, BorderLayout.NORTH);

        // ── Mod List with rich card renderer ────────────────────────────────
        // Rebuilt to reuse the same RoundedPanel "card" look as the Instances list
        // (rounded
        // corners, translucent fill/border, accent-tinted highlight when selected)
        // instead of a
        // plain square JPanel, so Mods and Instances feel like the same design
        // language.
        modsListModel = new DefaultListModel<>();
        modsList = new JList<>(modsListModel);
        modsList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        modsList.setOpaque(false);
        modsList.setFixedCellHeight(68);
        modsList.setBorder(new EmptyBorder(4, 4, 4, 4));
        modsList.setCellRenderer(new ListCellRenderer<ModEntry>() {
            private final RoundedPanel card = new RoundedPanel(14, new Color(255, 255, 255, 10),
                    new Color(255, 255, 255, 18));

            @Override
            public Component getListCellRendererComponent(JList<? extends ModEntry> list, ModEntry mod, int index,
                    boolean isSelected, boolean cellHasFocus) {
                // Pulled live from settings each render (instead of hardcoded) so the mod list
                // actually follows the Accent/Panel Background/Text colors configured in
                // Settings.
                com.launcher.model.LauncherSettings settings = com.launcher.manager.SettingsManager.getInstance()
                        .getSettings();
                Color accent = hexToColor(settings.accentColor, new Color(16, 185, 129));
                Color textColor = hexToColor(settings.textColor, new Color(226, 226, 234));

                card.removeAll();
                card.setLayout(new BorderLayout(10, 0));
                card.setBorder(new EmptyBorder(8, 10, 8, 10));
                int cardWidth = list.getWidth() > 0 ? list.getWidth() - 8 : 400;
                card.setPreferredSize(new Dimension(cardWidth, 62));

                // Dawn Client is installed as a raw "dawn-standalone.jar" file — special-case
                // it
                // so it shows a proper name/icon and the same amber→orange gradient outline as
                // the Dawn Client card on the Instances tab, instead of looking like a random
                // mod.
                boolean isDawnClient = mod.fileName != null && mod.fileName.equalsIgnoreCase("dawn-standalone.jar");

                if (isDawnClient) {
                    card.setColors(new Color(255, 255, 255, 10), null);
                    card.setBorderGradient(new Color(0xFF, 0xC1, 0x5E), new Color(0xDE, 0x80, 0x47), 2);
                    // Frosted-glass interior like the Dawn card on Instances, but with a
                    // stronger wash of the same amber/orange gradient colors (not the barely-
                    // there tint used there) so it reads as clearly "Dawn"-colored, not just gray
                    // blur.
                    card.setFrostedGlass(layeredPane, 6, new Color(0xDE, 0x80, 0x47, 110));
                } else if (isSelected) {
                    card.clearFrostedGlass();
                    card.setBorderGradient(null, null, 1); // clear any leftover gradient border
                    card.setColors(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 45),
                            new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 200));
                } else {
                    card.clearFrostedGlass();
                    card.setBorderGradient(null, null, 1);
                    card.setColors(new Color(255, 255, 255, 10), new Color(255, 255, 255, 18));
                }

                // Icon (32×32, async-loaded)
                JLabel iconLbl = new JLabel();
                iconLbl.setPreferredSize(new Dimension(32, 32));
                if (isDawnClient && dawnClientIcon32 != null) {
                    iconLbl.setIcon(dawnClientIcon32);
                } else if (mod.iconUrl != null && !mod.iconUrl.isBlank()) {
                    ImageIcon icon = modIconCache.get(mod.iconUrl);
                    if (icon != null) {
                        iconLbl.setIcon(icon);
                    } else {
                        iconLbl.setIcon(defaultModIcon);
                        final String url = mod.iconUrl;
                        new Thread(() -> {
                            ImageIcon ic = loadIconRobust(url, 32);
                            if (ic != null) {
                                modIconCache.put(url, ic);
                            }
                            SwingUtilities.invokeLater(() -> modsList.repaint());
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

                JLabel nameLbl = new JLabel(isDawnClient ? "Dawn Client" : mod.displayName());
                nameLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
                nameLbl.setForeground(textColor);
                nameLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
                center.add(nameLbl);

                // Second row: file name + size
                String infoText = mod.fileName + "  ·  " + mod.formattedSize();
                JLabel infoLbl = new JLabel(infoText);
                infoLbl.setFont(new Font("SansSerif", Font.PLAIN, 10));
                infoLbl.setForeground(tint(textColor, -80));
                infoLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
                center.add(infoLbl);

                // Third row: version info
                String verText = mod.currentVersion != null ? "v" + mod.currentVersion : "local";
                if (mod.latestVersion != null && !mod.latestVersion.equals(mod.currentVersion)) {
                    verText += "  →  v" + mod.latestVersion;
                }
                JLabel verLbl = new JLabel(verText);
                verLbl.setFont(new Font("SansSerif", Font.PLAIN, 10));
                verLbl.setForeground(mod.latestVersion != null ? new Color(245, 158, 11) : tint(textColor, -60));
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
                    default -> { // Unknown
                        statusLbl.setForeground(new Color(140, 140, 160));
                        statusLbl.setBackground(new Color(255, 255, 255, 10));
                    }
                }
                JPanel statusWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
                statusWrap.setOpaque(false);

                // Add an "Update" button label for mods with available updates
                if ("Update available".equals(mod.status) && mod.updateUrl != null) {
                    JLabel updateBtnLbl = new JLabel("⬆ Update");
                    updateBtnLbl.setFont(new Font("SansSerif", Font.BOLD, 10));
                    updateBtnLbl.setForeground(Color.WHITE);
                    updateBtnLbl.setOpaque(true);
                    updateBtnLbl.setBackground(new Color(245, 158, 11));
                    updateBtnLbl.setBorder(new EmptyBorder(4, 10, 4, 10));
                    updateBtnLbl.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
                    updateBtnLbl.setName("updateBtn"); // marker for mouse listener
                    statusWrap.add(updateBtnLbl);
                }

                statusWrap.add(statusLbl);
                card.add(statusWrap, BorderLayout.EAST);

                return card;
            }
        });

        // Mouse listener to handle clicks on the per-mod "Update" button label
        modsList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = modsList.locationToIndex(e.getPoint());
                if (idx < 0) return;
                ModEntry mod = modsListModel.getElementAt(idx);
                if (!"Update available".equals(mod.status) || mod.updateUrl == null) return;

                // Check if the click was in the right-hand area (status/update region)
                Rectangle cellBounds = modsList.getCellBounds(idx, idx);
                if (cellBounds == null) return;
                int relativeX = e.getX() - cellBounds.x;
                // The update button is roughly in the right 180px of the cell
                if (relativeX < cellBounds.width - 180) return;

                Instance sel = instanceList.getSelectedValue();
                if (sel == null) return;

                // Update this single mod
                notifications.info("Updating", "Updating " + mod.displayName() + "…");
                new Thread(() -> {
                    ModUpdateService service = new ModUpdateService();
                    Path modsDir = instanceManager.resolveGameDir(sel).resolve("mods");
                    boolean ok = service.downloadUpdate(mod, modsDir,
                            msg -> SwingUtilities.invokeLater(() -> setStatus(msg)));
                    SwingUtilities.invokeLater(() -> {
                        if (ok) {
                            notifications.success("Updated", mod.displayName() + " updated successfully.");
                            refreshModsView(sel);
                        } else {
                            notifications.error("Update failed", "Failed to update " + mod.displayName());
                        }
                    });
                }, "update-single-" + mod.fileName).start();
            }
        });

        JScrollPane scroll = new JScrollPane(modsList);
        com.launcher.ui.SmoothScroll.install(scroll);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        p.add(scroll, BorderLayout.CENTER);

        // ── Button actions ──────────────────────────────────────────────────
        refreshModsBtn.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel != null)
                refreshModsView(sel);
        });

        checkUpdatesBtn.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel == null)
                return;
            checkUpdatesBtn.setEnabled(false);
            notifications.info("Checking Updates", "Scanning Modrinth for mod updates…");
            new Thread(() -> {
                try {
                    ModUpdateService service = new ModUpdateService();
                    service.identifyMods(currentModEntries, msg -> SwingUtilities.invokeLater(() -> setStatus(msg)));
                    String loader = sel.modLoader != null && sel.modLoader != ModLoaderType.VANILLA
                            ? sel.modLoader.name().toLowerCase()
                            : null;
                    service.checkUpdates(currentModEntries, sel.mcVersion, loader,
                            msg -> SwingUtilities.invokeLater(() -> setStatus(msg)));
                    SwingUtilities.invokeLater(() -> {
                        filterMods();
                        checkUpdatesBtn.setEnabled(true);
                        long updatable = currentModEntries.stream().filter(m -> "Update available".equals(m.status))
                                .count();
                        notifications.success("Check completed",
                                updatable + " update(s) available out of " + currentModEntries.size() + " mods.");
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
            if (sel == null)
                return;
            List<ModEntry> updatable = currentModEntries.stream()
                    .filter(m -> "Update available".equals(m.status) && m.updateUrl != null)
                    .toList();
            if (updatable.isEmpty()) {
                notifications.info("No updates", "All mods are up to date.");
                return;
            }
            updateAllBtn.setEnabled(false);
            notifications.info("Updating", "Updating " + updatable.size() + " mod(s)…");
            String downloadId = com.launcher.manager.DownloadManager.getInstance()
                    .start("Updating " + updatable.size() + " mods");
            new Thread(() -> {
                com.launcher.manager.DownloadManager.getInstance().bindThread(downloadId);
                ModUpdateService service = new ModUpdateService();
                Path modsDir = instanceManager.resolveGameDir(sel).resolve("mods");
                int ok = 0;
                for (int i = 0; i < updatable.size(); i++) {
                    com.launcher.manager.DownloadManager.getInstance().awaitIfPaused(downloadId);
                    if (com.launcher.manager.DownloadManager.getInstance().isCancelled(downloadId))
                        break;
                    ModEntry mod = updatable.get(i);
                    com.launcher.manager.DownloadManager.getInstance().update(downloadId,
                            "(" + (i + 1) + "/" + updatable.size() + ") " + mod.displayName(),
                            (int) (100.0 * i / updatable.size()));
                    if (service.downloadUpdate(mod, modsDir, msg -> SwingUtilities.invokeLater(() -> setStatus(msg))))
                        ok++;
                }
                final int finalOk = ok;
                com.launcher.manager.DownloadManager.getInstance().finish(downloadId,
                        "Updated " + finalOk + " of " + updatable.size());
                SwingUtilities.invokeLater(() -> {
                    filterMods();
                    updateAllBtn.setEnabled(true);
                    notifications.success("Updates complete",
                            "Updated " + finalOk + " of " + updatable.size() + " mods.");
                });
            }, "update-all").start();
        });

        updateSelectedBtn.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel == null)
                return;
            List<ModEntry> selected = modsList.getSelectedValuesList().stream()
                    .filter(m -> "Update available".equals(m.status) && m.updateUrl != null)
                    .toList();
            if (selected.isEmpty()) {
                notifications.info("No updates", "Selected mods have no available updates.");
                return;
            }
            updateSelectedBtn.setEnabled(false);
            String downloadId = com.launcher.manager.DownloadManager.getInstance()
                    .start("Updating " + selected.size() + " selected mods");
            new Thread(() -> {
                com.launcher.manager.DownloadManager.getInstance().bindThread(downloadId);
                ModUpdateService service = new ModUpdateService();
                Path modsDir = instanceManager.resolveGameDir(sel).resolve("mods");
                int ok = 0;
                for (int i = 0; i < selected.size(); i++) {
                    com.launcher.manager.DownloadManager.getInstance().awaitIfPaused(downloadId);
                    if (com.launcher.manager.DownloadManager.getInstance().isCancelled(downloadId))
                        break;
                    ModEntry mod = selected.get(i);
                    com.launcher.manager.DownloadManager.getInstance().update(downloadId,
                            "(" + (i + 1) + "/" + selected.size() + ") " + mod.displayName(),
                            (int) (100.0 * i / selected.size()));
                    if (service.downloadUpdate(mod, modsDir, msg -> SwingUtilities.invokeLater(() -> setStatus(msg))))
                        ok++;
                }
                final int finalOk = ok;
                com.launcher.manager.DownloadManager.getInstance().finish(downloadId,
                        "Updated " + finalOk + " of " + selected.size());
                SwingUtilities.invokeLater(() -> {
                    filterMods();
                    updateSelectedBtn.setEnabled(true);
                    notifications.success("Updates complete",
                            "Updated " + finalOk + " of " + selected.size() + " selected mods.");
                });
            }, "update-selected").start();
        });

        deleteModBtn.addActionListener(e -> {
            List<ModEntry> selected = modsList.getSelectedValuesList();
            if (selected.isEmpty())
                return;
            int res = JOptionPane.showConfirmDialog(this,
                    "Are you sure you want to delete " + selected.size() + " mod(s)?", "Delete Mods",
                    JOptionPane.YES_NO_OPTION);
            if (res == JOptionPane.YES_OPTION) {
                for (ModEntry m : selected) {
                    try {
                        Files.deleteIfExists(Path.of(m.filePath));
                    } catch (Exception ignored) {
                    }
                }
                Instance sel = instanceList.getSelectedValue();
                if (sel != null)
                    refreshModsView(sel);
                notifications.warning("Mods deleted", "Deleted " + selected.size() + " mod(s).");
            }
        });

        dedupeModsBtn.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel == null)
                return;
            // Find mods with the same Modrinth project ID (keeping the newest version)
            Map<String, List<ModEntry>> byProject = new LinkedHashMap<>();
            for (ModEntry m : currentModEntries) {
                if (m.modrinthId != null) {
                    byProject.computeIfAbsent(m.modrinthId, k -> new ArrayList<>()).add(m);
                }
            }
            int removed = 0;
            for (var group : byProject.values()) {
                if (group.size() <= 1)
                    continue;
                // Keep the first (typically identified version); delete the rest
                for (int i = 1; i < group.size(); i++) {
                    try {
                        Files.deleteIfExists(Path.of(group.get(i).filePath));
                        removed++;
                    } catch (Exception ignored) {
                    }
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
            if (sel == null)
                return;
            installDependenciesBtn.setEnabled(false);
            notifications.info("Finding dependencies", "Scanning for missing required dependencies…");
            new Thread(() -> {
                try {
                    ModUpdateService service = new ModUpdateService();
                    String loader = sel.modLoader != null && sel.modLoader != ModLoaderType.VANILLA
                            ? sel.modLoader.name().toLowerCase()
                            : null;
                    var missing = service.findMissingRequiredDependencies(
                            currentModEntries, loader, sel.mcVersion,
                            msg -> SwingUtilities.invokeLater(() -> setStatus(msg)));
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
                            if (url == null)
                                continue;
                            String fileName = url.substring(url.lastIndexOf('/') + 1);
                            com.launcher.util.HttpUtil.downloadToFile(url, modsDir.resolve(fileName));
                            installed++;
                            final String depName = entry.getValue();
                            SwingUtilities.invokeLater(() -> setStatus("Installed dependency: " + depName));
                        } catch (Exception ignored) {
                        }
                    }
                    final int finalInstalled = installed;
                    SwingUtilities.invokeLater(() -> {
                        installDependenciesBtn.setEnabled(true);
                        refreshModsView(sel);
                        if (missing.isEmpty()) {
                            notifications.info("No dependencies needed", "All required dependencies are installed.");
                        } else {
                            String names = String.join(", ", missing.values());
                            notifications.success("Dependencies installed",
                                    "Installed " + finalInstalled + " of " + missing.size() + " dependencies:\n"
                                            + names);
                        }
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        installDependenciesBtn.setEnabled(true);
                        notifications.error("Dependency check failed", ex.getMessage());
                    });
                }
            }, "install-deps").start();
        });

        // ══════════════════════════════════════════════════════════════════════
        // EXPORT MODS — in-launcher overlay with mod selection + save location
        // ══════════════════════════════════════════════════════════════════════
        exportModsBtn.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel == null) {
                notifications.error("No instance", "Select an instance first.");
                return;
            }
            if (currentModEntries.isEmpty()) {
                notifications.info("No mods", "This instance has no mods to export.");
                return;
            }
            showExportOverlay(sel);
        });

        // ══════════════════════════════════════════════════════════════════════
        // IMPORT MODS — file chooser + in-launcher overlay with mod selection
        // ══════════════════════════════════════════════════════════════════════
        importModsBtn.addActionListener(e -> {
            Instance sel = instanceList.getSelectedValue();
            if (sel == null) {
                notifications.error("No instance", "Select a target instance first.");
                return;
            }
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select Mod List JSON");
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON Files (*.json)", "json"));
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File jsonFile = chooser.getSelectedFile();
            try {
                String content = Files.readString(jsonFile.toPath());
                JsonObject root = JsonUtil.parse(content).getAsJsonObject();
                showImportOverlay(sel, root, jsonFile.getName());
            } catch (Exception ex) {
                notifications.error("Invalid file", "Could not parse mod list: " + ex.getMessage());
            }
        });

        return p;
    }

    /**
     * Robustly loads an icon image from a URL for use in the Mods/Discover lists.
     * Uses our own
     * HttpClient (proper User-Agent + timeout) instead of a bare URLConnection,
     * since some CDNs
     * silently reject/hang on those, and validates the decoded image before
     * returning it, since
     * some icons (e.g. WebP) can't be decoded by Java's built-in image codecs and
     * would otherwise
     * show up as a blank/broken icon instead of falling back cleanly to the default
     * one.
     *
     * @return the loaded icon, or null if it couldn't be loaded/decoded (caller
     *         should fall back
     *         to a default icon in that case).
     */
    private ImageIcon loadIconRobust(String url, int size) {
        if (url == null || url.isBlank() || iconLoadFailures.contains(url))
            return null;
        try {
            byte[] bytes = com.launcher.util.HttpUtil.getBytes(url);
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
                iconLoadFailures.add(url);
                return null;
            }
            return new ImageIcon(img.getScaledInstance(size, size, Image.SCALE_SMOOTH));
        } catch (Throwable ex) {
            // Throwable (not just Exception) on purpose: a broken/missing ImageIO plugin
            // registration (e.g. from a shading issue) can surface as a LinkageError or
            // ServiceConfigurationError, and we'd rather fall back to the default icon than
            // have that silently kill this thread for every icon load. Icon fetch/decode
            // failures (timeouts, unsupported formats, etc.) are expected and don't need to
            // be surfaced in the console — the UI already falls back to a default icon.
            iconLoadFailures.add(url);
            return null;
        }
    }

    private void refreshModsView(Instance inst) {
        modsHeaderLabel.setText("Mods — " + inst.name);
        modsCountLabel.setText("Scanning…");
        log("Scanning mods for \"" + inst.name + "\"…");
        long startedAt = System.currentTimeMillis();
        new Thread(() -> {
            try {
                ModUpdateService service = new ModUpdateService();
                Path modsDir = instanceManager.resolveGameDir(inst).resolve("mods");
                List<ModEntry> list = service.scanModsDir(modsDir);
                log("Found " + list.size() + " mod jar(s) in " + modsDir + " (hashed in "
                        + (System.currentTimeMillis() - startedAt) + " ms).");

                // Publish the scanned list to the UI immediately instead of waiting for the
                // Modrinth identification round-trip to finish first — the list used to sit
                // on "Scanning…" the whole time even though the (usually much slower) network
                // part hadn't started yet. This makes Refresh feel instant for the common case
                // where the user mostly cares about seeing what's installed.
                SwingUtilities.invokeLater(() -> {
                    currentModEntries = list;
                    filterMods();
                });

                // Identify mods against Modrinth in the background; the list refreshes again
                // once names/versions/update status come back.
                service.identifyMods(list, msg -> SwingUtilities.invokeLater(() -> setStatus(msg)));
                long identified = list.stream().filter(m -> m.modrinthId != null).count();
                log("Identified " + identified + " of " + list.size() + " mod(s) on Modrinth in "
                        + (System.currentTimeMillis() - startedAt) + " ms total.");
                SwingUtilities.invokeLater(() -> {
                    currentModEntries = list;
                    filterMods();
                });
            } catch (Exception ex) {
                log("Mod scan failed: " + ex.getMessage());
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
    // EXPORT MODS OVERLAY
    // ══════════════════════════════════════════════════════════════════════════
    private void showExportOverlay(Instance inst) {
        // Remove any existing overlay
        if (exportOverlay != null) {
            layeredPane.remove(exportOverlay);
            layeredPane.repaint();
        }

        exportOverlay = new RoundedPanel(18, new Color(20, 20, 26, 250), new Color(255, 255, 255, 34));
        exportOverlay.putClientProperty("keepCustomBg", Boolean.TRUE);
        exportOverlay.setLayout(new BorderLayout());
        exportOverlay.setFrostedGlass(layeredPane, 8, new Color(12, 12, 16, 150));

        // ── Header ──
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(14, 18, 10, 12));

        JLabel title = new JLabel("📤  Export Mods — " + inst.name);
        title.setFont(new Font("SansSerif", Font.BOLD, 17));
        title.setForeground(new Color(6, 182, 212)); // cyan accent
        header.add(title, BorderLayout.CENTER);

        JButton closeBtn = new JButton("✕");
        closeBtn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        closeBtn.setFocusPainted(false);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setOpaque(false);
        closeBtn.setMargin(new Insets(4, 10, 4, 10));
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.addActionListener(ev -> animateOverlayHide(exportOverlay));
        header.add(closeBtn, BorderLayout.EAST);

        exportOverlay.add(header, BorderLayout.NORTH);

        // ── Info bar ──
        JPanel infoBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        infoBar.setOpaque(false);
        infoBar.setBorder(new EmptyBorder(0, 18, 6, 18));
        JLabel versionLbl = new JLabel("MC " + inst.mcVersion);
        versionLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        versionLbl.setForeground(new Color(156, 163, 175));
        infoBar.add(versionLbl);
        JLabel loaderLbl = new JLabel("│  " + (inst.modLoader != null ? inst.modLoader.name() : "VANILLA")
                + (inst.modLoaderVersion != null ? " " + inst.modLoaderVersion : ""));
        loaderLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        loaderLbl.setForeground(new Color(156, 163, 175));
        infoBar.add(loaderLbl);

        // ── Mod checkboxes list ──
        JPanel listPanel = new JPanel();
        listPanel.setOpaque(false);
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBorder(new EmptyBorder(4, 18, 4, 18));

        List<JCheckBox> checkboxes = new ArrayList<>();
        for (ModEntry mod : currentModEntries) {
            JCheckBox cb = new JCheckBox(mod.displayName(), true);
            cb.setOpaque(false);
            cb.setForeground(Color.WHITE);
            cb.setFont(new Font("SansSerif", Font.PLAIN, 13));
            cb.putClientProperty("modEntry", mod);
            if (mod.modrinthId != null) {
                cb.setToolTipText("Modrinth: https://modrinth.com/mod/" + mod.modrinthId);
            } else {
                cb.setToolTipText("No Modrinth link (will be skipped during import)");
                cb.setForeground(new Color(180, 180, 190));
            }
            checkboxes.add(cb);
            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            row.add(cb, BorderLayout.WEST);
            if (mod.modrinthId != null) {
                JLabel link = new JLabel("modrinth.com/mod/" + mod.modrinthId);
                link.setFont(new Font("SansSerif", Font.PLAIN, 10));
                link.setForeground(new Color(6, 182, 212, 160));
                row.add(link, BorderLayout.EAST);
            }
            listPanel.add(row);
            listPanel.add(Box.createVerticalStrut(2));
        }

        JScrollPane scroll = new JScrollPane(listPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        com.launcher.ui.SmoothScroll.install(scroll);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setOpaque(false);
        centerPanel.add(infoBar, BorderLayout.NORTH);
        centerPanel.add(scroll, BorderLayout.CENTER);
        exportOverlay.add(centerPanel, BorderLayout.CENTER);

        // ── Bottom bar: Select All/None, Choose Location, Export ──
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        bottomBar.setOpaque(false);
        bottomBar.setBorder(new EmptyBorder(6, 18, 14, 18));

        JButton selectAll = new JButton("Select All");
        selectAll.setFont(new Font("SansSerif", Font.PLAIN, 11));
        selectAll.setMargin(new Insets(6, 14, 6, 14));
        selectAll.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
        selectAll.addActionListener(ev -> checkboxes.forEach(cb -> cb.setSelected(true)));
        bottomBar.add(selectAll);

        JButton selectNone = new JButton("Select None");
        selectNone.setFont(new Font("SansSerif", Font.PLAIN, 11));
        selectNone.setMargin(new Insets(6, 14, 6, 14));
        selectNone.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
        selectNone.addActionListener(ev -> checkboxes.forEach(cb -> cb.setSelected(false)));
        bottomBar.add(selectNone);

        // Save location path holder
        final Path[] savePath = { null };
        JLabel savePathLbl = new JLabel("No location chosen");
        savePathLbl.setFont(new Font("SansSerif", Font.ITALIC, 11));
        savePathLbl.setForeground(new Color(156, 163, 175));

        JButton chooseLocBtn = new JButton("📂  Choose Save Location");
        chooseLocBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
        chooseLocBtn.setMargin(new Insets(6, 14, 6, 14));
        chooseLocBtn.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
        chooseLocBtn.addActionListener(ev -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Save Mod List As");
            String safeName = inst.name.replaceAll("[\\\\/:*?\"<>|]", "_");
            fc.setSelectedFile(new File(safeName + "_mods.json"));
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON Files (*.json)", "json"));
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                if (!f.getName().endsWith(".json")) f = new File(f.getAbsolutePath() + ".json");
                savePath[0] = f.toPath();
                savePathLbl.setText(f.getAbsolutePath());
            }
        });
        bottomBar.add(chooseLocBtn);

        JButton exportBtn = new JButton("✔  Export");
        exportBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        exportBtn.setForeground(Color.WHITE);
        exportBtn.setBackground(new Color(6, 182, 212));
        exportBtn.setMargin(new Insets(8, 20, 8, 20));
        exportBtn.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
        exportBtn.addActionListener(ev -> {
            if (savePath[0] == null) {
                notifications.warning("No save location", "Please choose where to save the file first.");
                return;
            }
            List<ModEntry> selected = new ArrayList<>();
            for (JCheckBox cb : checkboxes) {
                if (cb.isSelected()) {
                    selected.add((ModEntry) cb.getClientProperty("modEntry"));
                }
            }
            if (selected.isEmpty()) {
                notifications.warning("Nothing selected", "Select at least one mod to export.");
                return;
            }
            // Build JSON
            JsonObject root = new JsonObject();
            root.addProperty("launcherVersion", "Zero Launcher");
            root.addProperty("instanceName", inst.name);
            root.addProperty("mcVersion", inst.mcVersion);
            root.addProperty("modLoader", inst.modLoader != null ? inst.modLoader.name() : "VANILLA");
            if (inst.modLoaderVersion != null) root.addProperty("modLoaderVersion", inst.modLoaderVersion);
            JsonArray modsArr = new JsonArray();
            for (ModEntry m : selected) {
                JsonObject mObj = new JsonObject();
                mObj.addProperty("name", m.displayName());
                mObj.addProperty("fileName", m.fileName);
                if (m.modrinthId != null) {
                    mObj.addProperty("modrinthId", m.modrinthId);
                    mObj.addProperty("modrinthUrl", "https://modrinth.com/mod/" + m.modrinthId);
                }
                modsArr.add(mObj);
            }
            root.add("mods", modsArr);

            try {
                Files.writeString(savePath[0], JsonUtil.GSON.toJson(root));
                notifications.success("Mods exported",
                        "Exported " + selected.size() + " mod(s) to " + savePath[0].getFileName());
                animateOverlayHide(exportOverlay);
            } catch (Exception ex) {
                notifications.error("Export failed", ex.getMessage());
            }
        });
        bottomBar.add(exportBtn);

        JPanel bottomWrap = new JPanel(new BorderLayout());
        bottomWrap.setOpaque(false);
        bottomWrap.add(savePathLbl, BorderLayout.WEST);
        bottomWrap.add(bottomBar, BorderLayout.EAST);
        bottomWrap.setBorder(new EmptyBorder(0, 18, 0, 0));
        exportOverlay.add(bottomWrap, BorderLayout.SOUTH);

        // Position and animate
        positionModOverlay(exportOverlay);
        animateOverlayShow(exportOverlay);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // IMPORT MODS OVERLAY
    // ══════════════════════════════════════════════════════════════════════════
    private void showImportOverlay(Instance targetInst, JsonObject root, String fileName) {
        // Remove any existing overlay
        if (importOverlay != null) {
            layeredPane.remove(importOverlay);
            layeredPane.repaint();
        }

        importOverlay = new RoundedPanel(18, new Color(20, 20, 26, 250), new Color(255, 255, 255, 34));
        importOverlay.putClientProperty("keepCustomBg", Boolean.TRUE);
        importOverlay.setLayout(new BorderLayout());
        importOverlay.setFrostedGlass(layeredPane, 8, new Color(12, 12, 16, 150));

        // ── Header ──
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(14, 18, 10, 12));

        JLabel title = new JLabel("📥  Import Mods — " + fileName);
        title.setFont(new Font("SansSerif", Font.BOLD, 17));
        title.setForeground(new Color(245, 158, 11)); // amber accent
        header.add(title, BorderLayout.CENTER);

        JButton closeBtn = new JButton("✕");
        closeBtn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        closeBtn.setFocusPainted(false);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setOpaque(false);
        closeBtn.setMargin(new Insets(4, 10, 4, 10));
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.addActionListener(ev -> animateOverlayHide(importOverlay));
        header.add(closeBtn, BorderLayout.EAST);

        importOverlay.add(header, BorderLayout.NORTH);

        // ── Source info bar ──
        JPanel infoBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        infoBar.setOpaque(false);
        infoBar.setBorder(new EmptyBorder(0, 18, 4, 18));

        String srcInstance = root.has("instanceName") ? root.get("instanceName").getAsString() : "Unknown";
        String srcVersion = root.has("mcVersion") ? root.get("mcVersion").getAsString() : "?";
        String srcLoader = root.has("modLoader") ? root.get("modLoader").getAsString() : "VANILLA";
        String srcLoaderVer = root.has("modLoaderVersion") ? root.get("modLoaderVersion").getAsString() : "";

        JLabel srcLbl = new JLabel("Source: " + srcInstance + "  │  MC " + srcVersion + "  │  " + srcLoader + " " + srcLoaderVer);
        srcLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        srcLbl.setForeground(new Color(156, 163, 175));
        infoBar.add(srcLbl);

        JLabel targetLbl = new JLabel("→  Target: " + targetInst.name + "  (MC " + targetInst.mcVersion + ", "
                + (targetInst.modLoader != null ? targetInst.modLoader.name() : "VANILLA") + ")");
        targetLbl.setFont(new Font("SansSerif", Font.BOLD, 12));
        targetLbl.setForeground(new Color(16, 185, 129));
        infoBar.add(targetLbl);

        // ── Mod checkboxes list ──
        JsonArray modsArr = root.has("mods") ? root.getAsJsonArray("mods") : new JsonArray();
        JPanel listPanel = new JPanel();
        listPanel.setOpaque(false);
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBorder(new EmptyBorder(4, 18, 4, 18));

        List<JCheckBox> checkboxes = new ArrayList<>();
        for (int i = 0; i < modsArr.size(); i++) {
            JsonObject mObj = modsArr.get(i).getAsJsonObject();
            String name = mObj.has("name") ? mObj.get("name").getAsString() : "Unknown mod";
            String modrinthId = mObj.has("modrinthId") ? mObj.get("modrinthId").getAsString() : null;
            String modrinthUrl = mObj.has("modrinthUrl") ? mObj.get("modrinthUrl").getAsString() : null;

            JCheckBox cb = new JCheckBox(name, true);
            cb.setOpaque(false);
            cb.setFont(new Font("SansSerif", Font.PLAIN, 13));
            cb.putClientProperty("modrinthId", modrinthId);
            cb.putClientProperty("modName", name);

            if (modrinthId != null) {
                cb.setForeground(Color.WHITE);
                cb.setToolTipText(modrinthUrl != null ? modrinthUrl : "Modrinth ID: " + modrinthId);
            } else {
                cb.setForeground(new Color(255, 160, 80));
                cb.setToolTipText("⚠ No Modrinth ID — cannot download automatically");
                cb.setSelected(false);
                cb.setEnabled(false);
            }
            checkboxes.add(cb);

            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            row.add(cb, BorderLayout.WEST);
            if (modrinthUrl != null) {
                JLabel link = new JLabel(modrinthUrl.replace("https://", ""));
                link.setFont(new Font("SansSerif", Font.PLAIN, 10));
                link.setForeground(new Color(245, 158, 11, 160));
                row.add(link, BorderLayout.EAST);
            }
            listPanel.add(row);
            listPanel.add(Box.createVerticalStrut(2));
        }

        JScrollPane scroll = new JScrollPane(listPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        com.launcher.ui.SmoothScroll.install(scroll);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setOpaque(false);
        centerPanel.add(infoBar, BorderLayout.NORTH);
        centerPanel.add(scroll, BorderLayout.CENTER);
        importOverlay.add(centerPanel, BorderLayout.CENTER);

        // ── Bottom bar: Select All/None, Import Selected ──
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        bottomBar.setOpaque(false);
        bottomBar.setBorder(new EmptyBorder(6, 18, 14, 18));

        JButton selectAll = new JButton("Select All");
        selectAll.setFont(new Font("SansSerif", Font.PLAIN, 11));
        selectAll.setMargin(new Insets(6, 14, 6, 14));
        selectAll.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
        selectAll.addActionListener(ev -> checkboxes.forEach(cb -> { if (cb.isEnabled()) cb.setSelected(true); }));
        bottomBar.add(selectAll);

        JButton selectNone = new JButton("Select None");
        selectNone.setFont(new Font("SansSerif", Font.PLAIN, 11));
        selectNone.setMargin(new Insets(6, 14, 6, 14));
        selectNone.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
        selectNone.addActionListener(ev -> checkboxes.forEach(cb -> cb.setSelected(false)));
        bottomBar.add(selectNone);

        JLabel progressLbl = new JLabel(" ");
        progressLbl.setFont(new Font("SansSerif", Font.ITALIC, 11));
        progressLbl.setForeground(new Color(156, 163, 175));

        JButton importBtn = new JButton("⬇  Import Selected");
        importBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        importBtn.setForeground(Color.WHITE);
        importBtn.setBackground(new Color(245, 158, 11));
        importBtn.setMargin(new Insets(8, 20, 8, 20));
        importBtn.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
        importBtn.addActionListener(ev -> {
            // Gather selected mods with Modrinth IDs
            List<String[]> toDownload = new ArrayList<>(); // [modrinthId, name]
            List<String> skipped = new ArrayList<>();
            for (JCheckBox cb : checkboxes) {
                if (!cb.isSelected()) continue;
                String mid = (String) cb.getClientProperty("modrinthId");
                String mName = (String) cb.getClientProperty("modName");
                if (mid != null && !mid.isBlank()) {
                    toDownload.add(new String[]{ mid, mName });
                } else {
                    skipped.add(mName);
                }
            }
            if (toDownload.isEmpty()) {
                notifications.warning("Nothing to import", "No downloadable mods selected.");
                return;
            }
            importBtn.setEnabled(false);
            importBtn.setText("⏳  Importing...");

            // Download in background thread
            new Thread(() -> {
                Path modsDir = instanceManager.resolveGameDir(targetInst).resolve("mods");
                try { Files.createDirectories(modsDir); } catch (Exception ignored) {}

                String loader = targetInst.modLoader != null && targetInst.modLoader != ModLoaderType.VANILLA
                        ? targetInst.modLoader.name().toLowerCase() : null;
                ModUpdateService service = new ModUpdateService();
                int success = 0;
                List<String> failed = new ArrayList<>();

                for (int i = 0; i < toDownload.size(); i++) {
                    String[] entry = toDownload.get(i);
                    String mid = entry[0];
                    String mName = entry[1];
                    final int idx = i + 1;
                    SwingUtilities.invokeLater(() -> {
                        progressLbl.setText("Downloading " + idx + "/" + toDownload.size() + ": " + mName);
                        setStatus("Importing mod " + idx + "/" + toDownload.size() + ": " + mName);
                    });
                    try {
                        String url = service.getDownloadUrlForProject(mid, "mod", loader, targetInst.mcVersion);
                        if (url == null) {
                            failed.add(mName + " (no compatible version)");
                            continue;
                        }
                        String dlFileName = url.substring(url.lastIndexOf('/') + 1);
                        com.launcher.util.HttpUtil.downloadToFile(url, modsDir.resolve(dlFileName));
                        success++;
                    } catch (Exception ex) {
                        failed.add(mName + " (" + ex.getMessage() + ")");
                    }
                }

                final int finalSuccess = success;
                final List<String> finalFailed = failed;
                SwingUtilities.invokeLater(() -> {
                    importBtn.setEnabled(true);
                    importBtn.setText("⬇  Import Selected");
                    progressLbl.setText(" ");
                    setStatus("Import complete");
                    refreshModsView(targetInst);

                    StringBuilder msg = new StringBuilder();
                    msg.append("Installed ").append(finalSuccess).append(" of ").append(toDownload.size()).append(" mod(s).");
                    if (!skipped.isEmpty()) {
                        msg.append("\nSkipped (no Modrinth ID): ").append(String.join(", ", skipped));
                    }
                    if (!finalFailed.isEmpty()) {
                        msg.append("\nFailed: ").append(String.join(", ", finalFailed));
                    }
                    if (finalFailed.isEmpty() && skipped.isEmpty()) {
                        notifications.success("Import complete", msg.toString());
                    } else {
                        notifications.warning("Import finished with issues", msg.toString());
                    }
                    animateOverlayHide(importOverlay);
                });
            }, "mod-import").start();
        });
        bottomBar.add(importBtn);

        JPanel bottomWrap = new JPanel(new BorderLayout());
        bottomWrap.setOpaque(false);
        bottomWrap.add(progressLbl, BorderLayout.WEST);
        bottomWrap.add(bottomBar, BorderLayout.EAST);
        bottomWrap.setBorder(new EmptyBorder(0, 18, 0, 0));
        importOverlay.add(bottomWrap, BorderLayout.SOUTH);

        // Position and animate
        positionModOverlay(importOverlay);
        animateOverlayShow(importOverlay);
    }

    // ── Overlay positioning + show/hide animation helpers ────────────────────
    private void positionModOverlay(RoundedPanel overlay) {
        int width = Math.min(620, layeredPane.getWidth() - 80);
        int height = Math.min(560, layeredPane.getHeight() - 80);
        int x = (layeredPane.getWidth() - width) / 2;
        int y = (layeredPane.getHeight() - height) / 2;
        overlay.setBounds(x, y, width, height);
    }

    private void animateOverlayShow(RoundedPanel overlay) {
        Rectangle target = overlay.getBounds();
        int slide = 18;
        overlay.setBounds(target.x, target.y + slide, target.width, target.height);
        overlay.setAlpha(0f);
        layeredPane.setLayer(overlay, JLayeredPane.MODAL_LAYER);
        layeredPane.add(overlay);
        overlay.setVisible(true);
        long start = System.currentTimeMillis();
        int duration = 220;
        javax.swing.Timer[] holder = new javax.swing.Timer[1];
        holder[0] = new javax.swing.Timer(15, e -> {
            float p = Math.min(1f, (System.currentTimeMillis() - start) / (float) duration);
            float eased = 1 - (1 - p) * (1 - p);
            overlay.setAlpha(eased);
            int y = target.y + Math.round(slide * (1 - eased));
            overlay.setBounds(target.x, y, target.width, target.height);
            if (p >= 1f) {
                overlay.setBounds(target);
                overlay.setAlpha(1f);
                holder[0].stop();
            }
        });
        holder[0].start();
    }

    private void animateOverlayHide(RoundedPanel overlay) {
        if (overlay == null || !overlay.isVisible()) return;
        Rectangle target = overlay.getBounds();
        int slide = 14;
        long start = System.currentTimeMillis();
        int duration = 180;
        javax.swing.Timer[] holder = new javax.swing.Timer[1];
        holder[0] = new javax.swing.Timer(15, e -> {
            float p = Math.min(1f, (System.currentTimeMillis() - start) / (float) duration);
            float eased = p * p;
            overlay.setAlpha(1f - eased);
            int y = target.y + Math.round(slide * eased);
            overlay.setBounds(target.x, y, target.width, target.height);
            if (p >= 1f) {
                overlay.setVisible(false);
                layeredPane.remove(overlay);
                layeredPane.repaint();
                holder[0].stop();
            }
        });
        holder[0].start();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FIX MODS — Custom themed dropdown
    // ══════════════════════════════════════════════════════════════════════════
    private void buildFixModsDropdown() {
        // Read theme colors live from settings
        com.launcher.model.LauncherSettings s = com.launcher.manager.SettingsManager.getInstance().getSettings();
        Color panelBg = hexToColor(s.panelBgColor, new Color(19, 19, 26));
        Color textColor = hexToColor(s.textColor, new Color(226, 226, 234));
        Color accent = hexToColor(s.accentColor, new Color(16, 185, 129));

        fixModsDropdown = new RoundedPanel(14, new Color(panelBg.getRed(), panelBg.getGreen(), panelBg.getBlue(), 245),
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 80)); // accent border tint
        fixModsDropdown.putClientProperty("keepCustomBg", Boolean.TRUE);
        fixModsDropdown.setLayout(new BoxLayout(fixModsDropdown, BoxLayout.Y_AXIS));
        fixModsDropdown.setBorder(new EmptyBorder(8, 0, 8, 0));
        fixModsDropdown.setFrostedGlass(layeredPane, 6, new Color(panelBg.getRed(), panelBg.getGreen(), panelBg.getBlue(), 160));

        // ── Title ──
        JLabel titleLbl = new JLabel("  🔧  Fix Mods");
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        titleLbl.setForeground(accent); // accent color
        titleLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleLbl.setBorder(new EmptyBorder(4, 14, 8, 14));
        fixModsDropdown.add(titleLbl);

        // Separator
        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(255, 255, 255, 20));
        sep.setBackground(new Color(0, 0, 0, 0));
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        fixModsDropdown.add(sep);
        fixModsDropdown.add(Box.createVerticalStrut(4));

        // ── Dropdown items ──
        String[][] items = {
            {"⬆  Update All Mods", "Update all mods to their latest versions"},
            {"🔗  Install Dependencies", "Find and install missing required dependencies"},
            {"🧹  Deduplicate Mods", "Remove duplicate mod files (keeps newest)"}
        };
        Runnable[] actions = {
            () -> updateAllBtn.doClick(),
            () -> installDependenciesBtn.doClick(),
            () -> dedupeModsBtn.doClick()
        };
        Color hoverBg = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 30); // accent hover tint

        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            JPanel row = new JPanel(new BorderLayout()) {
                @Override
                protected void paintComponent(Graphics g) {
                    if (Boolean.TRUE.equals(getClientProperty("hovered"))) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(hoverBg);
                        g2.fillRoundRect(4, 0, getWidth() - 8, getHeight(), 10, 10);
                        g2.dispose();
                    }
                    super.paintComponent(g);
                }
            };
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(8, 14, 8, 14));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JLabel nameLbl = new JLabel(items[i][0]);
            nameLbl.setFont(new Font("SansSerif", Font.BOLD, 12));
            nameLbl.setForeground(textColor);

            JLabel descLbl = new JLabel(items[i][1]);
            descLbl.setFont(new Font("SansSerif", Font.PLAIN, 10));
            descLbl.setForeground(tint(textColor, -60));

            JPanel textPanel = new JPanel();
            textPanel.setOpaque(false);
            textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
            textPanel.add(nameLbl);
            textPanel.add(descLbl);
            row.add(textPanel, BorderLayout.CENTER);

            // Hover effect
            row.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    row.putClientProperty("hovered", Boolean.TRUE);
                    nameLbl.setForeground(accent);
                    row.repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    row.putClientProperty("hovered", Boolean.FALSE);
                    com.launcher.model.LauncherSettings ls = com.launcher.manager.SettingsManager.getInstance().getSettings();
                    nameLbl.setForeground(hexToColor(ls.textColor, new Color(226, 226, 234)));
                    row.repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    hideFixModsDropdown();
                    // Slight delay so animation starts before action
                    javax.swing.Timer t = new javax.swing.Timer(100, ev -> {
                        ((javax.swing.Timer) ev.getSource()).stop();
                        actions[idx].run();
                    });
                    t.setRepeats(false);
                    t.start();
                }
            });

            fixModsDropdown.add(row);
        }

        fixModsDropdown.setVisible(false);
        layeredPane.add(fixModsDropdown, JLayeredPane.MODAL_LAYER);

        // Auto-dismiss when clicking outside the dropdown
        layeredPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (fixModsDropdown != null && fixModsDropdown.isVisible()) {
                    Point p = e.getPoint();
                    if (!fixModsDropdown.getBounds().contains(p)) {
                        hideFixModsDropdown();
                    }
                }
            }
        });
    }

    private void positionFixModsDropdown() {
        if (fixModsDropdown == null || fixModsBtn == null) return;
        // Position below the Fix Mods button
        Point btnLoc = SwingUtilities.convertPoint(fixModsBtn, 0, fixModsBtn.getHeight(), layeredPane);
        int dropW = 280;
        int dropH = 210;
        int x = Math.max(8, Math.min(btnLoc.x, layeredPane.getWidth() - dropW - 8));
        int y = btnLoc.y + 4;
        // If it would go off the bottom, show above the button instead
        if (y + dropH > layeredPane.getHeight() - 8) {
            y = btnLoc.y - fixModsBtn.getHeight() - dropH - 4;
        }
        fixModsDropdown.setBounds(x, y, dropW, dropH);
    }

    private void toggleFixModsDropdown() {
        // Lazy-build on first use (layeredPane isn't ready during buildModsArea)
        if (fixModsDropdown == null) buildFixModsDropdown();
        if (fixModsDropdown == null) return;
        if (fixModsDropdown.isVisible()) {
            hideFixModsDropdown();
        } else {
            showFixModsDropdown();
        }
    }

    private void showFixModsDropdown() {
        if (fixModsDropdown == null) return;
        positionFixModsDropdown();
        Rectangle target = fixModsDropdown.getBounds();
        int slide = 10;
        fixModsDropdown.setBounds(target.x, target.y - slide, target.width, target.height);
        fixModsDropdown.setAlpha(0f);
        layeredPane.setLayer(fixModsDropdown, JLayeredPane.MODAL_LAYER);
        fixModsDropdown.setVisible(true);
        long start = System.currentTimeMillis();
        int duration = 160;
        javax.swing.Timer[] holder = new javax.swing.Timer[1];
        holder[0] = new javax.swing.Timer(15, e -> {
            float p = Math.min(1f, (System.currentTimeMillis() - start) / (float) duration);
            float eased = 1 - (1 - p) * (1 - p); // ease-out
            fixModsDropdown.setAlpha(eased);
            int y = target.y - Math.round(slide * (1 - eased));
            fixModsDropdown.setBounds(target.x, y, target.width, target.height);
            if (p >= 1f) {
                fixModsDropdown.setBounds(target);
                fixModsDropdown.setAlpha(1f);
                holder[0].stop();
            }
        });
        holder[0].start();
    }

    private void hideFixModsDropdown() {
        if (fixModsDropdown == null || !fixModsDropdown.isVisible()) return;
        Rectangle from = fixModsDropdown.getBounds();
        float startAlpha = fixModsDropdown.getAlpha();
        int slide = 8;
        long start = System.currentTimeMillis();
        int duration = 130;
        javax.swing.Timer[] holder = new javax.swing.Timer[1];
        holder[0] = new javax.swing.Timer(15, e -> {
            float p = Math.min(1f, (System.currentTimeMillis() - start) / (float) duration);
            fixModsDropdown.setAlpha(startAlpha * (1 - p));
            fixModsDropdown.setBounds(from.x, from.y - Math.round(slide * p), from.width, from.height);
            if (p >= 1f) {
                holder[0].stop();
                fixModsDropdown.setVisible(false);
                fixModsDropdown.setAlpha(1f);
                fixModsDropdown.setBounds(from);
            }
        });
        holder[0].start();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ACCOUNTS — Custom themed dropdown (mirrors the Fix Mods dropdown above):
    // lists every saved offline account with an "✕" to delete it, plus a row to
    // create a new offline account.
    // ══════════════════════════════════════════════════════════════════════════
    private boolean accountDropdownClickAwayAdded = false;

    private void buildAccountDropdown() {
        com.launcher.model.LauncherSettings s = com.launcher.manager.SettingsManager.getInstance().getSettings();
        Color panelBg = hexToColor(s.panelBgColor, new Color(19, 19, 26));
        Color textColor = hexToColor(s.textColor, new Color(226, 226, 234));
        Color accent = hexToColor(s.accentColor, new Color(16, 185, 129));

        accountDropdown = new RoundedPanel(14, new Color(panelBg.getRed(), panelBg.getGreen(), panelBg.getBlue(), 245),
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 80));
        accountDropdown.putClientProperty("keepCustomBg", Boolean.TRUE);
        accountDropdown.setLayout(new BoxLayout(accountDropdown, BoxLayout.Y_AXIS));
        accountDropdown.setBorder(new EmptyBorder(8, 0, 8, 0));
        accountDropdown.setFrostedGlass(layeredPane, 6, new Color(panelBg.getRed(), panelBg.getGreen(), panelBg.getBlue(), 160));

        JLabel titleLbl = new JLabel("  👤  Accounts");
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        titleLbl.setForeground(accent);
        titleLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleLbl.setBorder(new EmptyBorder(4, 14, 8, 14));
        accountDropdown.add(titleLbl);

        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(255, 255, 255, 20));
        sep.setBackground(new Color(0, 0, 0, 0));
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        accountDropdown.add(sep);
        accountDropdown.add(Box.createVerticalStrut(4));

        Color hoverBg = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 30);
        java.util.List<Account> accounts = accountManager.getAccounts();
        Account active = accountManager.getActiveAccount().orElse(null);

        if (accounts.isEmpty()) {
            JLabel emptyLbl = new JLabel("  No accounts yet");
            emptyLbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
            emptyLbl.setForeground(tint(textColor, -60));
            emptyLbl.setBorder(new EmptyBorder(6, 14, 10, 14));
            emptyLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            accountDropdown.add(emptyLbl);
        }

        for (Account acc : accounts) {
            JPanel row = new JPanel(new BorderLayout()) {
                @Override
                protected void paintComponent(Graphics g) {
                    if (Boolean.TRUE.equals(getClientProperty("hovered"))) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(hoverBg);
                        g2.fillRoundRect(4, 0, getWidth() - 8, getHeight(), 10, 10);
                        g2.dispose();
                    }
                    super.paintComponent(g);
                }
            };
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(6, 14, 6, 10));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            boolean isActive = active != null && active.equals(acc);
            String label = s.hideUsername ? "●●●●●" : acc.username;
            JLabel nameLbl = new JLabel(label, accountAvatarIcon(label), SwingConstants.LEFT);
            nameLbl.setIconTextGap(8);
            nameLbl.setFont(new Font("SansSerif", isActive ? Font.BOLD : Font.PLAIN, 12));
            nameLbl.setForeground(isActive ? accent : textColor);
            row.add(nameLbl, BorderLayout.CENTER);

            JButton delBtn = new JButton("✕");
            delBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
            delBtn.setFocusPainted(false);
            delBtn.setForeground(new Color(239, 68, 68));
            delBtn.setBackground(new Color(239, 68, 68, 35));
            delBtn.setPreferredSize(new Dimension(22, 22));
            delBtn.putClientProperty("JButton.arc", 22);
            delBtn.setToolTipText("Delete account");
            delBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            delBtn.addActionListener(e -> {
                accountManager.remove(acc);
                refreshAccounts();
                notifications.warning("Account removed", "Removed account " + acc.username);
                hideAccountDropdown();
            });
            JPanel delWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            delWrap.setOpaque(false);
            delWrap.add(delBtn);
            row.add(delWrap, BorderLayout.EAST);

            row.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    row.putClientProperty("hovered", Boolean.TRUE);
                    row.repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    row.putClientProperty("hovered", Boolean.FALSE);
                    row.repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    accountBox.setSelectedItem(acc);
                    accountManager.setActiveAccount(acc);
                    if (accountBtn != null) {
                        accountBtn.setText(accountButtonLabel());
                    }
                    hideAccountDropdown();
                }
            });

            accountDropdown.add(row);
        }

        accountDropdown.add(Box.createVerticalStrut(4));
        JSeparator sep2 = new JSeparator();
        sep2.setForeground(new Color(255, 255, 255, 20));
        sep2.setBackground(new Color(0, 0, 0, 0));
        sep2.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        accountDropdown.add(sep2);
        accountDropdown.add(Box.createVerticalStrut(4));

        JPanel createRow = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                if (Boolean.TRUE.equals(getClientProperty("hovered"))) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(hoverBg);
                    g2.fillRoundRect(4, 0, getWidth() - 8, getHeight(), 10, 10);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        createRow.setOpaque(false);
        createRow.setBorder(new EmptyBorder(8, 14, 8, 14));
        createRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        createRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        createRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel createLbl = new JLabel("＋  Create new offline account");
        createLbl.setFont(new Font("SansSerif", Font.BOLD, 12));
        createLbl.setForeground(accent);
        createRow.add(createLbl, BorderLayout.CENTER);

        createRow.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                createRow.putClientProperty("hovered", Boolean.TRUE);
                createRow.repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                createRow.putClientProperty("hovered", Boolean.FALSE);
                createRow.repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                hideAccountDropdown();
                javax.swing.Timer t = new javax.swing.Timer(100, ev -> {
                    ((javax.swing.Timer) ev.getSource()).stop();
                    promptCreateOfflineAccount();
                });
                t.setRepeats(false);
                t.start();
            }
        });

        accountDropdown.add(createRow);

        accountDropdown.setVisible(false);
        layeredPane.add(accountDropdown, JLayeredPane.MODAL_LAYER);

        // Only ever register this click-away listener once — buildAccountDropdown()
        // is re-run every time the dropdown opens (so it reflects any accounts
        // added/removed since), but the outside-click handler itself only needs to
        // exist a single time.
        if (!accountDropdownClickAwayAdded) {
            accountDropdownClickAwayAdded = true;
            layeredPane.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (accountDropdown != null && accountDropdown.isVisible()) {
                        Point p = e.getPoint();
                        if (!accountDropdown.getBounds().contains(p)) {
                            hideAccountDropdown();
                        }
                    }
                }
            });
        }
    }

    private void positionAccountDropdown() {
        if (accountDropdown == null || accountBtn == null) return;
        Point btnLoc = SwingUtilities.convertPoint(accountBtn, 0, accountBtn.getHeight(), layeredPane);
        int dropW = 260;
        int dropH = Math.max(120, Math.min(400, accountDropdown.getPreferredSize().height));
        int x = Math.max(8, Math.min(btnLoc.x + accountBtn.getWidth() - dropW, layeredPane.getWidth() - dropW - 8));
        int y = btnLoc.y + 4;
        if (y + dropH > layeredPane.getHeight() - 8) {
            y = btnLoc.y - accountBtn.getHeight() - dropH - 4;
        }
        accountDropdown.setBounds(x, y, dropW, dropH);
    }

    private void toggleAccountDropdown() {
        if (accountDropdown != null && accountDropdown.isVisible()) {
            hideAccountDropdown();
            return;
        }
        // Rebuild every time so newly added/removed accounts are always reflected.
        if (accountDropdown != null) {
            layeredPane.remove(accountDropdown);
            accountDropdown = null;
        }
        buildAccountDropdown();
        if (accountDropdown == null) return;
        showAccountDropdown();
    }

    private void showAccountDropdown() {
        if (accountDropdown == null) return;
        positionAccountDropdown();
        Rectangle target = accountDropdown.getBounds();
        int slide = 10;
        accountDropdown.setBounds(target.x, target.y - slide, target.width, target.height);
        accountDropdown.setAlpha(0f);
        layeredPane.setLayer(accountDropdown, JLayeredPane.MODAL_LAYER);
        accountDropdown.setVisible(true);
        long start = System.currentTimeMillis();
        int duration = 160;
        javax.swing.Timer[] holder = new javax.swing.Timer[1];
        holder[0] = new javax.swing.Timer(15, e -> {
            float p = Math.min(1f, (System.currentTimeMillis() - start) / (float) duration);
            float eased = 1 - (1 - p) * (1 - p);
            accountDropdown.setAlpha(eased);
            int y = target.y - Math.round(slide * (1 - eased));
            accountDropdown.setBounds(target.x, y, target.width, target.height);
            if (p >= 1f) {
                accountDropdown.setBounds(target);
                accountDropdown.setAlpha(1f);
                holder[0].stop();
            }
        });
        holder[0].start();
    }

    private void hideAccountDropdown() {
        if (accountDropdown == null || !accountDropdown.isVisible()) return;
        Rectangle from = accountDropdown.getBounds();
        float startAlpha = accountDropdown.getAlpha();
        int slide = 8;
        long start = System.currentTimeMillis();
        int duration = 130;
        javax.swing.Timer[] holder = new javax.swing.Timer[1];
        holder[0] = new javax.swing.Timer(15, e -> {
            float p = Math.min(1f, (System.currentTimeMillis() - start) / (float) duration);
            accountDropdown.setAlpha(startAlpha * (1 - p));
            accountDropdown.setBounds(from.x, from.y - Math.round(slide * p), from.width, from.height);
            if (p >= 1f) {
                holder[0].stop();
                accountDropdown.setVisible(false);
                accountDropdown.setAlpha(1f);
                accountDropdown.setBounds(from);
            }
        });
        holder[0].start();
    }

    /**
     * Builds the "Create Offline Account" popover — styled the same way as the
     * Downloads window (buildDownloadsPopover): a frosted-glass RoundedPanel with
     * a header (title + close ✕) and content below, instead of a plain OS dialog.
     */
    private RoundedPanel buildCreateAccountPopover() {
        com.launcher.model.LauncherSettings settings = com.launcher.manager.SettingsManager.getInstance().getSettings();
        Color textColor = hexToColor(settings.textColor, new Color(226, 226, 234));
        Color mutedTextColor = hexToColor("#9696a0", new Color(150, 150, 160));
        Color accent = hexToColor(settings.accentColor, new Color(16, 185, 129));
        Color errorColor = hexToColor("#ef4444", Color.RED);

        RoundedPanel popover = new RoundedPanel(18, new Color(20, 20, 26, 250), new Color(255, 255, 255, 34));
        popover.putClientProperty("keepCustomBg", Boolean.TRUE);
        popover.setLayout(new BorderLayout());
        popover.setFrostedGlass(layeredPane, 8, new Color(12, 12, 16, 150));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(16, 20, 10, 14));

        JLabel title = new JLabel("Create Offline Account");
        title.setFont(new Font("SansSerif", Font.BOLD, 18));
        title.setForeground(textColor);
        header.add(title, BorderLayout.WEST);

        JButton closeBtn = new JButton("\u2715");
        closeBtn.setToolTipText("Close");
        closeBtn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        closeBtn.setFocusPainted(false);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setOpaque(false);
        closeBtn.setForeground(textColor);
        closeBtn.setMargin(new Insets(4, 10, 4, 10));
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.addActionListener(e -> hideCreateAccountPopover());
        header.add(closeBtn, BorderLayout.EAST);

        popover.add(header, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(new EmptyBorder(4, 20, 20, 20));

        JLabel sub = new JLabel("<html>Offline accounts work for singleplayer and offline-mode servers.</html>");
        sub.setFont(new Font("SansSerif", Font.PLAIN, 13));
        sub.setForeground(mutedTextColor);
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(sub);
        body.add(Box.createVerticalStrut(18));

        JLabel userLabel = new JLabel("Username");
        userLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        userLabel.setForeground(textColor);
        userLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(userLabel);
        body.add(Box.createVerticalStrut(8));

        createAccountUsernameField = new CustomTextField();
        createAccountUsernameField.putClientProperty("JTextField.placeholderText", "Enter a username...");
        createAccountUsernameField.setFont(new Font("SansSerif", Font.PLAIN, 14));
        createAccountUsernameField.setAlignmentX(Component.LEFT_ALIGNMENT);
        createAccountUsernameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        createAccountUsernameField.setPreferredSize(new Dimension(100, 42));
        body.add(createAccountUsernameField);
        body.add(Box.createVerticalStrut(12));

        JLabel note = new JLabel("<html><body style='width: 380px;'>⚠  Microsoft authentication is not supported. Use the in-game account switcher if you need it.</body></html>");
        note.setFont(new Font("SansSerif", Font.PLAIN, 12));
        note.setForeground(mutedTextColor);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(note);

        createAccountErrorLabel = new JLabel(" ");
        createAccountErrorLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        createAccountErrorLabel.setForeground(errorColor);
        createAccountErrorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(createAccountErrorLabel);
        body.add(Box.createVerticalStrut(14));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnRow.setOpaque(false);
        btnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.setForeground(textColor);
        cancelBtn.setFont(new Font("SansSerif", Font.PLAIN, 13));
        cancelBtn.setPreferredSize(new Dimension(100, 36));
        cancelBtn.setFocusPainted(false);
        cancelBtn.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
        cancelBtn.addActionListener(e -> hideCreateAccountPopover());
        btnRow.add(cancelBtn);

        JButton createBtn = new JButton("Create Account");
        createBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
        createBtn.setPreferredSize(new Dimension(170, 36));
        createBtn.setForeground(Color.WHITE);
        createBtn.setBackground(accent);
        createBtn.setFocusPainted(false);
        createBtn.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
        createBtn.addActionListener(e -> submitCreateOfflineAccount());
        btnRow.add(createBtn);

        body.add(btnRow);
        popover.add(body, BorderLayout.CENTER);

        createAccountUsernameField.addActionListener(e -> createBtn.doClick());

        return popover;
    }

    /** Centers the create-account popover over the launcher window, like a modal. */
    private void positionCreateAccountPopover() {
        if (createAccountPopover == null) return;
        int width = 460;
        int height = createAccountPopover.getPreferredSize().height;
        height = Math.max(300, Math.min(height, layeredPane.getHeight() - 32));
        int x = (layeredPane.getWidth() - width) / 2;
        int y = (layeredPane.getHeight() - height) / 3;
        createAccountPopover.setBounds(Math.max(8, x), Math.max(8, y), width, height);
    }

    /** Opens the themed "Create Offline Account" popover (replaces the old OS input dialog). */
    private void promptCreateOfflineAccount() {
        if (createAccountPopover != null) {
            layeredPane.remove(createAccountPopover);
            createAccountPopover = null;
        }
        createAccountPopover = buildCreateAccountPopover();
        layeredPane.add(createAccountPopover, JLayeredPane.MODAL_LAYER);
        positionCreateAccountPopover();
        Rectangle target = createAccountPopover.getBounds();
        int slide = 14;
        createAccountPopover.setBounds(target.x, target.y - slide, target.width, target.height);
        createAccountPopover.setAlpha(0f);
        createAccountPopover.setVisible(true);
        long start = System.currentTimeMillis();
        int duration = 190;
        javax.swing.Timer[] holder = new javax.swing.Timer[1];
        holder[0] = new javax.swing.Timer(15, e -> {
            float p = Math.min(1f, (System.currentTimeMillis() - start) / (float) duration);
            float eased = 1 - (1 - p) * (1 - p);
            createAccountPopover.setAlpha(eased);
            int y = target.y - Math.round(slide * (1 - eased));
            createAccountPopover.setBounds(target.x, y, target.width, target.height);
            if (p >= 1f) {
                createAccountPopover.setBounds(target);
                createAccountPopover.setAlpha(1f);
                holder[0].stop();
                createAccountUsernameField.requestFocusInWindow();
            }
        });
        holder[0].start();
    }

    /** Fades + slides the create-account popover out, then hides it. */
    private void hideCreateAccountPopover() {
        if (createAccountPopover == null || !createAccountPopover.isVisible()) return;
        Rectangle from = createAccountPopover.getBounds();
        float startAlpha = createAccountPopover.getAlpha();
        int slide = 10;
        long start = System.currentTimeMillis();
        int duration = 150;
        javax.swing.Timer[] holder = new javax.swing.Timer[1];
        holder[0] = new javax.swing.Timer(15, e -> {
            float p = Math.min(1f, (System.currentTimeMillis() - start) / (float) duration);
            createAccountPopover.setAlpha(startAlpha * (1 - p));
            createAccountPopover.setBounds(from.x, from.y - Math.round(slide * p), from.width, from.height);
            if (p >= 1f) {
                holder[0].stop();
                createAccountPopover.setVisible(false);
            }
        });
        holder[0].start();
    }

    /** Validates the username field and creates the offline account. */
    private void submitCreateOfflineAccount() {
        if (createAccountUsernameField == null) return;
        String u = createAccountUsernameField.getText().trim();
        if (u.isEmpty()) {
            if (createAccountErrorLabel != null) createAccountErrorLabel.setText("Username cannot be empty.");
            return;
        }
        try {
            Account acc = new com.launcher.auth.OfflineAuthService().login(u);
            accountManager.addOrUpdate(acc);
            accountManager.setActiveAccount(acc);
            refreshAccounts();
            notifications.success("Account added", "Added offline account: " + acc.username);
            hideCreateAccountPopover();
        } catch (Exception ex) {
            if (createAccountErrorLabel != null) createAccountErrorLabel.setText(ex.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DISCOVER TAB (Modrinth)
    // ══════════════════════════════════════════════════════════════════════════
    // Discover tab palette — a slightly bluer, more saturated dark theme than the
    // rest of the app so the tab feels like a distinct "store" surface.
    // These used to be `static final` hardcoded constants, which is why the
    // Discover tab
    // never reacted to the Appearance settings (Accent/Background/Text color
    // pickers) —
    // it simply always painted with these fixed values. They're now mutable and get
    // recomputed from the user's settings in applyDiscoverPalette(), called from
    // applyTheme().
    private static Color DISC_BG = new Color(19, 20, 28);
    private static Color DISC_SURFACE = new Color(27, 28, 38);
    private static Color DISC_SURFACE_HOVER = new Color(34, 35, 48);
    private static Color DISC_BORDER = new Color(50, 51, 66);
    private static Color DISC_BORDER_HOVER = new Color(124, 109, 255);
    private static Color DISC_ACCENT = new Color(124, 109, 255);
    private static Color DISC_TEXT = new Color(238, 238, 244);
    private static Color DISC_TEXT_DIM = new Color(150, 150, 168);

    private JPanel buildDiscoverArea() {
        JPanel p = new JPanel(new BorderLayout(0, 12));
        // Transparent so the app's own background shows through behind the Discover
        // tab,
        // instead of this panel painting its own solid dark rectangle over it.
        p.setOpaque(false);
        p.putClientProperty("keepCustomBg", Boolean.TRUE);
        p.setBorder(new EmptyBorder(18, 20, 16, 20));
        discoverRootPanel = p;

        // ── Top panel (rebuilt from scratch) ────────────────────────────────
        // Three stacked rows inside one rounded glass card:
        //   1. Title + subtitle
        //   2. Mods/Resource Packs toggle (left) and Search/Refresh buttons (right)
        //   3. The Modrinth search text field, full width
        // Built with GridBagLayout instead of nested BorderLayouts so every row
        // is guaranteed a real, non-zero width/height regardless of its
        // neighbors' content.
        RoundedPanel topCard = new RoundedPanel(22, new Color(255, 255, 255, 9), new Color(255, 255, 255, 16));
        topCard.putClientProperty("keepCustomBg", Boolean.TRUE);
        topCard.setLayout(new GridBagLayout());
        topCard.setBorder(new EmptyBorder(16, 18, 16, 18));
        discoverFiltersPanel = topCard;

        GridBagConstraints rowGbc = new GridBagConstraints();
        rowGbc.gridx = 0;
        rowGbc.fill = GridBagConstraints.HORIZONTAL;
        rowGbc.weightx = 1;

        // Row 1: icon + title/subtitle.
        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        JLabel iconLbl = new JLabel("\uD83E\uDDED");
        iconLbl.setFont(new Font("SansSerif", Font.PLAIN, 22));
        iconLbl.setBorder(new EmptyBorder(0, 0, 0, 10));
        JPanel titleStack = new JPanel();
        titleStack.setOpaque(false);
        titleStack.setLayout(new BoxLayout(titleStack, BoxLayout.Y_AXIS));
        JLabel titleLbl = new JLabel("Discover");
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 22));
        titleLbl.setForeground(DISC_TEXT);
        discoverTitleLbl = titleLbl;
        JLabel subtitleLbl = new JLabel("Browse mods & resource packs on Modrinth");
        subtitleLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        subtitleLbl.setForeground(DISC_TEXT_DIM);
        discoverSubtitleLbl = subtitleLbl;
        subtitleLbl.setBorder(new EmptyBorder(2, 1, 0, 0));
        titleStack.add(titleLbl);
        titleStack.add(subtitleLbl);
        titleRow.add(iconLbl, BorderLayout.WEST);
        titleRow.add(titleStack, BorderLayout.CENTER);

        rowGbc.gridy = 0;
        rowGbc.insets = new Insets(0, 0, 14, 0);
        topCard.add(titleRow, rowGbc);

        // Row 2: Mods/Resource Packs toggle on the left, Search/Refresh buttons
        // on the right — both styled as the same kind of segmented pill row.
        discoverModsToggle = new DiscRoundToggleButton("Mods", true, DISC_BUTTON_ARC);
        discoverPacksToggle = new DiscRoundToggleButton("Resource Packs", false, DISC_BUTTON_ARC);
        styleDiscoverSegment(discoverModsToggle);
        styleDiscoverSegment(discoverPacksToggle);
        ButtonGroup discoverGroup = new ButtonGroup();
        discoverGroup.add(discoverModsToggle);
        discoverGroup.add(discoverPacksToggle);
        JPanel typeSegment = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
        typeSegment.setOpaque(false);
        typeSegment.add(discoverModsToggle);
        typeSegment.add(discoverPacksToggle);

        discoverSearchBtn = new DiscRoundButton("Search", DISC_BUTTON_ARC);
        styleDiscoverPrimaryButton(discoverSearchBtn);
        discoverRefreshBtn = new DiscRoundButton("↻ Refresh", DISC_BUTTON_ARC);
        styleDiscoverGhostButton(discoverRefreshBtn);
        JPanel actionSegment = new JPanel(new WrapLayout(FlowLayout.RIGHT, 4, 4));
        actionSegment.setOpaque(false);
        actionSegment.add(discoverSearchBtn);
        actionSegment.add(discoverRefreshBtn);

        JPanel controlsRow = new JPanel(new BorderLayout());
        controlsRow.setOpaque(false);
        controlsRow.add(typeSegment, BorderLayout.WEST);
        controlsRow.add(actionSegment, BorderLayout.EAST);

        rowGbc.gridy = 1;
        rowGbc.insets = new Insets(0, 0, 12, 0);
        topCard.add(controlsRow, rowGbc);

        // Row 3: the actual Modrinth search text field, full width. Kept as a
        // plain (non-frosted-glass) rounded pill — a live frosted-glass backdrop
        // here previously let the scrolling result cards bleed/ghost through the
        // pill — but with a solid-enough fill/border so it's always clearly
        // visible instead of nearly-invisible at very low alpha.
        discoverSearchField = new JTextField();
        discoverSearchField.putClientProperty("JTextField.placeholderText", "🔍  Search on Modrinth…");
        discoverSearchField.setFont(new Font("SansSerif", Font.PLAIN, 13));
        discoverSearchField.setForeground(DISC_TEXT);
        discoverSearchField.setCaretColor(DISC_TEXT);
        discoverSearchField.setOpaque(false);
        discoverSearchField.setBorder(new EmptyBorder(6, 4, 6, 4));

        RoundedPanel discoverSearchWrap = new RoundedPanel(18, new Color(255, 255, 255, 24), new Color(255, 255, 255, 60));
        discoverSearchWrap.putClientProperty("keepCustomBg", Boolean.TRUE);
        discoverSearchWrap.setLayout(new BorderLayout());
        discoverSearchWrap.setBorder(new EmptyBorder(4, 14, 4, 14));
        discoverSearchWrap.add(discoverSearchField, BorderLayout.CENTER);
        discoverSearchWrap.setPreferredSize(new Dimension(400, 38));
        discoverSearchWrap.setMinimumSize(new Dimension(120, 38));
        this.discoverSearchWrapPanel = discoverSearchWrap;

        rowGbc.gridy = 2;
        rowGbc.insets = new Insets(0, 0, 0, 0);
        topCard.add(discoverSearchWrap, rowGbc);

        JPanel topSection = new JPanel(new BorderLayout());
        topSection.setOpaque(false);
        topSection.setBorder(new EmptyBorder(0, 0, 14, 0));
        topSection.add(topCard, BorderLayout.CENTER);
        p.add(topSection, BorderLayout.NORTH);

        // ── Results pane (WrapLayout inside JScrollPane) ────────────────────
        // Must be built via wrapScrollablePanel (Scrollable + tracks viewport
        // width) or the viewport never constrains its width and every card
        // gets crammed onto one clipped row instead of wrapping.
        discoverResultsPane = WrapLayout.wrapScrollablePanel(FlowLayout.CENTER, 14, 14);
        discoverResultsPane.setOpaque(false);
        // Small top/bottom breathing room so the first/last row of cards never
        // renders flush against the filter toolbar (NORTH) or pagination bar
        // (SOUTH) — without this, rounded cards right at the scroll edge can look
        // like they're overlapping/tucked under those bars.
        discoverResultsPane.setBorder(new EmptyBorder(6, 0, 6, 0));
        JScrollPane scroll = new JScrollPane(discoverResultsPane);
        com.launcher.ui.SmoothScroll.install(scroll);
        discoverScrollPane = scroll;
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        scroll.setBorder(null);
        // Fixes a real Swing z-order/ghosting bug: JViewport's default
        // BLIT_SCROLL_MODE copies (blits) the previously painted pixels and only
        // repaints the newly-exposed strip when scrolling, which assumes fully
        // opaque content. With translucent/custom-painted RoundedPanel cards over
        // a non-opaque viewport, that blit can leave stale card pixels smeared
        // into the region under the (translucent) filter toolbar or over the
        // pagination bar. SIMPLE_SCROLL_MODE always fully repaints the viewport
        // from scratch on every scroll, which is a little more work but removes
        // the layering/ghosting artifacts entirely.
        scroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        // Belt-and-braces fix for the same class of bug: explicitly repaint the
        // top card on every scroll tick. Cards scrolling up pass directly beneath
        // the (translucent) top card's screen region; without this, a stray
        // partially-loaded icon/image can visibly show through for a frame before
        // the next full repaint catches up. Forcing the top card to redraw itself
        // on scroll guarantees anything now "underneath" it is immediately hidden.
        scroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            topCard.repaint();
        });
        // Re-layout on resize so WrapLayout recalculates row heights
        scroll.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                discoverResultsPane.revalidate();
            }
        });
        p.add(scroll, BorderLayout.CENTER);

        // Keep already-loaded results visible when the user switches away to another
        // tab and back — force a re-validate/repaint whenever this panel becomes
        // showing again instead of relying on results still being freshly loaded.
        p.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && p.isShowing()) {
                discoverResultsPane.revalidate();
                discoverResultsPane.repaint();
                scroll.revalidate();
                scroll.repaint();
            }
        });

        // ── Bottom pagination row ───────────────────────────────────────────
        JPanel bottomRow = new JPanel(new GridBagLayout());
        bottomRow.setOpaque(false);
        bottomRow.setBorder(new EmptyBorder(6, 2, 0, 2));

        JPanel pag = new JPanel(new WrapLayout(FlowLayout.CENTER, 6, 4));
        pag.setOpaque(false);
        discoverPrevPageBtn = new DiscRoundButton("‹ Prev", DISC_BUTTON_ARC);
        discoverNextPageBtn = new DiscRoundButton("Next ›", DISC_BUTTON_ARC);
        styleDiscoverNavButton(discoverPrevPageBtn);
        styleDiscoverNavButton(discoverNextPageBtn);
        discoverPageLabel = new JLabel("Page 1");
        discoverPageLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        discoverPageLabel.setForeground(DISC_TEXT_DIM);
        discoverPrevPageBtn.setEnabled(false);
        discoverNextPageBtn.setEnabled(false);
        pag.add(discoverPrevPageBtn);
        pag.add(discoverPageLabel);
        pag.add(discoverNextPageBtn);

        GridBagConstraints bc = new GridBagConstraints();
        bc.gridy = 0;
        bc.weighty = 1;
        bc.gridx = 0;
        bc.weightx = 1;
        bc.fill = GridBagConstraints.NONE;
        bc.anchor = GridBagConstraints.CENTER;
        bottomRow.add(pag, bc);
        p.add(bottomRow, BorderLayout.SOUTH);

        // The filter toolbar and pagination bar both use WrapLayout now (see
        // 'segment'/'centerGroup'/'pag' above) so they can wrap onto a 2nd line at
        // narrow widths instead of getting clipped — but a WrapLayout child only
        // reports its correct (wrapped) preferred height once it has actually been
        // laid out at its real width. Forcing an explicit revalidate of both bars
        // whenever the whole Discover panel resizes makes sure the root
        // BorderLayout always reserves the correct NORTH/SOUTH space for them;
        // otherwise, right after a resize that causes wrapping, the scroll area's
        // cards can render into space that visually overlaps the search toolbar
        // above or the Prev/Next bar below.
        p.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                topCard.revalidate();
                topCard.repaint();
                bottomRow.revalidate();
                bottomRow.repaint();
                p.revalidate();
            }
        });

        // ── Action listeners ────────────────────────────────────────────────
        Runnable doSearch = () -> {
            discoverOffset = 0;
            performDiscoverSearch();
        };
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

    /** Rounded-rect panel used for the Discover tab's "card" surfaces. */
    /**
     * Content-pane replacement that paints a soft, modern gradient backdrop — a
     * subtle vertical
     * dark gradient plus a faint accent-tinted glow in the upper-left — instead of
     * a single flat
     * color. Mostly visible around the edges/margins of the UI, but keeps things
     * from looking
     * like a plain, dated gray box.
     */
    public static final class GradientBackgroundPane extends JLayeredPane {
        private Color topColor = new Color(16, 16, 22);
        private Color bottomColor = new Color(8, 8, 12);
        private Color glowColor = new Color(16, 185, 129, 24);

        private BufferedImage bgImage; // raw, unscaled user-chosen image (null = none)
        private boolean blurEnabled = false;
        private int blurStrength = 10; // 1–40

        // Cache of the last blurred+scaled frame so we don't redo the (relatively
        // expensive)
        // downscale/upscale blur pass on every single repaint — only when size/settings
        // change.
        private BufferedImage renderCache;
        private int cacheW = -1, cacheH = -1, cacheBlur = -1;
        private boolean cacheBlurEnabled = false;
        /**
         * Bumped every time colors/style/image change so getSnapshot() knows to rebuild
         * even
         * when the pane's size hasn't changed (fixes "background style only updates
         * after a
         * restart" — the old cache key only tracked size + blur, never
         * style/color/image).
         */
        private int paletteVersion = 0;
        private int cachePaletteVersion = -1;

        GradientBackgroundPane() {
            setOpaque(true);
        }

        /**
         * Which preset PATTERN to paint. Colors always come from the Appearance tab's
         * Base
         * (background) and Accent colors — the style presets below only change the
         * shape of
         * the gradient and where/how the glow(s) are placed, never the hue. That keeps
         * "style"
         * and "color" fully independent, as the Appearance color settings intend.
         */
        private java.util.List<float[]> glowSpecs = java.util.List.of(new float[] { 0.18f, -0.05f, 0.9f, 22 });
        private boolean horizontalGradient = false;

        void setPalette(Color base, Color accent, String style) {
            String s = style == null ? "Default" : style;

            horizontalGradient = false;
            switch (s) {
                case "Midnight" -> {
                    // Deeper vertical gradient, single soft glow tucked in the bottom-right.
                    topColor = shade(base, 6);
                    bottomColor = shade(base, -18);
                    glowSpecs = java.util.List.of(new float[] { 0.82f, 1.05f, 0.8f, 26 });
                }
                case "Sunset" -> {
                    // Left-to-right gradient with two glows (warm, wide spread).
                    horizontalGradient = true;
                    topColor = shade(base, 12);
                    bottomColor = shade(base, -10);
                    glowSpecs = java.util.List.of(
                            new float[] { 0.05f, 0.15f, 0.7f, 28 },
                            new float[] { 0.95f, 0.85f, 0.7f, 20 });
                }
                case "Forest" -> {
                    // Broad glow centered low on the backdrop.
                    topColor = shade(base, 14);
                    bottomColor = shade(base, -14);
                    glowSpecs = java.util.List.of(new float[] { 0.5f, 1.0f, 1.1f, 24 });
                }
                case "Ocean" -> {
                    // Wide radial glow spreading from the top edge.
                    topColor = shade(base, 8);
                    bottomColor = shade(base, -10);
                    glowSpecs = java.util.List.of(new float[] { 0.5f, -0.1f, 1.3f, 22 });
                }
                case "Monochrome" -> {
                    // Plain neutral gradient, no accent-tinted glow at all.
                    topColor = shade(base, 16);
                    bottomColor = shade(base, -16);
                    glowSpecs = java.util.List.of();
                }
                case "Accent Glow" -> {
                    // Strong, large glow dead-center — the boldest accent presence.
                    topColor = shade(base, 10);
                    bottomColor = shade(base, -8);
                    glowSpecs = java.util.List.of(new float[] { 0.5f, 0.4f, 1.4f, 40 });
                }
                default -> { // "Default" — neutral dark gradient with a subtle glow, upper-left.
                    topColor = shade(base, 10);
                    bottomColor = shade(base, -8);
                    glowSpecs = java.util.List.of(new float[] { 0.18f, -0.05f, 0.9f, 22 });
                }
            }
            // Glow color is always derived from the Appearance "accent" color (never
            // hardcoded
            // per-style) — only its alpha varies slightly per spec above.
            glowColor = accent;

            paletteVersion++;
            invalidateCache();
            repaint();
        }

        private static Color mix(Color a, Color b, double t) {
            int r = (int) Math.round(a.getRed() * (1 - t) + b.getRed() * t);
            int g = (int) Math.round(a.getGreen() * (1 - t) + b.getGreen() * t);
            int bl = (int) Math.round(a.getBlue() * (1 - t) + b.getBlue() * t);
            return new Color(clamp(r), clamp(g), clamp(bl));
        }

        /**
         * @param path absolute/relative path to an image file, or null/blank to clear
         *             it.
         */
        void setBackgroundImage(String path) {
            BufferedImage loaded = null;
            if (path != null && !path.isBlank()) {
                try {
                    loaded = ImageIO.read(new File(path));
                } catch (Exception ignored) {
                    loaded = null; // Bad/missing file — just fall back to the gradient.
                }
            }
            if (loaded != bgImage) {
                bgImage = loaded;
                paletteVersion++;
                invalidateCache();
                repaint();
            }
        }

        void setBlur(boolean enabled, int strength) {
            int clamped = Math.max(1, Math.min(40, strength));
            if (enabled != blurEnabled || clamped != this.blurStrength) {
                blurEnabled = enabled;
                this.blurStrength = clamped;
                invalidateCache();
                repaint();
            }
        }

        private void invalidateCache() {
            renderCache = null;
            cacheW = -1;
            cacheH = -1;
            cacheBlur = -1;
        }

        private static Color shade(Color c, int amt) {
            return new Color(clamp(c.getRed() + amt), clamp(c.getGreen() + amt), clamp(c.getBlue() + amt));
        }

        private static int clamp(int v) {
            return Math.max(0, Math.min(255, v));
        }

        /**
         * Cheap, fast "blur": downscale the image a lot (amount scales with
         * blurStrength) then
         * scale it back up with bilinear interpolation. This is the standard low-cost
         * trick for
         * a soft frosted-glass look without a real (slow) Gaussian convolution —
         * perfectly fine
         * for a background that isn't the focal point of the UI.
         */
        private BufferedImage buildFrame(int w, int h) {
            BufferedImage frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = frame.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (bgImage != null) {
                // Scale-to-cover so the image always fills the pane without distortion.
                double scale = Math.max(w / (double) bgImage.getWidth(), h / (double) bgImage.getHeight());
                int drawW = (int) Math.ceil(bgImage.getWidth() * scale);
                int drawH = (int) Math.ceil(bgImage.getHeight() * scale);
                int dx = (w - drawW) / 2, dy = (h - drawH) / 2;

                if (blurEnabled) {
                    // blurStrength 1..40 -> downscale divisor ~1.2..14 (higher = blurrier)
                    double divisor = 1.0 + (blurStrength / 40.0) * 13.0;
                    int smallW = Math.max(1, (int) (w / divisor));
                    int smallH = Math.max(1, (int) (h / divisor));
                    BufferedImage small = new BufferedImage(smallW, smallH, BufferedImage.TYPE_INT_RGB);
                    Graphics2D sg = small.createGraphics();
                    sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    double sScale = Math.max(smallW / (double) bgImage.getWidth(),
                            smallH / (double) bgImage.getHeight());
                    int sDrawW = (int) Math.ceil(bgImage.getWidth() * sScale);
                    int sDrawH = (int) Math.ceil(bgImage.getHeight() * sScale);
                    sg.drawImage(bgImage, (smallW - sDrawW) / 2, (smallH - sDrawH) / 2, sDrawW, sDrawH, null);
                    sg.dispose();
                    g2.drawImage(small, 0, 0, w, h, null);
                } else {
                    g2.drawImage(bgImage, dx, dy, drawW, drawH, null);
                }

                // Slight dark scrim so text/panels stay readable over busy photos, regardless
                // of transparency settings (which are handled separately, higher up the stack).
                g2.setColor(new Color(0, 0, 0, 90));
                g2.fillRect(0, 0, w, h);
            } else {
                if (horizontalGradient) {
                    g2.setPaint(new GradientPaint(0, 0, topColor, w, 0, bottomColor));
                } else {
                    g2.setPaint(new GradientPaint(0, 0, topColor, 0, h, bottomColor));
                }
                g2.fillRect(0, 0, w, h);

                float baseRadius = Math.max(w, h);
                for (float[] spec : glowSpecs) {
                    float cx = spec[0], cy = spec[1], radiusFactor = spec[2];
                    int alpha = (int) spec[3];
                    Color glow = new Color(glowColor.getRed(), glowColor.getGreen(), glowColor.getBlue(), alpha);
                    Color glowTransparent = new Color(glowColor.getRed(), glowColor.getGreen(), glowColor.getBlue(), 0);
                    g2.setPaint(new RadialGradientPaint(
                            new Point2D.Float(w * cx, h * cy), baseRadius * radiusFactor,
                            new float[] { 0f, 1f },
                            new Color[] { glow, glowTransparent }));
                    g2.fillRect(0, 0, w, h);
                }
            }
            g2.dispose();
            return frame;
        }

        /**
         * Returns the current cached backdrop frame (gradient or background image,
         * blurred if
         * Blur is enabled), rebuilding it if the pane was resized/settings changed
         * since the
         * last paint. Used by Settings "island" cards to fake a per-panel frosted-glass
         * look.
         */
        BufferedImage getSnapshot() {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0)
                return null;
            if (renderCache == null || cacheW != w || cacheH != h || cacheBlur != blurStrength
                    || cacheBlurEnabled != blurEnabled || cachePaletteVersion != paletteVersion) {
                renderCache = buildFrame(w, h);
                cacheW = w;
                cacheH = h;
                cacheBlur = blurStrength;
                cacheBlurEnabled = blurEnabled;
                cachePaletteVersion = paletteVersion;
            }
            return renderCache;
        }

        @Override
        protected void paintComponent(Graphics g) {
            BufferedImage snap = getSnapshot();
            if (snap == null)
                return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.drawImage(snap, 0, 0, null);
            g2.dispose();
        }
    }

    /**
     * A modern replacement for JTabbedPane's tab bar: rounded, translucent "glass"
     * pill
     * buttons on a frosted capsule strip, with the selected tab glowing in the
     * accent color.
     * Exposes just the subset of JTabbedPane's API
     * (addTab/setSelectedIndex/getSelectedIndex/
     * setComponentAt) that the rest of the app actually calls, so it's a drop-in
     * swap.
     */
    private class PillTabbedPane extends JPanel {
        private final JPanel tabBar;
        private final JPanel cards;
        private final CardLayout cl = new CardLayout();
        private final java.util.List<JButton> buttons = new java.util.ArrayList<>();
        private int selectedIndex = -1;

        // ── Section-switch crossfade ────────────────────────────────────────
        // `cards` sits inside a plain null-layout container so we can drop a
        // fading snapshot of the outgoing section on top of it: the incoming
        // section is swapped in immediately underneath, and the snapshot
        // fades out over it, giving a smooth crossfade instead of an
        // instant hard cut.
        private final JPanel transitionContainer;
        private JComponent fadeOverlay;
        private javax.swing.Timer fadeTimer;

        PillTabbedPane() {
            super(new BorderLayout(0, 12));
            setOpaque(false);

            tabBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
            tabBar.setOpaque(false);

            JPanel tabBarWrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
            tabBarWrap.setOpaque(false);
            tabBarWrap.add(tabBar);

            cards = new JPanel(cl);
            cards.setOpaque(false);

            transitionContainer = new JPanel(null) {
                @Override
                public void doLayout() {
                    for (Component c : getComponents()) {
                        c.setBounds(0, 0, getWidth(), getHeight());
                    }
                }
            };
            transitionContainer.setOpaque(false);
            transitionContainer.add(cards);

            add(tabBarWrap, BorderLayout.NORTH);
            add(transitionContainer, BorderLayout.CENTER);
        }

        void addTab(String title, Component comp) {
            int idx = buttons.size();
            String key = "pill" + idx;
            cards.add(comp, key);

            JButton btn = new JButton(title) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    boolean sel = Boolean.TRUE.equals(getClientProperty("pillSelected"));
                    Color accent = hexToColor(
                            com.launcher.manager.SettingsManager.getInstance().getSettings().accentColor,
                            new Color(16, 185, 129));
                    int w = getWidth(), h = getHeight();
                    if (sel) {
                        // Soft outer glow, then a brighter accent-tinted glass fill.
                        g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 55));
                        g2.fillRoundRect(0, 0, w, h, h, h);
                        g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 35));
                        g2.fillRoundRect(2, 2, w - 4, h - 4, h, h);
                        g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 190));
                        g2.setStroke(new BasicStroke(1.6f));
                        g2.drawRoundRect(1, 1, w - 3, h - 3, h, h);
                    } else if (getModel().isRollover()) {
                        g2.setColor(new Color(255, 255, 255, 24));
                        g2.fillRoundRect(0, 0, w, h, h, h);
                        g2.setColor(new Color(255, 255, 255, 30));
                        g2.drawRoundRect(1, 1, w - 3, h - 3, h, h);
                    } else {
                        g2.setColor(new Color(255, 255, 255, 8));
                        g2.fillRoundRect(0, 0, w, h, h, h);
                    }
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            btn.setContentAreaFilled(false);
            btn.setFocusPainted(false);
            btn.setBorderPainted(false);
            btn.setOpaque(false);
            btn.setFont(new Font("SansSerif", Font.BOLD, 14));
            btn.setBorder(new EmptyBorder(10, 24, 10, 24));
            btn.putClientProperty("pillSelected", Boolean.FALSE);
            btn.addActionListener(e -> setSelectedIndex(idx));
            buttons.add(btn);
            tabBar.add(btn);

            if (selectedIndex == -1) {
                setSelectedIndex(0);
            }
        }

        void setComponentAt(int index, Component comp) {
            String key = "pill" + index;
            Component[] children = cards.getComponents();
            if (index >= 0 && index < children.length) {
                cards.remove(children[index]);
            }
            cards.add(comp, key, Math.min(index, cards.getComponentCount()));
            if (selectedIndex == index) {
                cl.show(cards, key);
            }
        }

        int getSelectedIndex() {
            return selectedIndex;
        }

        void setSelectedIndex(int index) {
            boolean sectionChanged = selectedIndex != -1 && selectedIndex != index;
            BufferedImage outgoingSnapshot = sectionChanged ? captureCardsSnapshot() : null;

            selectedIndex = index;
            cl.show(cards, "pill" + index);
            for (int i = 0; i < buttons.size(); i++) {
                JButton b = buttons.get(i);
                boolean sel = i == index;
                b.putClientProperty("pillSelected", sel);
                Color textColor = hexToColor(com.launcher.manager.SettingsManager.getInstance().getSettings().textColor,
                        new Color(226, 226, 234));
                b.setForeground(sel ? textColor
                        : new Color(textColor.getRed(), textColor.getGreen(), textColor.getBlue(), 160));
                b.repaint();
            }

            if (outgoingSnapshot != null) {
                crossfadeFrom(outgoingSnapshot);
            }
        }

        /** Grabs a pixel snapshot of whatever section is currently painted. */
        private BufferedImage captureCardsSnapshot() {
            int w = cards.getWidth(), h = cards.getHeight();
            if (w <= 0 || h <= 0) {
                return null;
            }
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            cards.paint(g2);
            g2.dispose();
            return img;
        }

        /**
         * Lays the outgoing section's snapshot on top of the (already-switched)
         * new section and fades it out over ~220ms, producing a smooth
         * crossfade transition instead of an instant cut.
         */
        private void crossfadeFrom(BufferedImage outgoingSnapshot) {
            if (fadeTimer != null && fadeTimer.isRunning()) {
                fadeTimer.stop();
            }
            if (fadeOverlay != null) {
                transitionContainer.remove(fadeOverlay);
                fadeOverlay = null;
            }

            fadeOverlay = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    Object alphaProp = getClientProperty("fadeAlpha");
                    float alpha = alphaProp instanceof Float ? (Float) alphaProp : 1f;
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
                    g2.drawImage(outgoingSnapshot, 0, 0, getWidth(), getHeight(), null);
                    g2.dispose();
                }
            };
            fadeOverlay.setOpaque(false);
            fadeOverlay.putClientProperty("fadeAlpha", 1f);
            transitionContainer.add(fadeOverlay);
            transitionContainer.setComponentZOrder(fadeOverlay, 0);
            transitionContainer.revalidate();
            transitionContainer.repaint();

            final long start = System.currentTimeMillis();
            final int durationMs = 220;
            fadeTimer = new javax.swing.Timer(12, null);
            fadeTimer.addActionListener(ev -> {
                float t = Math.min(1f, (System.currentTimeMillis() - start) / (float) durationMs);
                // Ease-out curve so the fade decelerates smoothly.
                float eased = 1f - (1f - t) * (1f - t);
                fadeOverlay.putClientProperty("fadeAlpha", 1f - eased);
                fadeOverlay.repaint();
                if (t >= 1f) {
                    fadeTimer.stop();
                    if (fadeOverlay != null) {
                        transitionContainer.remove(fadeOverlay);
                        fadeOverlay = null;
                        transitionContainer.repaint();
                    }
                }
            });
            fadeTimer.start();
        }

        /** Re-tints every pill so the glow follows a new accent color. */
        void refreshAccentStyles() {
            for (JButton b : buttons) {
                b.repaint();
            }
        }
    }

    public static class RoundedPanel extends JPanel {
        private final int radius;
        private Color fill;
        private Color border;
        // Optional top-to-bottom gradient fill; when set, this is painted instead of
        // the flat
        // "fill" color (e.g. the Dawn Client card's amber/orange gradient).
        private Color gradientTop;
        private Color gradientBottom;
        // Optional gradient border stroke, drawn instead of the flat "border" color.
        private Color borderGradientTop;
        private Color borderGradientBottom;
        private int borderStrokeWidth = 1;
        // Optional frosted-glass mode: instead of a flat/gradient fill, crop+blur
        // whatever the
        // app's background pane is currently showing behind this card and paint that.
        private GradientBackgroundPane frostedBackdrop;
        private int frostedBlurDivisor = 6; // higher = blurrier
        private Color frostedTint; // subtle color wash over the blurred backdrop, or null
        // Opacity multiplier (0..1) applied to the whole panel (fill/border/children)
        // so
        // callers can fade it in/out for show/hide animations instead of an abrupt
        // toggle.
        private float alpha = 1f;

        public RoundedPanel(int radius, Color fill, Color border) {
            this.radius = radius;
            this.fill = fill;
            this.border = border;
            setOpaque(false);
        }

        /**
         * Sets the 0..1 opacity multiplier used to fade this panel (and everything
         * painted
         * inside it) in or out; see the animateShow/animateHide helpers on Main.
         */
        public void setAlpha(float alpha) {
            this.alpha = Math.max(0f, Math.min(1f, alpha));
            repaint();
        }

        public float getAlpha() {
            return alpha;
        }

        @Override
        public void paint(Graphics g) {
            if (alpha >= 1f) {
                super.paint(g);
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            super.paint(g2);
            g2.dispose();
        }

        void setColors(Color fill, Color border) {
            this.fill = fill;
            this.border = border;
            this.gradientTop = null;
            this.gradientBottom = null;
            repaint();
        }

        void setGradient(Color top, Color bottom) {
            this.gradientTop = top;
            this.gradientBottom = bottom;
            repaint();
        }

        void setBorderGradient(Color top, Color bottom, int strokeWidth) {
            this.borderGradientTop = top;
            this.borderGradientBottom = bottom;
            this.borderStrokeWidth = strokeWidth;
            repaint();
        }

        /**
         * Makes the card's interior a frosted-glass blur of the app's background
         * instead of a
         * flat/gradient fill. {@code tint}, if non-null, is washed over the blur at low
         * alpha
         * to keep the card's own accent color visible even through the frosted effect.
         */
        public void setFrostedGlass(GradientBackgroundPane backdrop, int blurDivisor, Color tint) {
            this.frostedBackdrop = backdrop;
            this.frostedBlurDivisor = Math.max(2, blurDivisor);
            this.frostedTint = tint;
            repaint();
        }

        /**
         * Turns frosted-glass mode back off — needed when a single RoundedPanel
         * instance is
         * reused across list rows (e.g. a JList cell renderer) and only some rows want
         * it.
         */
        void clearFrostedGlass() {
            this.frostedBackdrop = null;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int w = getWidth(), h = getHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Shape roundedShape = new java.awt.geom.RoundRectangle2D.Float(0, 0, w - 1, h - 1, radius, radius);

            if (frostedBackdrop != null) {
                BufferedImage blurred = buildFrostedCrop(w, h);
                if (blurred != null) {
                    Shape oldClip = g2.getClip();
                    g2.clip(roundedShape);
                    g2.drawImage(blurred, 0, 0, null);
                    if (frostedTint != null) {
                        g2.setColor(frostedTint);
                        g2.fillRect(0, 0, w, h);
                    }
                    g2.setClip(oldClip);
                } else {
                    g2.setColor(fill);
                    g2.fill(roundedShape);
                }
            } else if (gradientTop != null && gradientBottom != null) {
                g2.setPaint(new GradientPaint(0, 0, gradientTop, 0, h, gradientBottom));
                g2.fill(roundedShape);
            } else {
                g2.setColor(fill);
                g2.fill(roundedShape);
            }

            if (borderGradientTop != null && borderGradientBottom != null) {
                g2.setPaint(new GradientPaint(0, 0, borderGradientTop, 0, h, borderGradientBottom));
                g2.setStroke(new BasicStroke(borderStrokeWidth));
                g2.draw(new java.awt.geom.RoundRectangle2D.Float(
                        borderStrokeWidth / 2f, borderStrokeWidth / 2f,
                        w - 1 - borderStrokeWidth, h - 1 - borderStrokeWidth, radius, radius));
            } else if (border != null) {
                g2.setColor(border);
                g2.drawRoundRect(0, 0, w - 1, h - 1, radius, radius);
            }
            g2.dispose();
            super.paintComponent(g);
        }

        /**
         * Crops the region of the app background directly behind this card out of the
         * backdrop's current frame, then cheaply blurs it (downscale/upscale, same
         * trick
         * GradientBackgroundPane itself uses for the Blur setting).
         */
        private BufferedImage buildFrostedCrop(int w, int h) {
            if (w <= 0 || h <= 0 || !isShowing())
                return null;
            BufferedImage full = frostedBackdrop.getSnapshot();
            if (full == null)
                return null;
            Point origin = SwingUtilities.convertPoint(this, 0, 0, frostedBackdrop);
            int fw = full.getWidth(), fh = full.getHeight();
            int sx = Math.max(0, Math.min(origin.x, fw - 1));
            int sy = Math.max(0, Math.min(origin.y, fh - 1));
            int sw = Math.max(1, Math.min(w, fw - sx));
            int sh = Math.max(1, Math.min(h, fh - sy));
            BufferedImage crop = full.getSubimage(sx, sy, sw, sh);

            int smallW = Math.max(1, w / frostedBlurDivisor);
            int smallH = Math.max(1, h / frostedBlurDivisor);
            BufferedImage small = new BufferedImage(smallW, smallH, BufferedImage.TYPE_INT_RGB);
            Graphics2D sg = small.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            sg.drawImage(crop, 0, 0, smallW, smallH, null);
            sg.dispose();

            BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D rg = result.createGraphics();
            rg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            rg.drawImage(small, 0, 0, w, h, null);
            rg.dispose();
            return result;
        }
    }

    // Moderate "kinda rounded" corner radius for the Discover toolbar buttons
    // (segment toggle, Search, Refresh, Prev/Next).
    private static final int DISC_BUTTON_ARC = 20;

    /**
     * A plain JButton whose background is hand-painted as a rounded rectangle,
     * instead of relying on the look-and-feel's own "JButton.arc" client
     * property. This guarantees the rounding is always visible no matter what
     * L&F/theme is active, since the shape is drawn directly rather than
     * delegated to FlatLaf's button UI.
     */
    private static class DiscRoundButton extends JButton {
        private final int arc;

        DiscRoundButton(String text, int arc) {
            super(text);
            this.arc = arc;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = getBackground();
            if (getModel().isPressed()) {
                fill = fill.darker();
            } else if (getModel().isRollover()) {
                fill = new Color(
                        Math.min(255, fill.getRed() + 12),
                        Math.min(255, fill.getGreen() + 12),
                        Math.min(255, fill.getBlue() + 12),
                        fill.getAlpha());
            }
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /**
     * Same rounded-rectangle self-painting approach as {@link DiscRoundButton},
     * but for the Mods/Resource Packs segmented toggle.
     */
    private static class DiscRoundToggleButton extends JToggleButton {
        private final int arc;

        DiscRoundToggleButton(String text, boolean selected, int arc) {
            super(text, selected);
            this.arc = arc;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = getBackground();
            if (getModel().isPressed()) {
                fill = fill.darker();
            } else if (getModel().isRollover() && !isSelected()) {
                fill = new Color(
                        Math.min(255, fill.getRed() + 12),
                        Math.min(255, fill.getGreen() + 12),
                        Math.min(255, fill.getBlue() + 12),
                        fill.getAlpha());
            }
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private void styleDiscoverSegment(JToggleButton btn) {
        btn.setFont(new Font("SansSerif", Font.BOLD, 11));
        btn.setForeground(DISC_TEXT_DIM);
        btn.setBackground(new Color(255, 255, 255, 14));
        btn.setBorder(new EmptyBorder(7, 16, 7, 16));
        btn.addChangeListener(e -> {
            if (btn.isSelected()) {
                btn.setForeground(Color.WHITE);
                btn.setBackground(DISC_ACCENT);
            } else {
                btn.setForeground(DISC_TEXT_DIM);
                btn.setBackground(new Color(255, 255, 255, 14));
            }
            btn.repaint();
        });
    }

    private void styleDiscoverPrimaryButton(JButton btn) {
        btn.setFont(new Font("SansSerif", Font.BOLD, 12));
        btn.setForeground(Color.WHITE);
        btn.setBackground(DISC_ACCENT);
        btn.setBorder(new EmptyBorder(7, 18, 7, 18));
    }

    private void styleDiscoverGhostButton(JButton btn) {
        btn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        btn.setForeground(DISC_TEXT);
        btn.setBackground(new Color(255, 255, 255, 14));
        btn.setBorder(new EmptyBorder(6, 14, 6, 14));
    }

    /**
     * Prev/Next pagination buttons — a subtle filled rounded-rect, styled the
     * same way as the rest of the toolbar so they read as buttons in their own
     * right instead of plain text.
     */
    private void styleDiscoverNavButton(JButton btn) {
        btn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        btn.setForeground(DISC_TEXT);
        btn.setBackground(new Color(255, 255, 255, 10));
        btn.setBorder(new EmptyBorder(6, 12, 6, 12));
    }

    /**
     * Recomputes the Discover tab's palette from the user's Appearance settings and
     * re-styles every already-built Discover widget in place. Previously the
     * Discover
     * tab (and consequently the mod/resource-pack cards inside it) was hardcoded to
     * a
     * fixed purple/dark palette and completely ignored theme changes made in
     * Settings.
     */
    private void applyDiscoverPalette() {
        if (discoverRootPanel == null)
            return;
        com.launcher.model.LauncherSettings settings = com.launcher.manager.SettingsManager.getInstance().getSettings();
        Color bg = hexToColor(settings.bgColor, new Color(10, 10, 15));
        Color panelBg = hexToColor(settings.panelBgColor, new Color(19, 19, 26));
        Color text = hexToColor(settings.textColor, new Color(226, 226, 234));
        Color accent = hexToColor(settings.accentColor, new Color(16, 185, 129));

        DISC_BG = bg;
        // Cards/surfaces now use the same soft translucent-white-over-background
        // look as the launcher's (Instances tab) RoundedPanels, rather than a
        // separate flat "store" palette, so Discover reads as part of the same UI.
        DISC_SURFACE = new Color(255, 255, 255, 10);
        DISC_SURFACE_HOVER = new Color(255, 255, 255, 20);
        DISC_BORDER = new Color(255, 255, 255, 18);
        DISC_BORDER_HOVER = accent;
        DISC_ACCENT = accent;
        DISC_TEXT = text;
        DISC_TEXT_DIM = tint(text, -60);

        // Root/results/scroll containers stay transparent (see buildDiscoverArea) so
        // the
        // app's background shows through — don't re-paint them opaque here.
        if (discoverTitleLbl != null)
            discoverTitleLbl.setForeground(DISC_TEXT);
        if (discoverSubtitleLbl != null)
            discoverSubtitleLbl.setForeground(DISC_TEXT_DIM);
        if (discoverFiltersPanel != null)
            discoverFiltersPanel.setColors(DISC_SURFACE, DISC_BORDER);

        // discoverSearchField itself is now hosted inside a wrapInFrostedGlass
        // RoundedPanel (the same "launcher" search bar used on the Mods tab), which
        // re-themes itself via wireFrostedGlassBackdrops/its own outline color, so
        // only the text/caret colors need to be kept in sync with the palette here.
        if (discoverSearchField != null) {
            discoverSearchField.setForeground(DISC_TEXT);
            discoverSearchField.setCaretColor(DISC_TEXT);
        }
        if (discoverSearchWrapPanel != null) {
            discoverSearchWrapPanel.setColors(new Color(255, 255, 255, 16), new Color(255, 255, 255, 45));
        }
        if (discoverSearchBtn != null)
            styleDiscoverPrimaryButton(discoverSearchBtn);
        if (discoverRefreshBtn != null)
            styleDiscoverGhostButton(discoverRefreshBtn);
        if (discoverPrevPageBtn != null)
            styleDiscoverNavButton(discoverPrevPageBtn);
        if (discoverNextPageBtn != null)
            styleDiscoverNavButton(discoverNextPageBtn);
        if (discoverModsToggle != null)
            styleDiscoverSegment(discoverModsToggle);
        if (discoverPacksToggle != null)
            styleDiscoverSegment(discoverPacksToggle);
        if (discoverPageLabel != null)
            discoverPageLabel.setForeground(DISC_TEXT_DIM);

        discoverRootPanel.revalidate();
        discoverRootPanel.repaint();
        // Re-render any already-loaded result cards so they pick up the new accent/text
        // colors too.
        if (discoverResultsPane != null) {
            discoverResultsPane.revalidate();
            discoverResultsPane.repaint();
        }
    }

    /**
     * Applies the Discover tab's glass palette to a combo box (Target/Version
     * pickers, etc.),
     * which otherwise fall back to FlatLaf's plain gray combo box look.
     */
    private static void styleDiscoverComboBox(JComboBox<?> box) {
        box.setBackground(new Color(255, 255, 255, 18));
        box.setForeground(DISC_TEXT);
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DISC_BORDER, 1, true),
                new EmptyBorder(2, 8, 2, 8)));
        box.putClientProperty("JComboBox.buttonStyle", "none");
        box.putClientProperty("JComponent.arc", 14);
    }

    /**
     * Lightens (positive amt) or darkens (negative amt) a color by a flat
     * per-channel amount.
     */
    private static Color tint(Color c, int amt) {
        int r = Math.max(0, Math.min(255, c.getRed() + amt));
        int g = Math.max(0, Math.min(255, c.getGreen() + amt));
        int b = Math.max(0, Math.min(255, c.getBlue() + amt));
        return new Color(r, g, b);
    }

    /**
     * Small circular avatar badge showing the account's first letter, tinted with
     * the
     * configured accent color — used by the account dropdown so each row has a
     * visual anchor
     * instead of just plain text.
     */
    private ImageIcon accountAvatarIcon(String label) {
        int size = 20;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        com.launcher.model.LauncherSettings settings = com.launcher.manager.SettingsManager.getInstance().getSettings();
        Color accent = hexToColor(settings.accentColor, new Color(16, 185, 129));
        g2.setColor(accent);
        g2.fillOval(0, 0, size, size);
        String letter = (label == null || label.isBlank()) ? "?" : label.substring(0, 1).toUpperCase();
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("SansSerif", Font.BOLD, 11));
        FontMetrics fm = g2.getFontMetrics();
        int tx = (size - fm.stringWidth(letter)) / 2;
        int ty = (size - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(letter, tx, ty);
        g2.dispose();
        return new ImageIcon(img);
    }

    /**
     * Wraps a component in a frosted-glass RoundedPanel: a blurred crop of the
     * app's own
     * background instead of a flat gray fill, with a colored outline. The backdrop
     * is wired up
     * later via {@link #wireFrostedGlassBackdrops()} once layeredPane exists.
     */
    private RoundedPanel wrapInFrostedGlass(JComponent inner, int arc, Color outline) {
        RoundedPanel wrap = new RoundedPanel(arc, new Color(255, 255, 255, 10), outline);
        wrap.putClientProperty("keepCustomBg", Boolean.TRUE);
        wrap.setLayout(new BorderLayout());
        wrap.setBorder(new EmptyBorder(2, 10, 2, 10));
        inner.setOpaque(false);
        wrap.add(inner, BorderLayout.CENTER);
        frostedGlassPanels.add(wrap);
        return wrap;
    }

    /**
     * Wires every panel registered via {@link #wrapInFrostedGlass} to blur a live
     * crop of
     * layeredPane. Must run after layeredPane is constructed.
     */
    private void wireFrostedGlassBackdrops() {
        for (RoundedPanel panel : frostedGlassPanels) {
            panel.setFrostedGlass(layeredPane, 8, new Color(255, 255, 255, 8));
        }
    }

    private final java.util.List<RoundedPanel> frostedGlassPanels = new ArrayList<>();

    private void refreshDiscoverInstances() {
        // No longer using discoverInstanceBox. We use instanceList.getSelectedValue()
        // instead.
    }

    // ── Skeleton loading placeholders ────────────────────────────────────────
    private void showDiscoverSkeletons() {
        discoverResultsPane.removeAll();
        // Fill enough skeleton cards to cover a full viewport (and then some) so
        // the loading state doesn't look sparse compared to a real results page —
        // scale roughly to the page size instead of a fixed small handful.
        int count = Math.max(12, ModUpdateService.DISCOVER_PAGE_SIZE);
        for (int i = 0; i < count; i++) {
            discoverResultsPane.add(buildDiscoverSkeletonCard());
        }
        discoverResultsPane.revalidate();
        discoverResultsPane.repaint();
    }

    private JPanel buildDiscoverSkeletonCard() {
        RoundedPanel card = new RoundedPanel(20, DISC_SURFACE, DISC_BORDER) {
            private float phase = 0f;
            {
                // Subtle shimmer animation
                javax.swing.Timer shimmer = new javax.swing.Timer(50, e -> {
                    phase += 0.08f;
                    if (phase > 2f)
                        phase = 0f;
                    repaint();
                });
                shimmer.start();
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                int w = getWidth();
                // Intersect with (not replace) whatever clip Swing already
                // established for this paint pass — if the card is half-scrolled
                // out of the visible viewport, the incoming clip already reflects
                // that, and replacing it here would make the shimmer ignore that
                // and paint into the hidden half anyway.
                g2.clip(new java.awt.geom.RoundRectangle2D.Float(0, 0, w, getHeight(), 20, 20));
                // Shimmer overlay
                float shimmerX = (phase - 1f) * w;
                java.awt.GradientPaint gp = new java.awt.GradientPaint(
                        shimmerX, 0, new Color(255, 255, 255, 0),
                        shimmerX + w * 0.4f, 0, new Color(255, 255, 255, 14));
                g2.setPaint(gp);
                g2.fillRect(0, 0, w, getHeight());
                g2.dispose();
            }
        };
        card.putClientProperty("keepCustomBg", Boolean.TRUE);
        card.setLayout(new BorderLayout(12, 8));
        card.setPreferredSize(new Dimension(310, 200));
        card.setBorder(new EmptyBorder(14, 14, 14, 14));

        // Placeholder icon
        JPanel iconPlaceholder = new RoundedPanel(14, new Color(255, 255, 255, 16), null);
        iconPlaceholder.setPreferredSize(new Dimension(48, 48));
        card.add(iconPlaceholder, BorderLayout.WEST);

        // Placeholder text lines
        JPanel textCol = new JPanel();
        textCol.setOpaque(false);
        textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
        for (int i = 0; i < 3; i++) {
            JPanel line = new RoundedPanel(4, new Color(255, 255, 255, 16), null);
            int w = i == 0 ? 160 : (i == 1 ? 200 : 80);
            line.setMaximumSize(new Dimension(w, 12));
            line.setPreferredSize(new Dimension(w, 12));
            line.setAlignmentX(Component.LEFT_ALIGNMENT);
            textCol.add(line);
            textCol.add(Box.createVerticalStrut(8));
        }
        card.add(textCol, BorderLayout.CENTER);
        return card;
    }

    // ── Search ──────────────────────────────────────────────────────────────
    private void performDiscoverSearch() {
        String query = discoverSearchField.getText().trim();
        boolean isPack = discoverPacksToggle.isSelected();
        String projectType = isPack ? "resourcepack" : "mod";

        Instance target = instanceList.getSelectedValue();
        String loader = null;
        String gameVersion = null;
        if (target != null) {
            gameVersion = target.mcVersion;
            if (target.modLoader != null && target.modLoader != ModLoaderType.VANILLA) {
                loader = target.modLoader.name().toLowerCase();
            }
        }

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
                    discoverSearchBtn.setEnabled(true);
                });

            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    discoverResultsPane.removeAll();
                    discoverResultsPane.revalidate();
                    discoverResultsPane.repaint();
                    discoverSearchBtn.setEnabled(true);
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
        // Same rounded, softly translucent "glass" card treatment used by the
        // launcher's Instances tab (headerCard/infoCard/instance-list rows) — bigger
        // corner radius, white-alpha fill/border, accent border on hover — instead of
        // the old flat purple "store" card.
        RoundedPanel card = new RoundedPanel(20, DISC_SURFACE, DISC_BORDER);
        card.putClientProperty("keepCustomBg", Boolean.TRUE);
        card.setLayout(new BorderLayout(12, 10));
        card.setPreferredSize(new Dimension(310, 200));
        card.setBorder(new EmptyBorder(14, 14, 14, 14));

        // Hover effect
        card.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                card.setColors(DISC_SURFACE_HOVER, DISC_BORDER_HOVER);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                card.setColors(DISC_SURFACE, DISC_BORDER);
            }
        });

        String title = hit.get("title").getAsString();
        String desc = hit.has("description") ? hit.get("description").getAsString() : "";
        String author = hit.has("author") && !hit.get("author").isJsonNull() ? hit.get("author").getAsString() : "";
        String iconUrl = hit.has("icon_url") && !hit.get("icon_url").isJsonNull() ? hit.get("icon_url").getAsString()
                : null;
        String slug = hit.has("slug") ? hit.get("slug").getAsString() : "";
        String projectId = hit.has("project_id") ? hit.get("project_id").getAsString() : slug;
        int downloads = hit.has("downloads") ? hit.get("downloads").getAsInt() : 0;

        // Version compatibility — check if any of the project's listed versions match
        // the target instance's Minecraft version.
        boolean compatible = false;
        if (gameVersion != null && hit.has("versions") && hit.get("versions").isJsonArray()) {
            for (var v : hit.getAsJsonArray("versions")) {
                if (gameVersion.equals(v.getAsString())) {
                    compatible = true;
                    break;
                }
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
                    // Intersect with (not replace) the incoming clip. Swing already
                    // sets this to the visible portion when the card is partially
                    // scrolled out of the viewport — replacing it here would make
                    // the icon ignore that and paint fully regardless, effectively
                    // decoupling the image from the card's own hidden/visible
                    // state. Intersecting keeps the image clipped in lockstep with
                    // whatever part of the card is actually on-screen, in addition
                    // to giving it its own rounded corners.
                    g2.clip(new java.awt.geom.RoundRectangle2D.Float(0, 0, s, s, 14, 14));
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
                iconLabel.setIcon(defaultModIcon48);
                new Thread(() -> {
                    ImageIcon icon = loadIconRobust(fIconUrl, 48);
                    if (icon != null) {
                        discoverIconCache.put(fIconUrl, icon);
                        SwingUtilities.invokeLater(() -> iconLabel.setIcon(icon));
                    }
                }, "icon-load").start();
            }
        } else {
            iconLabel.setIcon(defaultModIcon48);
        }
        card.add(iconLabel, BorderLayout.WEST);

        // ── Center: text info ───────────────────────────────────────────────
        JPanel textCol = new JPanel();
        textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
        textCol.setOpaque(false);

        // Title
        JLabel titleLbl = new JLabel("<html><b>" + escapeHtml(title) + "</b></html>");
        titleLbl.setForeground(DISC_TEXT);
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 14));
        titleLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        textCol.add(titleLbl);

        // Author + download count
        String meta = "";
        if (!author.isEmpty())
            meta += "by " + author;
        if (downloads > 0) {
            if (!meta.isEmpty())
                meta += "  ·  ";
            meta += "⬇ " + formatCount(downloads);
        }
        if (!meta.isEmpty()) {
            JLabel metaLbl = new JLabel(meta);
            metaLbl.setFont(new Font("SansSerif", Font.PLAIN, 10));
            metaLbl.setForeground(DISC_TEXT_DIM);
            metaLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            textCol.add(metaLbl);
        }

        // Compatibility badge
        if (gameVersion != null) {
            JLabel badge;
            if (compatible) {
                badge = new JLabel("✓ Supports " + gameVersion);
                badge.setForeground(new Color(96, 210, 130));
            } else {
                badge = new JLabel("⚠ NotSupported " + gameVersion);
                badge.setForeground(new Color(230, 170, 60));
            }
            badge.setFont(new Font("SansSerif", Font.BOLD, 10));
            badge.setAlignmentX(Component.LEFT_ALIGNMENT);
            badge.setBorder(new EmptyBorder(3, 0, 0, 0));
            textCol.add(badge);
        }

        // Description
        JLabel descLbl = new JLabel("<html><body style='width: 175px;'>" + escapeHtml(desc) + "</body></html>");
        descLbl.setFont(new Font("SansSerif", Font.PLAIN, 10));
        descLbl.setForeground(DISC_TEXT_DIM);
        descLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        textCol.add(Box.createVerticalStrut(4));
        textCol.add(descLbl);

        card.add(textCol, BorderLayout.CENTER);

        // ── Bottom: version picker + download button ────────────────────────
        JPanel bottomRow = new JPanel(new BorderLayout(8, 0));
        bottomRow.setOpaque(false);

        JComboBox<VersionOption> versionPicker = new JComboBox<>();
        versionPicker.setPreferredSize(new Dimension(140, 28));
        versionPicker.setFont(new Font("SansSerif", Font.PLAIN, 10));
        versionPicker.addItem(new VersionOption("Loading versions…", null, null, false, false));
        versionPicker.setEnabled(false);
        styleDiscoverComboBox(versionPicker);
        bottomRow.add(versionPicker, BorderLayout.CENTER);

        JButton downloadBtn = new JButton("Download");
        styleDiscoverPrimaryButton(downloadBtn);
        downloadBtn.setEnabled(false);
        bottomRow.add(downloadBtn, BorderLayout.EAST);

        card.add(bottomRow, BorderLayout.SOUTH);

        // ── Async: load versions for the picker ─────────────────────────────
        loadDiscoverCardVersions(projectId, projectType, loader, gameVersion, versionPicker, downloadBtn, title);

        return card;
    }

    private static final int DISCOVER_VERSION_MAX_AUTO_RETRIES = 3;

    /**
     * Asynchronously loads version list into a card's dropdown and wires the
     * download button.
     */
    private void loadDiscoverCardVersions(String projectId, String projectType,
            String loader, String gameVersion,
            JComboBox<VersionOption> picker,
            JButton downloadBtn, String title) {
        fetchDiscoverCardVersions(projectId, projectType, loader, gameVersion, picker, downloadBtn, title, 0);

        // Wire download action
        downloadBtn.addActionListener(e -> {
            VersionOption selected = (VersionOption) picker.getSelectedItem();
            if (selected == null || selected.url() == null) {
                // Selecting the "Failed to load" sentinel re-triggers a fresh attempt
                // instead of silently doing nothing.
                if (selected != null && selected.failed()) {
                    picker.removeAllItems();
                    picker.addItem(new VersionOption("Loading versions…", null, null, false, false));
                    picker.setEnabled(false);
                    downloadBtn.setText("Download");
                    downloadBtn.setEnabled(false);
                    fetchDiscoverCardVersions(projectId, projectType, loader, gameVersion, picker, downloadBtn, title,
                            0);
                }
                return;
            }
            Instance target = instanceList.getSelectedValue();
            if (target == null) {
                notifications.error("No target", "Select a target instance first.");
                return;
            }
            boolean isPack = "resourcepack".equals(projectType);
            downloadBtn.setEnabled(false);
            downloadBtn.setText("…");
            String downloadId = com.launcher.manager.DownloadManager.getInstance().start(title);
            new Thread(() -> {
                try {
                    String subDir = isPack ? "resourcepacks" : "mods";
                    Path destDir = instanceManager.resolveGameDir(target).resolve(subDir);
                    Files.createDirectories(destDir);
                    Path destFile = destDir.resolve(selected.fileName());

                    com.launcher.util.HttpUtil.downloadFile(selected.url(), destFile, msg -> {
                        com.launcher.manager.DownloadManager.getInstance().update(downloadId, msg);
                        SwingUtilities.invokeLater(() -> setStatus(msg));
                    });
                    com.launcher.manager.DownloadManager.getInstance().finish(downloadId,
                            "Installed into " + target.name);
                    SwingUtilities.invokeLater(() -> {
                        String kind = isPack ? "resource pack" : "mod";
                        notifications.success("Downloaded " + kind, title + " installed into " + target.name);
                        downloadBtn.setText("✓ Done");
                    });
                } catch (Exception ex) {
                    com.launcher.manager.DownloadManager.getInstance().fail(downloadId, ex.getMessage());
                    SwingUtilities.invokeLater(() -> {
                        notifications.error("Download failed", ex.getMessage());
                        downloadBtn.setEnabled(true);
                        downloadBtn.setText("Download");
                    });
                }
            }, "discover-download").start();
        });
    }

    /**
     * Performs the actual Modrinth version fetch for a Discover card, with
     * automatic
     * retries (with backoff) on failure. After
     * {@link #DISCOVER_VERSION_MAX_AUTO_RETRIES}
     * failed attempts it gives up and shows a "Failed to load" entry that the user
     * can
     * click to trigger a fresh manual retry (wired in
     * {@link #loadDiscoverCardVersions}).
     */
    private void fetchDiscoverCardVersions(String projectId, String projectType,
            String loader, String gameVersion,
            JComboBox<VersionOption> picker,
            JButton downloadBtn, String title, int attempt) {
        new Thread(() -> {
            try {
                JsonArray versions = discoverModService.listVersions(projectId, projectType, loader, gameVersion);
                List<VersionOption> options = new ArrayList<>();
                for (var el : versions) {
                    JsonObject v = el.getAsJsonObject();
                    String versionNumber = v.has("version_number") ? v.get("version_number").getAsString() : "?";
                    String versionName = v.has("name") && !v.get("name").isJsonNull() ? v.get("name").getAsString()
                            : versionNumber;

                    // Check game version support for the label
                    boolean supportsTarget = false;
                    if (gameVersion != null && v.has("game_versions") && v.get("game_versions").isJsonArray()) {
                        for (var gv : v.getAsJsonArray("game_versions")) {
                            if (gameVersion.equals(gv.getAsString())) {
                                supportsTarget = true;
                                break;
                            }
                        }
                    }

                    String[] file = ModUpdateService.primaryFileOf(v);
                    if (file == null)
                        continue;

                    String label = versionNumber;
                    if (supportsTarget)
                        label += "  ✓";
                    if (versionName != null && !versionName.equals(versionNumber)) {
                        // Truncate long names
                        String displayName = versionName.length() > 25 ? versionName.substring(0, 22) + "…"
                                : versionName;
                        label += "  (" + displayName + ")";
                    }
                    options.add(new VersionOption(label, file[0], file[1], supportsTarget, false));
                }

                SwingUtilities.invokeLater(() -> {
                    picker.removeAllItems();
                    if (options.isEmpty()) {
                        picker.addItem(new VersionOption("No versions available", null, null, false, false));
                    } else {
                        for (VersionOption opt : options)
                            picker.addItem(opt);
                        picker.setEnabled(true);
                        downloadBtn.setEnabled(true);
                        // Auto-select the version that matches the target instance's game
                        // version/loader, so the user isn't stuck with an incompatible
                        // default (usually just the newest release) selected by mistake.
                        options.stream()
                                .filter(VersionOption::matchesTarget)
                                .findFirst()
                                .ifPresent(picker::setSelectedItem);
                    }
                });
            } catch (Exception ex) {
                if (attempt < DISCOVER_VERSION_MAX_AUTO_RETRIES) {
                    // Automatically reload with a short, increasing backoff before giving up.
                    long delayMs = 1000L * (attempt + 1);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    fetchDiscoverCardVersions(projectId, projectType, loader, gameVersion, picker, downloadBtn, title,
                            attempt + 1);
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    picker.removeAllItems();
                    picker.addItem(new VersionOption("⟳ Failed to load — click to retry", null, null, false, true));
                    picker.setEnabled(true);
                    // Re-enable the button (re-labeled) so the user has an obvious way to
                    // retry manually instead of it staying permanently greyed out.
                    downloadBtn.setText("Retry");
                    downloadBtn.setEnabled(true);
                });
            }
        }, "load-versions-" + projectId + "-attempt" + attempt).start();
    }

    /**
     * Formats a download count into a compact human-readable string (1.2K, 3.4M).
     */
    private static String formatCount(int count) {
        if (count >= 1_000_000)
            return String.format("%.1fM", count / 1_000_000.0);
        if (count >= 1_000)
            return String.format("%.1fK", count / 1_000.0);
        return String.valueOf(count);
    }

    /** Simple HTML-escape to prevent injection via project titles/descriptions. */
    private static String escapeHtml(String s) {
        if (s == null)
            return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SETTINGS TAB
    // ══════════════════════════════════════════════════════════════════════════
    private JPanel createCard(String title) {
        JPanel card = new JPanel();
        card.setLayout(new GridBagLayout());
        card.putClientProperty("settingsCardTitle", title);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createLineBorder(new Color(60, 60, 70), 1, true),
                        title,
                        TitledBorder.LEFT,
                        TitledBorder.TOP,
                        new Font("SansSerif", Font.BOLD, 13),
                        hexToColor(com.launcher.manager.SettingsManager.getInstance().getSettings().accentColor,
                                new Color(16, 185, 129))),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        card.setBackground(hexToColor(com.launcher.manager.SettingsManager.getInstance().getSettings().panelBgColor,
                new Color(19, 19, 26)));
        return card;
    }

    private JScrollPane buildSettingsArea() {
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(new EmptyBorder(16, 16, 16, 16));

        com.launcher.model.LauncherSettings s = com.launcher.manager.SettingsManager.getInstance().getSettings();
        com.launcher.manager.SettingsManager mgr = com.launcher.manager.SettingsManager.getInstance();

        // ── 1. APPEEARANCE CARD ────────────────────────────────────────────────
        JPanel appearanceCard = createCard("Appearance");
        GridBagConstraints gbc = createGbc();

        // Helper for color input rows
        Consumer<String> saveAndApply = (hex) -> {
            mgr.save();
            applyTheme();
        };

        // Corrected to directly access public fields
        appearanceCard.add(createColorInputRow("Accent Color (Hex)", () -> s.accentColor, (val) -> s.accentColor = val,
                saveAndApply, "#10b981"), gbc);
        gbc.gridy++;
        appearanceCard.add(createColorInputRow("Background Color (Hex)", () -> s.bgColor, (val) -> s.bgColor = val,
                saveAndApply, "#0a0a0f"), gbc);
        gbc.gridy++;
        appearanceCard.add(createColorInputRow("Panel Background (Hex)", () -> s.panelBgColor,
                (val) -> s.panelBgColor = val, saveAndApply, "#13131a"), gbc);

        gbc.gridy++;
        appearanceCard.add(createColorInputRow("Notification Background (Hex)", () -> s.notificationBgColor,
                (val) -> s.notificationBgColor = val, saveAndApply, "#13131A"), gbc);

        JComboBox<String> notifStyleCombo = new JComboBox<>(
                new String[] { "Frosted Glass", "Solid Dark", "Minimal Outline" });
        notifStyleCombo.setSelectedItem(s.notificationStyle);
        styleComboBox(notifStyleCombo);
        notifStyleCombo.addActionListener(e -> {
            s.notificationStyle = (String) notifStyleCombo.getSelectedItem();
            saveAndApply.accept("");
        });
        gbc.gridy++;
        appearanceCard.add(createColorInputRow("Text Color (Hex)", () -> s.textColor, (val) -> s.textColor = val,
                saveAndApply, "#e2e2ea"), gbc);
        gbc.gridy++;
        appearanceCard.add(createColorInputRow("Log Background (Hex)", () -> s.logBgColor, (val) -> s.logBgColor = val,
                saveAndApply, "#060608"), gbc);
        gbc.gridy++;

        // ── Transparency (independent of Blur) ──────────────────────────────
        CustomToggle enableTransparencyCb = new CustomToggle("Enable transparency (Recommended)");
        enableTransparencyCb.setSelected(s.enableTransparency);

        JSlider transparencySlider = new JSlider(1, 100, s.transparencyStrength > 0 ? s.transparencyStrength : 20);
        transparencySlider.setEnabled(s.enableTransparency);
        JLabel transparencyValLabel = new JLabel(String.valueOf(transparencySlider.getValue()));
        transparencyValLabel.setForeground(Color.LIGHT_GRAY);

        enableTransparencyCb.addActionListener(e -> {
            s.enableTransparency = enableTransparencyCb.isSelected();
            transparencySlider.setEnabled(s.enableTransparency);
            mgr.save();
            applyTheme();
        });
        transparencySlider.addChangeListener(e -> {
            s.transparencyStrength = transparencySlider.getValue();
            transparencyValLabel.setText(String.valueOf(s.transparencyStrength));
            mgr.save();
            applyTheme();
        });

        addSettingsRow(appearanceCard, "Transparency", enableTransparencyCb, gbc);
        JPanel transparencySliderPane = new JPanel(new BorderLayout(8, 0));
        transparencySliderPane.setOpaque(false);
        transparencySliderPane.add(transparencySlider, BorderLayout.CENTER);
        transparencySliderPane.add(transparencyValLabel, BorderLayout.EAST);
        addSettingsRow(appearanceCard, "Transparency Strength", transparencySliderPane, gbc);

        // ── Blur (independent of Transparency) ──────────────────────────────
        CustomToggle enableBlurCb = new CustomToggle("Enable blur effect");
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

        addSettingsRow(appearanceCard, "Blur", enableBlurCb, gbc);
        JPanel sliderPane = new JPanel(new BorderLayout(8, 0));
        sliderPane.setOpaque(false);
        sliderPane.add(blurSlider, BorderLayout.CENTER);
        sliderPane.add(blurValLabel, BorderLayout.EAST);
        addSettingsRow(appearanceCard, "Blur Strength", sliderPane, gbc);

        // ── Background image ─────────────────────────────────────────────────
        CustomToggle useBackgroundImageCb = new CustomToggle("Use background image");
        useBackgroundImageCb.setSelected(s.useBackgroundImage);

        JLabel backgroundImagePathLbl = new JLabel(
                s.backgroundImagePath == null || s.backgroundImagePath.isBlank()
                        ? "No image selected"
                        : new File(s.backgroundImagePath).getName());
        backgroundImagePathLbl.setForeground(Color.LIGHT_GRAY);
        backgroundImagePathLbl.setFont(new Font("SansSerif", Font.PLAIN, 11));

        JButton browseImageBtn = new JButton("Browse…");
        JButton clearImageBtn = new JButton("Clear");
        browseImageBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        clearImageBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));

        useBackgroundImageCb.addActionListener(e -> {
            s.useBackgroundImage = useBackgroundImageCb.isSelected();
            mgr.save();
            applyTheme();
        });

        browseImageBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Choose a background image");
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Images (png, jpg, jpeg, gif, bmp, webp)", "png", "jpg", "jpeg", "gif", "bmp", "webp"));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File chosen = chooser.getSelectedFile();
                s.backgroundImagePath = chosen.getAbsolutePath();
                s.useBackgroundImage = true;
                useBackgroundImageCb.setSelected(true);
                backgroundImagePathLbl.setText(chosen.getName());
                mgr.save();
                applyTheme();
            }
        });

        clearImageBtn.addActionListener(e -> {
            s.backgroundImagePath = "";
            s.useBackgroundImage = false;
            useBackgroundImageCb.setSelected(false);
            backgroundImagePathLbl.setText("No image selected");
            mgr.save();
            applyTheme();
        });

        addSettingsRow(appearanceCard, "Background Image", useBackgroundImageCb, gbc);
        JPanel imagePickerPane = new JPanel(new BorderLayout(8, 0));
        imagePickerPane.setOpaque(false);
        JPanel imageButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        imageButtons.setOpaque(false);
        imageButtons.add(browseImageBtn);
        imageButtons.add(clearImageBtn);
        imagePickerPane.add(imageButtons, BorderLayout.WEST);
        imagePickerPane.add(backgroundImagePathLbl, BorderLayout.CENTER);
        addSettingsRow(appearanceCard, "", imagePickerPane, gbc);

        // ── Background style presets ─────────────────────────────────────────
        JComboBox<String> bgStyleBox = new JComboBox<>(new String[] {
                "Default", "Midnight", "Sunset", "Forest", "Ocean", "Monochrome", "Accent Glow"
        });
        bgStyleBox.setSelectedItem(
                s.backgroundStyle == null || s.backgroundStyle.isBlank() ? "Default" : s.backgroundStyle);
        styleComboBox(bgStyleBox);
        bgStyleBox.addActionListener(e -> {
            s.backgroundStyle = (String) bgStyleBox.getSelectedItem();
            mgr.save();
            applyTheme();
        });
        addSettingsRow(appearanceCard, "Background Style", bgStyleBox, gbc);
        addSettingsRow(appearanceCard, "Notification Style", notifStyleCombo, gbc);

        // ── Font family ───────────────────────────────────────────────────────
        // Only sans-serif fonts, the bundled Minecraft font, and any user-added custom
        // fonts are offered here — see FontManager for exactly how that list is built.
        JComboBox<String> fontBox = new JComboBox<>(
                com.launcher.manager.FontManager.buildFontChoices(s).toArray(new String[0]));
        fontBox.setSelectedItem(s.fontFamily == null || s.fontFamily.isBlank() ? "SansSerif" : s.fontFamily);
        fontBox.setMaximumRowCount(12);
        styleComboBox(fontBox);
        fontBox.addActionListener(e -> {
            s.fontFamily = (String) fontBox.getSelectedItem();
            mgr.save();
            applyTheme();
        });

        JButton addFontBtn = new JButton("Add Font…");
        addFontBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        addFontBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Choose a font file");
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Fonts (ttf, otf)", "ttf", "otf"));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File chosen = chooser.getSelectedFile();
                String family = com.launcher.manager.FontManager.registerFontFile(chosen.getAbsolutePath());
                if (family == null) {
                    JOptionPane.showMessageDialog(this,
                            "Couldn't load that file as a font.", "Add Font", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                s.addCustomFontPath(chosen.getAbsolutePath());
                mgr.save();
                fontBox.setModel(new DefaultComboBoxModel<>(
                        com.launcher.manager.FontManager.buildFontChoices(s).toArray(new String[0])));
                fontBox.setSelectedItem(family);
                s.fontFamily = family;
                mgr.save();
                applyTheme();
            }
        });

        JPanel fontPickerPane = new JPanel(new BorderLayout(8, 0));
        fontPickerPane.setOpaque(false);
        fontPickerPane.add(fontBox, BorderLayout.CENTER);
        fontPickerPane.add(addFontBtn, BorderLayout.EAST);
        addSettingsRow(appearanceCard, "Font Family", fontPickerPane, gbc);

        mainPanel.add(appearanceCard);
        mainPanel.add(Box.createVerticalStrut(12));

        // ── 2. BEHAVIOR CARD ──────────────────────────────────────────────────
        JPanel behaviorCard = createCard("Behavior");
        gbc = createGbc();

        CustomToggle minimizeCb = new CustomToggle("Minimize launcher when the game opens");
        minimizeCb.setSelected(s.minimizeOnLaunch);
        minimizeCb.addActionListener(e -> {
            s.minimizeOnLaunch = minimizeCb.isSelected();
            mgr.save();
        });
        addSettingsRow(behaviorCard, "", minimizeCb, gbc);

        CustomToggle restoreCb = new CustomToggle("Show launcher when the game closes");
        restoreCb.setSelected(s.restoreLauncherOnGameClose);
        restoreCb.addActionListener(e -> {
            s.restoreLauncherOnGameClose = restoreCb.isSelected();
            mgr.save();
        });
        addSettingsRow(behaviorCard, "", restoreCb, gbc);

        CustomToggle trayCb = new CustomToggle("Enable system tray icon (requires restart)");
        trayCb.setSelected(s.enableSystemTray);
        trayCb.addActionListener(e -> {
            s.enableSystemTray = trayCb.isSelected();
            mgr.save();
        });
        addSettingsRow(behaviorCard, "", trayCb, gbc);

        CustomToggle closeCb = new CustomToggle("Close launcher after game starts");
        closeCb.setSelected(s.closeAfterLaunch);
        closeCb.addActionListener(e -> {
            s.closeAfterLaunch = closeCb.isSelected();
            mgr.save();
        });
        addSettingsRow(behaviorCard, "", closeCb, gbc);

        CustomToggle showConsoleCb = new CustomToggle("Keep console visible while game is running");
        showConsoleCb.setSelected(s.showConsoleOnLaunch);
        showConsoleCb.addActionListener(e -> {
            s.showConsoleOnLaunch = showConsoleCb.isSelected();
            mgr.save();
        });
        addSettingsRow(behaviorCard, "", showConsoleCb, gbc);

        CustomToggle scanCb = new CustomToggle("Scan .minecraft folder for installed versions on startup");
        scanCb.setSelected(s.scanOnStartup);
        scanCb.addActionListener(e -> {
            s.scanOnStartup = scanCb.isSelected();
            mgr.save();
        });
        addSettingsRow(behaviorCard, "", scanCb, gbc);

        CustomToggle showHiddenCb = new CustomToggle("Show hidden instances");
        showHiddenCb.setSelected(s.showHiddenInstances);
        showHiddenCb.addActionListener(e -> {
            s.showHiddenInstances = showHiddenCb.isSelected();
            mgr.save();
            refreshInstances();
        });
        addSettingsRow(behaviorCard, "", showHiddenCb, gbc);

        CustomToggle smoothScrollCb = new CustomToggle("Enable smooth scrolling");
        smoothScrollCb.setSelected(s.smoothScrolling);
        smoothScrollCb.addActionListener(e -> {
            s.smoothScrolling = smoothScrollCb.isSelected();
            mgr.save();
        });
        addSettingsRow(behaviorCard, "", smoothScrollCb, gbc);

        CustomToggle checkModUpdatesCb = new CustomToggle("Check for mod updates when launcher starts");
        checkModUpdatesCb.setSelected(s.checkModUpdatesOnStartup);
        checkModUpdatesCb.addActionListener(e -> {
            s.checkModUpdatesOnStartup = checkModUpdatesCb.isSelected();
            mgr.save();
        });
        addSettingsRow(behaviorCard, "", checkModUpdatesCb, gbc);

        CustomToggle refreshDiscoverCb = new CustomToggle("Refresh Discover (trending mods/packs) when launcher starts");
        refreshDiscoverCb.setSelected(s.refreshDiscoverOnLaunch);
        refreshDiscoverCb.addActionListener(e -> {
            s.refreshDiscoverOnLaunch = refreshDiscoverCb.isSelected();
            mgr.save();
        });
        addSettingsRow(behaviorCard, "", refreshDiscoverCb, gbc);

        CustomToggle autoRefreshOnFailCb = new CustomToggle("Auto-refresh mods & resource packs if a version fails to load");
        autoRefreshOnFailCb.setSelected(s.autoRefreshModsOnVersionLoadFail);
        autoRefreshOnFailCb.addActionListener(e -> {
            s.autoRefreshModsOnVersionLoadFail = autoRefreshOnFailCb.isSelected();
            mgr.save();
        });
        addSettingsRow(behaviorCard, "", autoRefreshOnFailCb, gbc);

        mainPanel.add(behaviorCard);
        mainPanel.add(Box.createVerticalStrut(12));

        // ── 3. WINDOW CARD ────────────────────────────────────────────────────
        JPanel sizeCard = createCard("Window");
        gbc = createGbc();

        CustomToggle customTitleBarCb = new CustomToggle("Use custom in-app title bar (hide the OS window frame)");
        customTitleBarCb.setSelected(s.useCustomTitleBar);
        customTitleBarCb.addActionListener(e -> {
            s.useCustomTitleBar = customTitleBarCb.isSelected();
            mgr.save();
            promptRestartForTitleBarChange();
        });
        addSettingsRow(sizeCard, "", customTitleBarCb, gbc);

        CustomToggle startMaximizedCb = new CustomToggle("Always launch maximized");
        startMaximizedCb.setSelected(s.startMaximized);
        startMaximizedCb.addActionListener(e -> {
            s.startMaximized = startMaximizedCb.isSelected();
            mgr.save();
            if (s.startMaximized) {
                setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);
            }
        });
        addSettingsRow(sizeCard, "", startMaximizedCb, gbc);

        int savedW = (s.launcherWidth >= 820) ? s.launcherWidth : 960;
        int savedH = (s.launcherHeight >= 560) ? s.launcherHeight : 660;

        SpinnerModel widthModel = new SpinnerNumberModel(savedW, 820, 3840, 10);
        CustomSpinner widthSpinner = new CustomSpinner(widthModel);
        widthSpinner.addChangeListener(e -> {
            s.launcherWidth = (int) widthSpinner.getValue();
            mgr.save();
        });
        addSettingsRow(sizeCard, "Width (px)", widthSpinner, gbc);

        SpinnerModel heightModel = new SpinnerNumberModel(savedH, 560, 2160, 10);
        CustomSpinner heightSpinner = new CustomSpinner(heightModel);
        heightSpinner.addChangeListener(e -> {
            s.launcherHeight = (int) heightSpinner.getValue();
            mgr.save();
        });
        addSettingsRow(sizeCard, "Height (px)", heightSpinner, gbc);

        JButton applySizeBtn = new JButton("Apply Now");
        applySizeBtn.addActionListener(e -> {
            if ((getExtendedState() & JFrame.MAXIMIZED_BOTH) == 0) {
                setSize(s.launcherWidth >= 820 ? s.launcherWidth : 960,
                        s.launcherHeight >= 560 ? s.launcherHeight : 660);
                setLocationRelativeTo(null);
                captureNormalBoundsIfApplicable();
                log("Applied window size " + s.launcherWidth + "x" + s.launcherHeight + ".");
            }
        });
        addSettingsRow(sizeCard, "", applySizeBtn, gbc);

        mainPanel.add(sizeCard);
        mainPanel.add(Box.createVerticalStrut(12));

        // ── 4. PERFORMANCE CARD ───────────────────────────────────────────────
        JPanel performanceCard = createCard("Performance");
        gbc = createGbc();

        SpinnerModel ramModel = new SpinnerNumberModel(s.defaultRamGb > 0 ? s.defaultRamGb : 3, 1, 64, 1);
        CustomSpinner ramSpinner = new CustomSpinner(ramModel);
        ramSpinner.addChangeListener(e -> {
            s.defaultRamGb = (int) ramSpinner.getValue();
            mgr.save();
            log("Default RAM set to " + s.defaultRamGb + " GB.");
        });
        addSettingsRow(performanceCard, "Default RAM (GB)", ramSpinner, gbc);

        CustomTextField extraJvmField = new CustomTextField(s.extraJvmArgs != null ? s.extraJvmArgs : "");
        extraJvmField.addActionListener(e -> {
            s.extraJvmArgs = extraJvmField.getText().trim();
            s.jvmArgs = s.extraJvmArgs;
            mgr.save();
        });
        extraJvmField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                s.extraJvmArgs = extraJvmField.getText().trim();
                s.jvmArgs = s.extraJvmArgs;
                mgr.save();
            }
        });
        addSettingsRow(performanceCard, "Extra JVM Arguments", extraJvmField, gbc);

        CustomTextField javaPathField = new CustomTextField(s.javaPath != null ? s.javaPath : "");
        javaPathField.addActionListener(e -> {
            s.javaPath = javaPathField.getText().trim();
            mgr.save();
        });
        javaPathField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                s.javaPath = javaPathField.getText().trim();
                mgr.save();
            }
        });
        addSettingsRow(performanceCard, "Java Executable Path", javaPathField, gbc);

        CustomToggle enableLauncherRamCb = new CustomToggle("Enable Max Launcher RAM");
        enableLauncherRamCb.setSelected(s.enableLauncherMaxRam);

        SpinnerModel launcherRamModel = new SpinnerNumberModel(s.launcherMaxRamMb > 0 ? s.launcherMaxRamMb : 500, 128,
                8192, 64);
        CustomSpinner launcherRamSpinner = new CustomSpinner(launcherRamModel);
        launcherRamSpinner.setEnabled(s.enableLauncherMaxRam);
        launcherRamSpinner.addChangeListener(e -> {
            s.launcherMaxRamMb = (int) launcherRamSpinner.getValue();
            mgr.save();
            log("Launcher max RAM set to " + s.launcherMaxRamMb + " MB. Restart the launcher to apply.");
        });

        enableLauncherRamCb.addActionListener(e -> {
            s.enableLauncherMaxRam = enableLauncherRamCb.isSelected();
            launcherRamSpinner.setEnabled(s.enableLauncherMaxRam);
            mgr.save();
            log(s.enableLauncherMaxRam
                    ? "Launcher max RAM limit enabled (" + s.launcherMaxRamMb + " MB). Restart the launcher to apply."
                    : "Launcher max RAM limit disabled. Restart the launcher to apply.");
        });

        addSettingsRow(performanceCard, "", enableLauncherRamCb, gbc);
        addSettingsRow(performanceCard, "Max RAM for Launcher (MB)", launcherRamSpinner, gbc);

        mainPanel.add(performanceCard);
        mainPanel.add(Box.createVerticalStrut(12));

        // ── 5. PRIVACY & SECURITY CARD ────────────────────────────────────────
        JPanel privacyCard = createCard("Privacy & Security");
        gbc = createGbc();

        CustomToggle hideUserCb = new CustomToggle("Hide username in launcher UI");
        hideUserCb.setSelected(s.hideUsername);
        hideUserCb.addActionListener(e -> {
            s.hideUsername = hideUserCb.isSelected();
            mgr.save();
            refreshAccounts();
        });
        addSettingsRow(privacyCard, "", hideUserCb, gbc);

        CustomToggle redactPathsCb = new CustomToggle("Redact OS username from log paths");
        redactPathsCb.setSelected(s.redactPaths);
        redactPathsCb.addActionListener(e -> {
            s.redactPaths = redactPathsCb.isSelected();
            mgr.save();
            Instance keepSelected = instanceList.getSelectedValue();
            refreshInstances();
            if (keepSelected != null)
                instanceList.setSelectedValue(keepSelected, true);
        });
        addSettingsRow(privacyCard, "", redactPathsCb, gbc);

        CustomToggle redactTokensCb = new CustomToggle("Redact Minecraft session tokens in logs");
        redactTokensCb.setSelected(s.redactTokens);
        redactTokensCb.addActionListener(e -> {
            s.redactTokens = redactTokensCb.isSelected();
            mgr.save();
        });
        addSettingsRow(privacyCard, "", redactTokensCb, gbc);

        CustomToggle clearSessionCb = new CustomToggle("Clear account sessions when the launcher closes");
        clearSessionCb.setSelected(s.clearSessionOnExit);
        clearSessionCb.addActionListener(e -> {
            s.clearSessionOnExit = clearSessionCb.isSelected();
            mgr.save();
        });
        addSettingsRow(privacyCard, "", clearSessionCb, gbc);

        mainPanel.add(privacyCard);
        mainPanel.add(Box.createVerticalStrut(12));

        // ── 6. DISCORD RPC CARD ───────────────────────────────────────────────
        JPanel discordCard = createCard("Discord RPC (Forced Off)");
        gbc = createGbc();

        CustomToggle enableRpcCb = new CustomToggle("Enable Discord Rich Presence");
        enableRpcCb.setSelected(false);
        enableRpcCb.setEnabled(false);
        addSettingsRow(discordCard, "", enableRpcCb, gbc);

        CustomToggle showServerCb = new CustomToggle("Show connected server IP in Discord status");
        showServerCb.setSelected(false);
        showServerCb.setEnabled(false);
        addSettingsRow(discordCard, "", showServerCb, gbc);

        CustomTextField rpcNameField = new CustomTextField(
                s.customDiscordRpcName != null ? s.customDiscordRpcName : "Zero Launcher");
        rpcNameField.setEnabled(false);
        addSettingsRow(discordCard, "Custom RPC Name", rpcNameField, gbc);

        JLabel discordNote = new JLabel(
                "<html><body style='color:#ef4444;'>⚠ Currently this is not available. Try using a mod like Vanilla RPC instead.</body></html>");
        addSettingsRow(discordCard, "", discordNote, gbc);

        JButton findVanillaRpcBtn = new JButton("Find Vanilla RPC in Discover");
        findVanillaRpcBtn.addActionListener(e -> {
            mainTabPane.setSelectedIndex(2); // Discover tab
            if (discoverSearchField != null) {
                discoverSearchField.setText("Vanilla RPC");
            }
            discoverOffset = 0;
            performDiscoverSearch();
        });
        addSettingsRow(discordCard, "", findVanillaRpcBtn, gbc);

        mainPanel.add(discordCard);
        mainPanel.add(Box.createVerticalStrut(12));

        // ── 7. RESET CARD ──────────────────────────────────────────────────
        JPanel resetCard = createCard("Reset");
        gbc = createGbc();

        JButton resetAllBtn = new JButton("Reset All Settings");
        resetAllBtn.setForeground(new Color(239, 68, 68));
        resetAllBtn.addActionListener(e -> {
            int choice = JOptionPane.showConfirmDialog(this,
                    "This will reset ALL launcher settings back to their defaults. Continue?",
                    "Reset All Settings", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                mgr.resetToDefaults();
                applyTheme();
                int idx = mainTabPane.getSelectedIndex();
                mainTabPane.setComponentAt(3, buildSettingsArea());
                mainTabPane.setSelectedIndex(idx);
                log("All settings have been reset to defaults.");

                // Reset restores the defaults (Transparency on) via applyTheme() above, but the
                // background can still render "goofy" right after — same underlying redraw
                // quirk as on a fresh app start. Toggling Transparency off then back on forces
                // Swing to properly recompute it, so do that automatically here instead of
                // making the user click it manually.
                com.launcher.model.LauncherSettings freshSettings = mgr.getSettings();
                if (freshSettings.enableTransparency) {
                    freshSettings.enableTransparency = false;
                    applyTheme();
                    javax.swing.Timer flashBackTimer = new javax.swing.Timer(1000, ev -> {
                        freshSettings.enableTransparency = true;
                        mgr.save();
                        applyTheme();
                    });
                    flashBackTimer.setRepeats(false);
                    flashBackTimer.start();
                }
            }
        });
        addSettingsRow(resetCard, "", resetAllBtn, gbc);

        mainPanel.add(resetCard);

        JScrollPane scroll = new JScrollPane(mainPanel);
        com.launcher.ui.SmoothScroll.install(scroll);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private GridBagConstraints createGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.gridy = 0;
        return gbc;
    }

    private void addSettingsRow(JPanel panel, String label, JComponent comp, GridBagConstraints gbc) {
        gbc.gridx = 0;
        if (label != null && !label.isEmpty()) {
            JLabel lbl = new JLabel(label);
            lbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
            lbl.setForeground(hexToColor(com.launcher.manager.SettingsManager.getInstance().getSettings().textColor,
                    new Color(226, 226, 234)));
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

    /**
     * Creates a JPanel containing a JTextField for hex color input and a JButton
     * that acts as a color swatch
     * and opens a JColorChooser.
     * 
     * @param labelText The label for the color setting.
     * @param getter    A Supplier to get the current hex color string from
     *                  settings.
     * @param setter    A Consumer to set the new hex color string in settings.
     * @param onUpdate  A Consumer to call after the color is updated (e.g., to
     *                  apply theme).
     * @return A JPanel containing the color input components.
     */
    private JPanel createColorInputRow(String labelText, Supplier<String> getter, Consumer<String> setter,
            Consumer<String> onUpdate, String defaultHex) {
        JPanel rowPanel = new JPanel(new BorderLayout(8, 0));
        rowPanel.setOpaque(false); // Inherit background from parent

        CustomTextField hexField = new CustomTextField(getter.get());
        hexField.setPreferredSize(new Dimension(80, 22));
        hexField.addActionListener(e -> {
            String newHex = hexField.getText().trim();
            setter.accept(newHex);
            onUpdate.accept(newHex);
        });
        hexField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                String newHex = hexField.getText().trim();
                setter.accept(newHex);
                onUpdate.accept(newHex);
            }
        });

        JButton colorSwatch = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(hexToColor(getter.get(), Color.BLACK)); // Use current color from settings
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(Color.DARK_GRAY);
                g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                g2.dispose();
            }
        };
        colorSwatch.setPreferredSize(new Dimension(22, 22));
        colorSwatch.setToolTipText("Pick Color");
        colorSwatch.addActionListener(e -> {
            Color initialColor = hexToColor(getter.get(), Color.BLACK);
            Color chosenColor = JColorChooser.showDialog(this, "Choose " + labelText, initialColor);
            if (chosenColor != null) {
                String newHex = String.format("#%06x", chosenColor.getRGB() & 0xFFFFFF);
                hexField.setText(newHex);
                setter.accept(newHex);
                onUpdate.accept(newHex);
            }
        });

        JButton resetBtn = new JButton("Reset");
        resetBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
        resetBtn.setMargin(new Insets(1, 6, 1, 6));
        resetBtn.setToolTipText("Reset to default (" + defaultHex + ")");
        resetBtn.addActionListener(e -> {
            hexField.setText(defaultHex);
            setter.accept(defaultHex);
            onUpdate.accept(defaultHex);
        });

        rowPanel.add(fieldLabel(labelText, hexToColor(
                com.launcher.manager.SettingsManager.getInstance().getSettings().textColor, new Color(226, 226, 234))),
                BorderLayout.WEST);
        JPanel inputPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        inputPanel.setOpaque(false);
        inputPanel.add(hexField);
        inputPanel.add(colorSwatch);
        inputPanel.add(resetBtn);
        rowPanel.add(inputPanel, BorderLayout.CENTER);

        return rowPanel;
    }

    /**
     * Restyles a JComboBox to match the app's rounded, themed pill chrome
     * (same look as {@link com.launcher.ui.CustomTextField}), instead of the
     * default OS combo box appearance. Works with any item type/model, so it
     * can be applied to existing combo boxes without changing their logic.
     */
    private void styleComboBox(JComboBox<?> combo) {
        com.launcher.model.LauncherSettings s = com.launcher.manager.SettingsManager.getInstance().getSettings();
        Color panelBg = hexToColor(s.panelBgColor, new Color(19, 19, 26));
        Color fill = new Color(
                Math.min(255, panelBg.getRed() + 12),
                Math.min(255, panelBg.getGreen() + 12),
                Math.min(255, panelBg.getBlue() + 12));
        Color textColor = hexToColor(s.textColor, new Color(226, 226, 234));
        Color accent = hexToColor(s.accentColor, new Color(16, 185, 129));

        combo.setOpaque(false);
        combo.setFont(new Font("SansSerif", Font.PLAIN, 12));
        combo.setForeground(textColor);
        combo.setBackground(fill);
        combo.setBorder(new EmptyBorder(2, 8, 2, 4));
        combo.setFocusable(true);

        combo.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
            @Override
            protected JButton createArrowButton() {
                JButton arrow = new JButton("▾");
                arrow.setFont(new Font("SansSerif", Font.PLAIN, 10));
                arrow.setForeground(new Color(255, 255, 255, 140));
                arrow.setContentAreaFilled(false);
                arrow.setBorderPainted(false);
                arrow.setFocusPainted(false);
                arrow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                return arrow;
            }

            @Override
            public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, comboBox.getWidth() - 1, comboBox.getHeight() - 1, 10, 10);
                g2.setStroke(new BasicStroke(hasFocus ? 1.4f : 1f));
                g2.setColor(hasFocus ? accent : new Color(255, 255, 255, 35));
                g2.drawRoundRect(0, 0, comboBox.getWidth() - 1, comboBox.getHeight() - 1, 10, 10);
                g2.dispose();
            }
        });

        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                c.setForeground(isSelected ? Color.WHITE : textColor);
                c.setBackground(isSelected ? accent : fill);
                if (c instanceof JComponent jc) {
                    jc.setBorder(new EmptyBorder(4, 8, 4, 8));
                    jc.setOpaque(true);
                }
                return c;
            }
        });
    }

    private JLabel fieldLabel(String text, Color color) {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        // Add any other desired styling here (e.g., Font, Border)
        return label;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LOG TOGGLE BUTTON (floating, bottom-left, GNOME-Terminal-style icon)
    // ══════════════════════════════════════════════════════════════════════════
    private RoundedPanel buildTerminalToggleButton() {
        boolean startsVisible = com.launcher.manager.SettingsManager.getInstance().getSettings().logConsoleVisible;
        Color accent = hexToColor(com.launcher.manager.SettingsManager.getInstance().getSettings().accentColor,
                new Color(16, 185, 129));

        // The icon itself paints nothing but a single clean prompt glyph — the
        // frosted-glass
        // RoundedPanel behind it supplies the blurred, translucent background. (An
        // earlier
        // version also drew tiny traffic-light dots, but at this button's small size
        // they
        // just read as visual noise rather than a recognizable terminal window.)
        JButton icon = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth(), h = getHeight();
                boolean pressed = getModel().isPressed();
                boolean hover = getModel().isRollover();

                // Subtle hover/press wash so it still feels clickable through the glass.
                if (pressed || hover) {
                    g2.setColor(new Color(255, 255, 255, pressed ? 10 : 18));
                    g2.fillRoundRect(0, 0, w, h, 10, 10);
                }

                // Single centered terminal prompt glyph.
                g2.setColor(accent);
                g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, (int) (h * 0.42)));
                FontMetrics fm = g2.getFontMetrics();
                String prompt = ">_";
                int textW = fm.stringWidth(prompt);
                int textX = (w - textW) / 2;
                int textY = (h + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(prompt, textX, textY);

                g2.dispose();
            }
        };
        icon.setContentAreaFilled(false);
        icon.setBorderPainted(false);
        icon.setFocusPainted(false);
        icon.setOpaque(false);
        icon.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        icon.setToolTipText(startsVisible ? "Hide Log" : "Show Log");
        icon.addActionListener(e -> {
            boolean nowVisible = !logAreaPanel.isVisible();
            logAreaPanel.setVisible(nowVisible);
            icon.setToolTipText(nowVisible ? "Hide Log" : "Show Log");
            com.launcher.manager.SettingsManager.getInstance().getSettings().logConsoleVisible = nowVisible;
            com.launcher.manager.SettingsManager.getInstance().save();
            if (logAreaPanel.getParent() != null) {
                logAreaPanel.getParent().revalidate();
                logAreaPanel.getParent().repaint();
            }
        });

        // Fully transparent background — no frosted glass, no fill, no border — so only
        // the
        // ">_" glyph (plus its faint hover wash) is visible, floating directly over
        // whatever
        // is behind it.
        RoundedPanel wrap = new RoundedPanel(10, new Color(0, 0, 0, 0), null);
        wrap.putClientProperty("keepCustomBg", Boolean.TRUE);
        wrap.setLayout(new BorderLayout());
        wrap.add(icon, BorderLayout.CENTER);
        return wrap;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DOWNLOADS TOGGLE BUTTON (floating, top-center — only visible while active)
    // ══════════════════════════════════════════════════════════════════════════
    /**
     * Small floating pill, top-center of the window, that only appears while
     * {@link com.launcher.manager.DownloadManager} has at least one active
     * download. Clicking
     * it opens a dialog listing every tracked download (mod installs/updates, the
     * Dawn client
     * jar, game file installs, …) and its live status.
     */
    /**
     * Builds the current label for the downloads toggle pill, e.g. "1 download in
     * progress"
     * or "3 downloads in progress".
     */
    private String downloadsToggleLabel() {
        int count = com.launcher.manager.DownloadManager.getInstance().activeCount();
        if (count <= 0)
            return "Downloads";
        return count == 1 ? "1 download in progress" : (count + " downloads in progress");
    }

    /**
     * Measures how wide the toggle pill needs to be to fit its current label, so it
     * can be
     * centered top and grow/shrink cleanly as the label text changes.
     */
    private int downloadsToggleWidth() {
        Font f = new Font(Font.SANS_SERIF, Font.BOLD, 13);
        FontMetrics fm = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().getFontMetrics(f);
        int textW = fm.stringWidth(downloadsToggleLabel());
        // glyph + gap + text + left/right padding
        return textW + 20 /* glyph+gap */ + 28 /* padding */;
    }

    /**
     * Re-centers the downloads toggle pill top-center, recomputing its width from
     * the current
     * label so it never looks clipped or oddly padded.
     */
    private void repositionDownloadsToggle() {
        if (downloadsToggleWrap == null || layeredPane == null)
            return;
        int width = downloadsToggleWidth();
        int x = (layeredPane.getWidth() - width) / 2;
        downloadsToggleWrap.setBounds(x, 16, width, DOWNLOAD_TOGGLE_HEIGHT);
    }

    private RoundedPanel buildDownloadsToggleButton() {
        JButton icon = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth(), h = getHeight();
                boolean pressed = getModel().isPressed();
                boolean hover = getModel().isRollover();
                if (pressed || hover) {
                    g2.setColor(new Color(255, 255, 255, pressed ? 20 : 34));
                    g2.fillRoundRect(0, 0, w, h, 12, 12);
                }

                String label = downloadsToggleLabel();
                Font glyphFont = new Font(Font.SANS_SERIF, Font.BOLD, (int) (h * 0.42));
                Font textFont = new Font(Font.SANS_SERIF, Font.BOLD, 13);

                g2.setColor(Color.WHITE);
                g2.setFont(glyphFont);
                FontMetrics gfm = g2.getFontMetrics();
                String glyph = "\u2193"; // ↓
                int glyphW = gfm.stringWidth(glyph);

                g2.setFont(textFont);
                FontMetrics tfm = g2.getFontMetrics();
                int textW = tfm.stringWidth(label);

                int gap = 8;
                int totalW = glyphW + gap + textW;
                int startX = Math.max(10, (w - totalW) / 2);

                g2.setFont(glyphFont);
                int glyphY = (h + gfm.getAscent() - gfm.getDescent()) / 2;
                g2.drawString(glyph, startX, glyphY);

                g2.setFont(textFont);
                int textX = startX + glyphW + gap;
                int textY = (h + tfm.getAscent() - tfm.getDescent()) / 2;
                g2.drawString(label, textX, textY);

                g2.dispose();
            }
        };
        icon.setContentAreaFilled(false);
        icon.setBorderPainted(false);
        icon.setFocusPainted(false);
        icon.setOpaque(false);
        icon.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        icon.setToolTipText("Downloads in progress — click to view");
        icon.addActionListener(e -> toggleDownloadsPopover());

        Color accent = hexToColor(com.launcher.manager.SettingsManager.getInstance().getSettings().accentColor,
                new Color(16, 185, 129));
        RoundedPanel downloadsWrap = new RoundedPanel(12,
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 210),
                new Color(255, 255, 255, 60));
        downloadsWrap.putClientProperty("keepCustomBg", Boolean.TRUE);
        downloadsWrap.setLayout(new BorderLayout());
        downloadsWrap.add(icon, BorderLayout.CENTER);
        downloadsWrap.setVisible(false);
        // Frosted-glass look: blur whatever's behind the pill instead of a flat fill,
        // tinted
        // with the accent color so it still reads as "on" at a glance.
        downloadsWrap.setFrostedGlass(layeredPane, 10,
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 90));
        return downloadsWrap;
    }

    /**
     * Called whenever DownloadManager reports a change, and once after theme
     * changes — shows
     * or hides the floating downloads button, and keeps its accent-tinted pill
     * on-theme.
     */
    private void refreshDownloadsToggleVisibility() {
        if (downloadsToggleWrap == null)
            return;
        boolean active = com.launcher.manager.DownloadManager.getInstance().hasActive();
        Color accent = hexToColor(com.launcher.manager.SettingsManager.getInstance().getSettings().accentColor,
                new Color(16, 185, 129));
        downloadsToggleWrap.setColors(
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 210),
                new Color(255, 255, 255, 60));
        downloadsToggleWrap.setFrostedGlass(layeredPane, 10,
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 90));
        repositionDownloadsToggle();
        if (active != downloadsToggleShown) {
            downloadsToggleShown = active;
            if (active) {
                fadeIn(downloadsToggleWrap, 220);
            } else {
                fadeOut(downloadsToggleWrap, 160, null);
            }
        }
        downloadsToggleWrap.repaint();
        if (downloadsPopover != null && downloadsPopover.isVisible()) {
            refreshDownloadsDialogContent();
        }
    }

    /**
     * Fades a RoundedPanel in from fully transparent to fully opaque over
     * {@code durationMs},
     * making it visible first so the fade is seen rather than an abrupt pop-in.
     * Also slides it
     * up into place from just below its resting position, so it reads as "arriving
     * from below"
     * rather than simply materializing.
     */
    private void fadeIn(RoundedPanel panel, int durationMs) {
        if (panel == null)
            return;
        int targetX = panel.getX(), targetY = panel.getY(), w = panel.getWidth(), h = panel.getHeight();
        int slideDistance = 24;
        panel.setAlpha(0f);
        panel.setBounds(targetX, targetY + slideDistance, w, h);
        panel.setVisible(true);
        long start = System.currentTimeMillis();
        javax.swing.Timer[] holder = new javax.swing.Timer[1];
        holder[0] = new javax.swing.Timer(15, e -> {
            float p = Math.min(1f, (System.currentTimeMillis() - start) / (float) durationMs);
            float eased = 1f - (1f - p) * (1f - p); // ease-out, so it settles gently
            panel.setAlpha(p);
            int y = Math.round(targetY + slideDistance * (1 - eased));
            panel.setBounds(panel.getX(), y, panel.getWidth(), panel.getHeight());
            if (p >= 1f)
                holder[0].stop();
        });
        holder[0].start();
    }

    /**
     * Fades a RoundedPanel out from its current opacity to fully transparent over
     * {@code durationMs}, then hides it (and runs {@code onDone}, if given). Also
     * slides it
     * down and out as it fades, the reverse of {@link #fadeIn}, so it reads as
     * "leaving
     * downward" rather than simply vanishing in place.
     */
    private void fadeOut(RoundedPanel panel, int durationMs, Runnable onDone) {
        if (panel == null)
            return;
        float startAlpha = panel.getAlpha();
        int startX = panel.getX(), startY = panel.getY(), w = panel.getWidth(), h = panel.getHeight();
        int slideDistance = 24;
        long start = System.currentTimeMillis();
        javax.swing.Timer[] holder = new javax.swing.Timer[1];
        holder[0] = new javax.swing.Timer(15, e -> {
            float p = Math.min(1f, (System.currentTimeMillis() - start) / (float) durationMs);
            float eased = p * p; // ease-in, so it accelerates away as it fades
            panel.setAlpha(startAlpha * (1 - p));
            int y = Math.round(startY + slideDistance * eased);
            panel.setBounds(startX, y, w, h);
            if (p >= 1f) {
                holder[0].stop();
                panel.setVisible(false);
                panel.setAlpha(1f);
                panel.setBounds(startX, startY, w, h);
                if (onDone != null)
                    onDone.run();
            }
        });
        holder[0].start();
    }

    /**
     * Builds the downloads popover — a rounded panel that lives inside the
     * launcher's own
     * layered pane (not a separate OS window) and is shown/hidden anchored under
     * the toggle
     * button, the same way other in-app overlays (notifications, log toggle)
     * already work.
     */
    private RoundedPanel buildDownloadsPopover() {
        RoundedPanel popover = new RoundedPanel(18, new Color(20, 20, 26, 250), new Color(255, 255, 255, 34));
        popover.putClientProperty("keepCustomBg", Boolean.TRUE);
        popover.setLayout(new BorderLayout());
        // Frosted-glass backdrop: blur whatever's behind the popover instead of a flat
        // panel,
        // with a dark tint washed over it so text stays legible against a bright
        // background.
        popover.setFrostedGlass(layeredPane, 8, new Color(12, 12, 16, 150));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(12, 16, 8, 10));

        JLabel title = new JLabel("Downloads");
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        header.add(title, BorderLayout.WEST);

        JButton closeBtn = new JButton("\u2715");
        closeBtn.setToolTipText("Close");
        closeBtn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        closeBtn.setFocusPainted(false);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setOpaque(false);
        closeBtn.setMargin(new Insets(4, 10, 4, 10));
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.addActionListener(e -> animateDownloadsPopoverHide());
        header.add(closeBtn, BorderLayout.EAST);

        popover.add(header, BorderLayout.NORTH);

        downloadsListPanel = new JPanel();
        downloadsListPanel.setOpaque(false);
        downloadsListPanel.setLayout(new BoxLayout(downloadsListPanel, BoxLayout.Y_AXIS));
        downloadsListPanel.setBorder(new EmptyBorder(0, 14, 14, 14));

        JScrollPane scroll = new JScrollPane(downloadsListPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        com.launcher.ui.SmoothScroll.install(scroll);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        popover.add(scroll, BorderLayout.CENTER);

        return popover;
    }

    /**
     * Keeps the popover anchored just below the toggle button and correctly sized,
     * whether
     * called on resize or right before showing it.
     */
    private void positionDownloadsPopover() {
        if (downloadsPopover == null || downloadsToggleWrap == null)
            return;
        int width = 440, height = 520;
        int x = downloadsToggleWrap.getX() + downloadsToggleWrap.getWidth() / 2 - width / 2;
        x = Math.max(8, Math.min(x, layeredPane.getWidth() - width - 8));
        int y = downloadsToggleWrap.getY() + downloadsToggleWrap.getHeight() + 8;
        downloadsPopover.setBounds(x, y, width, Math.min(height, Math.max(160, layeredPane.getHeight() - y - 16)));
    }

    /**
     * Shows or hides the in-window downloads popover — clicking the toggle button
     * never opens
     * a separate popup window, it just reveals/hides this panel inside the launcher
     * itself,
     * animated with a short fade + slide instead of an abrupt pop.
     */
    private void toggleDownloadsPopover() {
        if (downloadsPopover == null)
            return;
        if (downloadsPopover.isVisible()) {
            animateDownloadsPopoverHide();
        } else {
            refreshDownloadsDialogContent();
            animateDownloadsPopoverShow();
        }
    }

    /**
     * Fades + slides the downloads popover in from just above its resting position.
     */
    private void animateDownloadsPopoverShow() {
        if (downloadsPopover == null)
            return;
        positionDownloadsPopover();
        Rectangle target = downloadsPopover.getBounds();
        int slide = 14;
        downloadsPopover.setBounds(target.x, target.y - slide, target.width, target.height);
        downloadsPopover.setAlpha(0f);
        layeredPane.setLayer(downloadsPopover, JLayeredPane.MODAL_LAYER);
        downloadsPopover.setVisible(true);
        long start = System.currentTimeMillis();
        int duration = 190;
        javax.swing.Timer[] holder = new javax.swing.Timer[1];
        holder[0] = new javax.swing.Timer(15, e -> {
            float p = Math.min(1f, (System.currentTimeMillis() - start) / (float) duration);
            float eased = 1 - (1 - p) * (1 - p); // ease-out, feels snappier than linear
            downloadsPopover.setAlpha(eased);
            int y = target.y - Math.round(slide * (1 - eased));
            downloadsPopover.setBounds(target.x, y, target.width, target.height);
            if (p >= 1f) {
                downloadsPopover.setBounds(target);
                holder[0].stop();
            }
        });
        holder[0].start();
    }

    /** Fades + slides the downloads popover out, then hides it. */
    private void animateDownloadsPopoverHide() {
        if (downloadsPopover == null || !downloadsPopover.isVisible())
            return;
        Rectangle from = downloadsPopover.getBounds();
        float startAlpha = downloadsPopover.getAlpha();
        int slide = 10;
        long start = System.currentTimeMillis();
        int duration = 150;
        javax.swing.Timer[] holder = new javax.swing.Timer[1];
        holder[0] = new javax.swing.Timer(15, e -> {
            float p = Math.min(1f, (System.currentTimeMillis() - start) / (float) duration);
            downloadsPopover.setAlpha(startAlpha * (1 - p));
            downloadsPopover.setBounds(from.x, from.y - Math.round(slide * p), from.width, from.height);
            if (p >= 1f) {
                holder[0].stop();
                downloadsPopover.setVisible(false);
                downloadsPopover.setAlpha(1f);
                downloadsPopover.setBounds(from);
            }
        });
        holder[0].start();
    }

    /**
     * Rebuilds the downloads popover's list of cards from the current
     * DownloadManager state.
     */
    private void refreshDownloadsDialogContent() {
        if (downloadsListPanel == null)
            return;
        com.launcher.model.LauncherSettings settings = com.launcher.manager.SettingsManager.getInstance().getSettings();
        Color text = hexToColor(settings.textColor, new Color(226, 226, 234));
        Color accent = hexToColor(settings.accentColor, new Color(16, 185, 129));

        downloadsListPanel.removeAll();

        List<com.launcher.manager.DownloadManager.DownloadItem> downloadItems = com.launcher.manager.DownloadManager
                .getInstance().snapshotNewestFirst();

        if (downloadItems.isEmpty()) {
            JLabel empty = new JLabel("No downloads yet.");
            empty.setForeground(text);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            downloadsListPanel.add(empty);
        } else {
            for (com.launcher.manager.DownloadManager.DownloadItem item : downloadItems) {
                downloadsListPanel.add(buildDownloadItemCard(item, text, accent));
                downloadsListPanel.add(Box.createVerticalStrut(8));
            }
        }
        downloadsListPanel.revalidate();
        downloadsListPanel.repaint();
    }

    /**
     * Builds one "card" row (name, status text, progress bar + percentage, and
     * Pause/Cancel
     * controls) for the downloads popover.
     */
    private JComponent buildDownloadItemCard(com.launcher.manager.DownloadManager.DownloadItem item, Color text,
            Color accent) {
        boolean running = item.state == com.launcher.manager.DownloadManager.State.RUNNING;
        boolean paused = item.state == com.launcher.manager.DownloadManager.State.PAUSED;
        boolean active = running || paused;

        RoundedPanel card = new RoundedPanel(14, new Color(255, 255, 255, 14), new Color(255, 255, 255, 26));
        card.putClientProperty("keepCustomBg", Boolean.TRUE);
        card.setLayout(new BorderLayout(0, 10));
        card.setBorder(new EmptyBorder(14, 16, 14, 16));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));

        // ── Header row: state glyph + name on the left, Pause/Resume + Cancel (X) on
        // the
        // right. The Cancel button is always shown while active, and made
        // larger/clearer
        // than a tiny icon so it reads as an actual "stop this download" control.
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);

        String stateGlyph = switch (item.state) {
            case DONE -> "\u2713";
            case FAILED -> "\u26A0";
            case CANCELLED -> "\u2715";
            case PAUSED -> "\u23F8";
            default -> "\u2193";
        };
        Color stateColor = switch (item.state) {
            case DONE -> new Color(34, 197, 94);
            case FAILED -> new Color(239, 68, 68);
            case CANCELLED -> new Color(148, 148, 158);
            case PAUSED -> new Color(234, 179, 8);
            default -> accent;
        };
        JLabel glyphLbl = new JLabel(stateGlyph);
        glyphLbl.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        glyphLbl.setForeground(stateColor);

        JLabel nameLbl = new JLabel(item.name);
        nameLbl.setFont(new Font("SansSerif", Font.BOLD, 14));
        nameLbl.setForeground(text);

        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        titleRow.setOpaque(false);
        titleRow.add(glyphLbl);
        titleRow.add(nameLbl);
        header.add(titleRow, BorderLayout.WEST);

        if (active) {
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            controls.setOpaque(false);

            JButton pauseBtn = downloadControlButton(paused ? "\u25B6 Resume" : "\u23F8 Pause",
                    paused ? "Resume this download" : "Pause this download", false);
            pauseBtn.addActionListener(e -> {
                if (paused) {
                    com.launcher.manager.DownloadManager.getInstance().requestResume(item.id);
                } else {
                    com.launcher.manager.DownloadManager.getInstance().requestPause(item.id);
                }
                refreshDownloadsDialogContent();
            });
            controls.add(pauseBtn);

            JButton cancelBtn = downloadControlButton("\u2715", "Cancel this download", true);
            cancelBtn.addActionListener(e -> {
                com.launcher.manager.DownloadManager.getInstance().requestCancel(item.id);
                refreshDownloadsDialogContent();
            });
            controls.add(cancelBtn);

            header.add(controls, BorderLayout.EAST);
        }
        card.add(header, BorderLayout.NORTH);

        // ── Custom rounded progress bar (bigger + nicer than the default L&F bar),
        // with the
        // percentage/state painted on top of the fill.
        String barLabel;
        if (item.percent >= 0) {
            barLabel = paused ? "Paused · " + item.percent + "%" : item.percent + "%";
        } else {
            barLabel = switch (item.state) {
                case PAUSED -> "Paused";
                case DONE -> "Complete";
                case FAILED -> "Failed";
                case CANCELLED -> "Cancelled";
                default -> "Working…";
            };
        }
        int percentForBar = item.percent >= 0 ? item.percent : (running || paused ? -1 : 100);
        DownloadProgressBar bar = new DownloadProgressBar(percentForBar, stateColor, barLabel);
        bar.setPreferredSize(new Dimension(10, 22));
        card.add(bar, BorderLayout.CENTER);

        JLabel statusLbl = new JLabel(item.status);
        statusLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        statusLbl.setForeground(new Color(text.getRed(), text.getGreen(), text.getBlue(), 175));
        card.add(statusLbl, BorderLayout.SOUTH);

        return card;
    }

    /**
     * Custom-painted, bigger, rounded progress bar for download cards — an
     * indeterminate
     * (percent &lt; 0) or determinate fill with a centered label drawn on top,
     * since the
     * default Swing progress bar looks thin/flat next to the rest of the app's
     * rounded UI.
     */
    private static final class DownloadProgressBar extends JComponent {
        private final int percent; // -1 = indeterminate
        private final Color color;
        private final String label;
        private float indeterminatePhase = 0f;

        DownloadProgressBar(int percent, Color color, String label) {
            this.percent = percent;
            this.color = color;
            this.label = label;
            setOpaque(false);
            if (percent < 0) {
                javax.swing.Timer t = new javax.swing.Timer(30, e -> {
                    indeterminatePhase += 0.02f;
                    if (indeterminatePhase > 1f)
                        indeterminatePhase = 0f;
                    repaint();
                });
                t.start();
                addHierarchyListener(e -> {
                    if (!isDisplayable())
                        t.stop();
                });
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();

            g2.setColor(new Color(255, 255, 255, 26));
            g2.fillRoundRect(0, 0, w, h, h, h);

            if (percent >= 0) {
                int fillW = Math.max(h, (int) (w * (percent / 100.0)));
                g2.setColor(color);
                g2.fillRoundRect(0, 0, fillW, h, h, h);
            } else {
                int segW = Math.max(30, w / 4);
                int x = (int) (indeterminatePhase * (w + segW)) - segW;
                g2.setColor(color);
                g2.fillRoundRect(Math.max(0, x), 0, segW, h, h, h);
            }

            g2.setColor(Color.WHITE);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            FontMetrics fm = g2.getFontMetrics();
            int tx = (w - fm.stringWidth(label)) / 2;
            int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(label, tx, ty);

            g2.dispose();
        }
    }

    /**
     * Pause/Resume/Cancel control button for a download card — bigger and clearer
     * than a
     * tiny icon-only button, with an optional red "danger" style for Cancel.
     */
    private JButton downloadControlButton(String label, String tooltip, boolean danger) {
        JButton btn = new JButton(label);
        btn.setToolTipText(tooltip);
        if (danger) {
            stylePillButton(btn, new Color(239, 68, 68, 45), Color.WHITE, new Color(239, 68, 68, 160));
        } else {
            stylePillButton(btn, new Color(255, 255, 255, 18), Color.WHITE, new Color(255, 255, 255, 45));
        }
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CONSOLE LOG AREA
    // ══════════════════════════════════════════════════════════════════════════
    private JPanel buildLogArea() {
        com.launcher.model.LauncherSettings settings = com.launcher.manager.SettingsManager.getInstance().getSettings();
        Color logBg = hexToColor(settings.logBgColor, new Color(6, 6, 8));
        Color text = hexToColor(settings.textColor, new Color(226, 226, 234));
        Color accent = hexToColor(settings.accentColor, new Color(16, 185, 129));

        JPanel outer = new JPanel(new BorderLayout());
        outer.setBorder(new EmptyBorder(0, 16, 16, 16));

        // Same "island card" language as the rest of the app — soft translucent fill,
        // faint border, rounded corners — instead of a flat plain JPanel.
        logCard = new RoundedPanel(16, new Color(255, 255, 255, 10), new Color(255, 255, 255, 18));
        logCard.putClientProperty("keepCustomBg", Boolean.TRUE);
        logCard.setLayout(new BorderLayout(0, 8));
        logCard.setBorder(new EmptyBorder(12, 16, 14, 16));

        // ── Header row: terminal glyph + "Console" title on the left, live status +
        // action buttons on the right — mirrors the header/detail-card pattern used
        // elsewhere (e.g. the instance header card).
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        JPanel titleGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        titleGroup.setOpaque(false);
        JLabel glyph = new JLabel(">_");
        glyph.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        glyph.setForeground(accent);
        JLabel titleLbl = new JLabel("Console");
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        titleLbl.setForeground(text);
        titleGroup.add(glyph);
        titleGroup.add(titleLbl);
        header.add(titleGroup, BorderLayout.WEST);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        controls.setOpaque(false);

        clearLogBtn = new JButton("Clear");
        stylePillButton(clearLogBtn, new Color(255, 255, 255, 18), text, new Color(255, 255, 255, 30));
        clearLogBtn.addActionListener(e -> logArea.setText(""));

        controls.add(clearLogBtn);
        header.add(controls, BorderLayout.EAST);

        // Thin divider under the header, same treatment as the top bar's bottom edge.
        JPanel divider = new JPanel();
        divider.setOpaque(true);
        divider.setBackground(new Color(255, 255, 255, 18));
        divider.setPreferredSize(new Dimension(10, 1));
        JPanel dividerWrap = new JPanel(new BorderLayout());
        dividerWrap.setOpaque(false);
        dividerWrap.setBorder(new EmptyBorder(0, 0, 8, 0));
        dividerWrap.add(divider, BorderLayout.CENTER);

        JPanel northStack = new JPanel(new BorderLayout());
        northStack.setOpaque(false);
        northStack.add(header, BorderLayout.NORTH);
        northStack.add(dividerWrap, BorderLayout.SOUTH);
        logCard.add(northStack, BorderLayout.NORTH);

        // Console area — dark monospace surface nested inside the lighter card, its own
        // subtle rounded border so the log text reads as a distinct "screen" region. A
        // JTextPane (rather than a plain JTextArea) is used so log lines can be
        // colorized
        // per-token — bracketed tags dimmed, error/warn/debug lines tinted — for a more
        // modern, at-a-glance-readable console feel.
        logArea = new JTextPane();
        logArea.setEditable(false);
        logArea.setBackground(logBg);
        logArea.setForeground(text);
        logArea.setCaretColor(text);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logArea.setBorder(new EmptyBorder(8, 10, 8, 10));
        buildLogStyles(text, accent);

        JScrollPane scroll = new JScrollPane(logArea);
        com.launcher.ui.SmoothScroll.install(scroll);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 18), 1, true));
        scroll.getViewport().setBackground(logBg);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(true);
        // JTextPane has no setRows() like JTextArea did, so pin the same ~6-line height
        // here.
        FontMetrics fm = logArea.getFontMetrics(logArea.getFont());
        scroll.setPreferredSize(new Dimension(10, fm.getHeight() * 6 + 16));

        logCard.add(scroll, BorderLayout.CENTER);
        outer.add(logCard, BorderLayout.CENTER);
        return outer;
    }

    /**
     * Applies the app's standard rounded "pill" button look (see e.g. account +/−
     * buttons)
     * with a caller-supplied tint, instead of each call site repeating the same
     * five lines.
     */
    private void stylePillButton(JButton btn, Color background, Color foreground, Color borderColor) {
        btn.setFont(new Font("SansSerif", Font.BOLD, 12));
        btn.setFocusPainted(false);
        btn.setForeground(foreground);
        btn.setBackground(background);
        btn.setMargin(new Insets(6, 16, 6, 16));
        btn.putClientProperty("JButton.borderColor", borderColor);
        btn.putClientProperty("JButton.arc", ROUNDED_BUTTON_ARC);
    }

    private void refreshAccounts() {
        if (accountBox == null)
            return;

        // Remove ActionListeners to prevent triggering setActiveAccount while
        // populating
        java.awt.event.ActionListener[] listeners = accountBox.getActionListeners();
        for (java.awt.event.ActionListener l : listeners) {
            accountBox.removeActionListener(l);
        }

        accountBox.removeAllItems();
        for (Account a : accountManager.getAccounts()) {
            accountBox.addItem(a);
        }

        accountManager.getActiveAccount().ifPresent(accountBox::setSelectedItem);

        // Re-add listeners
        for (java.awt.event.ActionListener l : listeners) {
            accountBox.addActionListener(l);
        }

        if (accountBtn != null) {
            accountBtn.setText(accountButtonLabel());
        }
        if (accountDropdown != null && accountDropdown.isVisible()) {
            // Rebuild in place so an open dropdown reflects the change immediately.
            layeredPane.remove(accountDropdown);
            accountDropdown = null;
            buildAccountDropdown();
            showAccountDropdown();
        }
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
        if (dawnStatusLabel == null)
            return;
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
            installDawnButton.setEnabled(false); // Disabled
            deleteDawnButton.setEnabled(false);
        }
    }

    private void installDawn(Instance inst) {
        installDawnButton.setEnabled(false);
        setStatus("Downloading Dawn standalone client…");
        String downloadId = com.launcher.manager.DownloadManager.getInstance().start("Dawn Client");
        new Thread(() -> {
            com.launcher.manager.DownloadManager.getInstance().bindThread(downloadId);
            try {
                Path mods = instanceManager.resolveGameDir(inst).resolve("mods");
                Files.createDirectories(mods);
                Path jar = mods.resolve(DAWN_JAR_NAME);
                com.launcher.util.HttpUtil.downloadFile(DAWN_DOWNLOAD_URL, jar, msg -> {
                    com.launcher.manager.DownloadManager.getInstance().update(downloadId, msg);
                    SwingUtilities.invokeLater(() -> setStatus(msg));
                });
                com.launcher.manager.DownloadManager.getInstance().finish(downloadId, "Installed");
                SwingUtilities.invokeLater(() -> {
                    notifications.success("Dawn client installed", "Standalone JAR placed in mods.");
                    updateDawnStatus(inst);
                });
            } catch (Exception ex) {
                com.launcher.manager.DownloadManager.getInstance().fail(downloadId, ex.getMessage());
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
    private void launchGame(Instance instance, Account account, boolean installOnly) {
        setStatus("Preparing files…");
        log("----------------------------------------------------------------");
        log("Launching " + instance.name + " (" + instance.mcVersion + ") as " + account.username);
        log("----------------------------------------------------------------");

        String downloadId = com.launcher.manager.DownloadManager.getInstance().start("Installing " + instance.name);
        new Thread(() -> {
            com.launcher.manager.DownloadManager.getInstance().bindThread(downloadId);
            try {
                Path gameDir = instanceManager.resolveGameDir(instance);
                Path nativesDir = instanceManager.resolveNativesDir(instance);
                Files.createDirectories(gameDir);
                Files.createDirectories(nativesDir);

                GameInstaller installer = new GameInstaller();
                VersionManifestService manifestService = new VersionManifestService();
                JsonObject versionJson = null;

                try {
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
                            if (url == null)
                                throw new RuntimeException("Unknown version: " + instance.mcVersion);
                            versionJson = manifestService.fetchVersionJson(url);
                        }

                    } else if (instance.modLoader == ModLoaderType.FABRIC) {
                        log("Fetching Fabric profile " + instance.modLoaderVersion + "…");
                        SwingUtilities.invokeLater(() -> setStatus("Fetching Fabric profile…"));
                        versionJson = new FabricInstaller().fetchProfileJson(instance.mcVersion,
                                instance.modLoaderVersion);

                    } else if (instance.modLoader == ModLoaderType.QUILT) {
                        log("Fetching Quilt profile " + instance.modLoaderVersion + "…");
                        SwingUtilities.invokeLater(() -> setStatus("Fetching Quilt profile…"));
                        versionJson = new QuiltInstaller().fetchProfileJson(instance.mcVersion,
                                instance.modLoaderVersion);

                    } else if (instance.modLoader == ModLoaderType.NEOFORGE) {
                        log("Installing NeoForge " + instance.modLoaderVersion + "…");
                        SwingUtilities.invokeLater(() -> setStatus("Installing NeoForge…"));
                        NeoForgeInstaller nfi = new NeoForgeInstaller();
                        String vid = nfi.installClient(instance.mcVersion, instance.modLoaderVersion, gameDir,
                                this::log);
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

                    if (instance.modLoader == ModLoaderType.VANILLA || instance.modLoader == ModLoaderType.FABRIC
                            || instance.modLoader == ModLoaderType.QUILT) {
                        String vid = instance.mcVersion;
                        if (instance.modLoader == ModLoaderType.FABRIC)
                            vid = "fabric-loader-" + instance.modLoaderVersion + "-" + instance.mcVersion;
                        else if (instance.modLoader == ModLoaderType.QUILT)
                            vid = "quilt-loader-" + instance.modLoaderVersion + "-" + instance.mcVersion;

                        if (versionJson.has("id")) {
                            vid = versionJson.get("id").getAsString();
                        } else {
                            versionJson.addProperty("id", vid);
                        }

                        try {
                            Path defaultMcDir = com.launcher.manager.LauncherPaths.getDefaultMinecraftPath()
                                    .toAbsolutePath().normalize();
                            Path gameDirAbs = gameDir.toAbsolutePath().normalize();
                            Path savePath;

                            if (!gameDirAbs.equals(defaultMcDir)) {
                                String dirName = gameDir.getFileName().toString();
                                savePath = gameDir.resolve(dirName + ".json");
                                if (versionJson.has("id")) {
                                    versionJson.addProperty("id", dirName);
                                }
                            } else {
                                savePath = gameDir.resolve("versions").resolve(vid).resolve(vid + ".json");
                            }

                            Files.createDirectories(savePath.getParent());
                            Files.writeString(savePath, com.launcher.util.JsonUtil.GSON.toJson(versionJson),
                                    java.nio.charset.StandardCharsets.UTF_8);
                        } catch (Exception e) {
                            log("Failed to save version JSON locally: " + e.getMessage());
                        }
                    }
                } catch (Exception versionEx) {
                    log("Failed to load version data: " + versionEx.getMessage());
                    if (com.launcher.manager.SettingsManager.getInstance()
                            .getSettings().autoRefreshModsOnVersionLoadFail) {
                        autoRefreshModsAndResourcePacksAfterVersionLoadFailure(instance);
                    }
                    throw versionEx;
                }

                SwingUtilities.invokeLater(() -> setStatus("Resolving dependencies…"));
                com.launcher.manager.DownloadManager.getInstance().update(downloadId, "Resolving dependencies…");
                JsonObject merged = installer.resolveInheritance(versionJson, this::log);
                log("Downloading/verifying files…");
                com.launcher.manager.DownloadManager.getInstance().update(downloadId, "Downloading/verifying files…");
                SwingUtilities.invokeLater(() -> setStatus("Installing files…"));
                ResolvedVersion resolved = installer.installAndResolve(merged, nativesDir, this::log);

                instance.installed = true;
                instanceManager.save();
                SwingUtilities.invokeLater(() -> instanceList.repaint());
                com.launcher.manager.DownloadManager.getInstance().finish(downloadId, "Installed");

                if (installOnly) {
                    SwingUtilities.invokeLater(() -> {
                        setStatus("Ready");
                        playButton.setEnabled(true);
                    });
                    return;
                }

                log("Launching Minecraft in separate window…");
                log("Instance: " + instance.name + " | MC " + instance.mcVersion + " | Loader: " + instance.modLoader
                        + (instance.modLoaderVersion != null ? " " + instance.modLoaderVersion : "")
                        + " | RAM: " + instance.ramMb + " MB"
                        + " | Account: " + account.username);
                SwingUtilities.invokeLater(() -> setStatus("Running " + instance.name));

                GameLauncher launcher = new GameLauncher();
                Process process = launcher.launch(instance, gameDir, nativesDir, resolved, account, this::log);
                String runId = UUID.randomUUID().toString();
                RunningInstance ri = new RunningInstance(runId, instance.name, instance, process);
                runningInstances.add(ri);

                SwingUtilities.invokeLater(() -> {
                    // Turn play button yellow and update text to show count
                    playButton.setBackground(new Color(245, 158, 11)); // Yellow/Amber
                    playButton.setText("PLAY ▸ (" + runningInstances.size() + ")");
                    playButton.setEnabled(true); // Always keep enabled so we can launch more
                });

                SwingUtilities.invokeLater(() -> {
                    killInstanceButton.setEnabled(true);
                    com.launcher.model.LauncherSettings cs = com.launcher.manager.SettingsManager.getInstance()
                            .getSettings();
                    if (cs.minimizeOnLaunch) {
                        hideToTray();
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
                            com.launcher.manager.DiscordRpcManager.getInstance().updatePlayingServer(instance,
                                    resolved.id, serverIp);
                        }
                    }
                }
                int exit = process.waitFor();
                log("Minecraft exited with code " + exit);
                SwingUtilities.invokeLater(
                        () -> setStatus(exit == 0 ? "Game closed normally." : "Game exited (code " + exit + ")"));
                com.launcher.manager.DiscordRpcManager.getInstance().updateIdle();

            } catch (Exception ex) {
                log("ERROR: " + ex.getMessage());
                com.launcher.manager.DownloadManager.DownloadItem dlItem = com.launcher.manager.DownloadManager
                        .getInstance()
                        .snapshotNewestFirst().stream().filter(d -> d.id.equals(downloadId)).findFirst().orElse(null);
                if (dlItem != null && dlItem.state == com.launcher.manager.DownloadManager.State.RUNNING) {
                    com.launcher.manager.DownloadManager.getInstance().fail(downloadId, ex.getMessage());
                }
                SwingUtilities.invokeLater(() -> {
                    setStatus("Launch failed — see console.");
                    // If it looks like a network issue, show the offline play button
                    if (ex instanceof java.io.IOException || ex.getMessage().toLowerCase().contains("failed")
                            || ex.getMessage().toLowerCase().contains("timeout")) {
                        offlinePlayButton.setVisible(true);
                    }
                });
            } finally {
                // Find and remove the process from tracking (could be null if we crashed before
                // launching)
                runningInstances.removeIf(r -> r.instance == instance && (r.process == null || !r.process.isAlive()));
                SwingUtilities.invokeLater(() -> {
                    if (runningInstances.isEmpty()) {
                        playButton.setBackground(new Color(16, 185, 129)); // Original Green
                        playButton.setText("PLAY");
                        killInstanceButton.setEnabled(false);
                    } else {
                        playButton.setText("PLAY ▸ (" + runningInstances.size() + ")");
                    }
                    playButton.setEnabled(true);
                    if (com.launcher.manager.SettingsManager.getInstance().getSettings().restoreLauncherOnGameClose) {
                        restoreFromTray();
                    }
                });
                System.gc();
            }
        }, "launch-thread").start();
    }

    private void launchGameOffline(Instance instance, Account account) {
        setStatus("Preparing offline launch…");
        log("----------------------------------------------------------------");
        log("Launching " + instance.name + " (" + instance.mcVersion + ") OFFLINE as " + account.username);
        log("----------------------------------------------------------------");

        new Thread(() -> {
            try {
                Path gameDir = instanceManager.resolveGameDir(instance);
                Path nativesDir = instanceManager.resolveNativesDir(instance);

                GameInstaller installer = new GameInstaller();
                JsonObject versionJson = null;

                // For offline play, we MUST find a local JSON
                String targetId = instance.mcVersion;
                if (instance.modLoader != ModLoaderType.VANILLA) {
                    // Try to guess the fabric/forge directory name
                    if (instance.modLoader == ModLoaderType.FABRIC)
                        targetId = "fabric-loader-" + instance.modLoaderVersion + "-" + instance.mcVersion;
                    else if (instance.modLoader == ModLoaderType.FORGE)
                        targetId = instance.mcVersion + "-forge-" + instance.modLoaderVersion;
                    else if (instance.modLoader == ModLoaderType.NEOFORGE)
                        targetId = "neoforge-" + instance.modLoaderVersion;
                    else if (instance.modLoader == ModLoaderType.QUILT)
                        targetId = "quilt-loader-" + instance.modLoaderVersion + "-" + instance.mcVersion;
                }

                Path localJson = LauncherPaths.findLocalVersionJson(targetId, gameDir);
                if (localJson == null && instance.modLoader != ModLoaderType.VANILLA) {
                    // Fallback to vanilla
                    localJson = LauncherPaths.findLocalVersionJson(instance.mcVersion, gameDir);
                }

                if (localJson != null) {
                    log("Loading local version JSON for offline launch: " + localJson);
                    versionJson = JsonUtil.parse(Files.readString(localJson)).getAsJsonObject();
                }

                if (versionJson == null) {
                    throw new RuntimeException(
                            "No cached version data found. You must launch this instance online at least once.");
                }

                SwingUtilities.invokeLater(() -> setStatus("Resolving local files…"));
                JsonObject merged = installer.resolveInheritance(versionJson, this::log); // Might fail if parent isn't
                                                                                          // cached
                ResolvedVersion resolved = installer.installAndResolve(merged, nativesDir, this::log); // Won't download
                                                                                                       // if exists

                log("Launching Minecraft OFFLINE…");
                SwingUtilities.invokeLater(() -> setStatus("Running " + instance.name + " (Offline)"));

                GameLauncher launcher = new GameLauncher();
                Process process = launcher.launch(instance, gameDir, nativesDir, resolved, account, this::log);
                String runId = UUID.randomUUID().toString();
                RunningInstance ri = new RunningInstance(runId, instance.name, instance, process);
                runningInstances.add(ri);

                SwingUtilities.invokeLater(() -> {
                    playButton.setBackground(new Color(245, 158, 11)); // Yellow/Amber
                    playButton.setText("PLAY ▸ (" + runningInstances.size() + ")");
                    playButton.setEnabled(true);
                    offlinePlayButton.setVisible(false); // Hide it once successful
                });

                SwingUtilities.invokeLater(() -> {
                    killInstanceButton.setEnabled(true);
                    com.launcher.model.LauncherSettings cs = com.launcher.manager.SettingsManager.getInstance()
                            .getSettings();
                    if (cs.minimizeOnLaunch)
                        hideToTray();
                    if (cs.closeAfterLaunch)
                        System.exit(0);
                });
                System.gc();

                int exit = process.waitFor();
                log("Minecraft exited with code " + exit);
                SwingUtilities.invokeLater(
                        () -> setStatus(exit == 0 ? "Game closed normally." : "Game exited (code " + exit + ")"));

            } catch (Exception ex) {
                log("OFFLINE ERROR: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    setStatus("Offline launch failed.");
                    notifications.error("Offline Launch Failed", ex.getMessage());
                });
            } finally {
                // Find and remove the process from tracking (could be null if we crashed before
                // launching)
                runningInstances.removeIf(r -> r.instance == instance && !r.process.isAlive());
                SwingUtilities.invokeLater(() -> {
                    if (runningInstances.isEmpty()) {
                        playButton.setBackground(new Color(16, 185, 129));
                        playButton.setText("PLAY");
                        killInstanceButton.setEnabled(false);
                    } else {
                        playButton.setText("PLAY ▸ (" + runningInstances.size() + ")");
                    }
                    offlinePlayButton.setEnabled(true);
                    playButton.setEnabled(true);
                    if (com.launcher.manager.SettingsManager.getInstance().getSettings().restoreLauncherOnGameClose) {
                        restoreFromTray();
                    }
                });
                System.gc();
            }
        }, "launch-thread-offline").start();
    }

    private static double transparencyAlpha(com.launcher.model.LauncherSettings settings) {
        int s = settings.transparencyStrength <= 0 ? 20 : settings.transparencyStrength;
        s = Math.min(100, Math.max(1, s));
        // Higher "strength" = more see-through, so it maps to a *lower* alpha (more of
        // the
        // background shows through). Clamped so panels never become fully invisible.
        return Math.max(0.20, 1.0 - (s / 100.0));
    }

    private void applyTheme() {
        com.launcher.model.LauncherSettings settings = com.launcher.manager.SettingsManager.getInstance().getSettings();
        Color bg = hexToColor(settings.bgColor, new Color(10, 10, 15));
        Color panelBg = hexToColor(settings.panelBgColor, new Color(19, 19, 26));
        Color text = hexToColor(settings.textColor, new Color(226, 226, 234));
        Color logBg = hexToColor(settings.logBgColor, new Color(6, 6, 8));
        Color accent = hexToColor(settings.accentColor, new Color(16, 185, 129));

        // Always keep the layered pane's backdrop (gradient, or the user's background
        // image)
        // up to date — it's what shows through gaps/margins, and behind panels when
        // Transparency is on. Blur and the background image are independent of the
        // transparency toggle below.
        if (logoSub != null) {
            logoSub.setForeground(accent);
        }

        // Apply the configured font family across every component, preserving each
        // component's existing size/style (bold, italic, size) and only swapping the
        // family.
        applyFontFamilyRecursively(getContentPane(), settings.fontFamily);

        if (layeredPane != null) {
            layeredPane.setPalette(bg, accent, settings.backgroundStyle);
            boolean useImage = settings.useBackgroundImage && settings.backgroundImagePath != null
                    && !settings.backgroundImagePath.isBlank();
            layeredPane.setBackgroundImage(useImage ? settings.backgroundImagePath : null);
            layeredPane.setBlur(settings.enableBlurEffect, settings.blurStrength);
        }

        // Transparency: blend the content pane (and, recursively, plain panels within
        // it)
        // with whatever is painted behind them, instead of a flat opaque color. (An
        // earlier
        // version tried to scope this to individual Settings "island" cards by cropping
        // a
        // snapshot per-panel — that turned out visually glitchy, so this reverts to the
        // simpler, more stable whole-window blend.)
        if (settings.enableTransparency) {
            double alpha = transparencyAlpha(settings);
            // Tint the translucent backdrop with the accent color instead of a flat
            // neutral background, so transparency actually reflects the chosen theme.
            int mixR = (int) (bg.getRed() * 0.78 + accent.getRed() * 0.22);
            int mixG = (int) (bg.getGreen() * 0.78 + accent.getGreen() * 0.22);
            int mixB = (int) (bg.getBlue() * 0.78 + accent.getBlue() * 0.22);
            Color tintedBg = new Color(
                    Math.max(0, Math.min(255, mixR)),
                    Math.max(0, Math.min(255, mixG)),
                    Math.max(0, Math.min(255, mixB)));
            Color translucentBg = new Color(tintedBg.getRed(), tintedBg.getGreen(), tintedBg.getBlue(),
                    (int) (alpha * 255));
            getContentPane().setBackground(translucentBg);
        } else {
            getContentPane().setBackground(bg);
        }

        // Ensure child components are non-opaque if transparent is on, so background
        // shows through
        setComponentTranslucent(getContentPane(), settings.enableTransparency);

        // Apply the configured panel background consistently to every plain panel
        // across the
        // whole app (previously this only affected the Settings tab's "cards", so the
        // setting
        // looked like it did nothing everywhere else).
        applyPanelBackgroundRecursively(cardPanel, panelBg);

        // Re-style the custom title bar, if in use, to match the theme.
        if (customTitleBar != null) {
            customTitleBar.applyColors(panelBg, text);
        }

        // Re-style the console log area with the configured colors.
        if (logArea != null) {
            logArea.setBackground(logBg);
            logArea.setForeground(text);
            logArea.setCaretColor(text);
            buildLogStyles(text, accent);
        }
        if (logCard != null) {
            logCard.setColors(new Color(255, 255, 255, 10), new Color(255, 255, 255, 18));
        }
        if (clearLogBtn != null) {
            clearLogBtn.setForeground(text);
        }

        // Re-style settings/behavior "cards" and their accent-colored titles.
        restyleCards(cardPanel, panelBg, accent); // Apply to cardPanel, which is inside layeredPane

        // Give the primary action buttons the configured accent color.
        if (playButton != null)
            playButton.setBackground(accent);
        if (playButton != null)
            playButton.setForeground(Color.WHITE);

        // The account (player name) combo box now lives inside a frosted-glass wrapper
        // (see
        // wrapInFrostedGlass), so it no longer needs a forced flat background/border
        // here —
        // just keep its text color in sync with the rest of the theme.
        if (accountBox != null) {
            accountBox.setForeground(text);
        }

        // The Discover tab used to keep a totally separate, hardcoded palette and never
        // reacted to these settings at all — now it's re-derived from the same colors.
        applyDiscoverPalette();

        // The mod-list card renderer reads settings live, so just force a repaint.
        if (modsList != null)
            modsList.repaint();

        // Plain JButtons across Instances/Mods (and everywhere else outside Discover,
        // which
        // styles its own buttons above) used to fall back to FlatLaf's flat gray button
        // look
        // regardless of the configured accent color. Re-tint them to match now.
        restyleActionButtons(cardPanel, accent);

        // The tab underline/hover glow was hardcoded to a fixed green regardless of the
        // configured accent color; keep it in sync too.
        UIManager.put("TabbedPane.underlineColor", accent);
        UIManager.put("TabbedPane.focusColor", new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 60));
        UIManager.put("TabbedPane.hoverColor", new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 35));
        UIManager.put("TabbedPane.underlineHeight", 3);
        if (mainTabPane != null) {
            mainTabPane.refreshAccentStyles();
        }

        refreshDownloadsToggleVisibility();

        repaint();
    }

    /**
     * Ordinary JButtons (Instances/Mods toolbars, dialogs, etc.) default to
     * FlatLaf's flat
     * gray look. Re-tint them with a translucent accent-colored fill so the whole
     * app feels
     * consistent with the Discover tab and the configured Accent Color, instead of
     * only the
     * Play button being colored. Skips Discover's own buttons/toggles (already
     * self-styled)
     * and toggle buttons in general (segmented controls manage their own
     * selected/unselected
     * colors already).
     */
    private void restyleActionButtons(Component comp, Color accent) {
        if (comp == null)
            return;
        if (comp instanceof JComponent jc && Boolean.TRUE.equals(jc.getClientProperty("keepCustomBg"))) {
            return; // Discover subtree — manages its own button styling.
        }
        if (comp instanceof JButton btn && !(comp instanceof JToggleButton) && btn != playButton) {
            Color tinted = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 40);
            btn.setBackground(tinted);
            btn.setForeground(Color.WHITE);
            btn.setFocusPainted(false);
            btn.putClientProperty("JButton.borderColor", accent);
        }
        if (comp instanceof Container container) {
            for (Component child : container.getComponents()) {
                restyleActionButtons(child, accent);
            }
        }
    }

    /**
     * Recursively swaps every component's font family to {@code family}, keeping
     * each
     * component's existing style (plain/bold/italic) and size intact. An
     * unknown/unavailable
     * family just falls back to a default logical font per normal AWT behavior, so
     * this is
     * always safe to call even with a name Java doesn't recognize.
     */
    private void applyFontFamilyRecursively(Component comp, String family) {
        if (comp == null || family == null || family.isBlank())
            return;
        Font f = comp.getFont();
        if (f != null && !family.equals(f.getFamily())) {
            comp.setFont(new Font(family, f.getStyle(), f.getSize()));
        }
        if (comp instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyFontFamilyRecursively(child, family);
            }
        }
    }

    /**
     * Recursively applies the configured "Panel Background" color to plain, opaque
     * JPanels
     * throughout the whole UI. Custom-painted panels (Discover's
     * RoundedPanel/skeleton cards,
     * anything explicitly marked to keep its own palette) and non-opaque panels are
     * left alone,
     * since overwriting those would break their intentional look.
     */
    private void applyPanelBackgroundRecursively(Component comp, Color panelBg) {
        if (comp instanceof JComponent jc && Boolean.TRUE.equals(jc.getClientProperty("keepCustomBg"))) {
            return; // This whole subtree manages its own palette — leave it alone entirely.
        }
        if (comp instanceof JPanel panel
                && panel.isOpaque()
                && !(panel instanceof RoundedPanel)
                && panel.getClientProperty("settingsCardTitle") == null) {
            panel.setBackground(panelBg);
        } else if (comp instanceof JScrollPane scroll) {
            scroll.getViewport().setBackground(panelBg);
        }
        if (comp instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyPanelBackgroundRecursively(child, panelBg);
            }
        }
    }

    /**
     * Recursively re-applies the panel background and titled-border accent color to
     * all "cards" built by createCard().
     */
    private void restyleCards(Component comp, Color panelBg, Color accent) {
        if (comp instanceof JPanel panel && panel.getClientProperty("settingsCardTitle") != null) {
            panel.setBackground(panelBg);
            String title = (String) panel.getClientProperty("settingsCardTitle");
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder(
                            BorderFactory.createLineBorder(new Color(60, 60, 70), 1, true),
                            title,
                            TitledBorder.LEFT,
                            TitledBorder.TOP,
                            new Font("SansSerif", Font.BOLD, 13),
                            accent),
                    BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        }
        if (comp instanceof Container container) {
            for (Component child : container.getComponents()) {
                restyleCards(child, panelBg, accent);
            }
        }
    }

    private void setComponentTranslucent(Component comp, boolean transparent) {
        if (comp instanceof JPanel panel) {
            // Let text fields, lists, buttons keep their opacity for readability
            if (panel.getClass() == JPanel.class || panel.getLayout() instanceof BoxLayout
                    || panel.getLayout() instanceof GridBagLayout || panel.getLayout() instanceof BorderLayout) {
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

    /**
     * Switching between the custom title bar and the OS one requires an
     * undecorated-state change, which Swing only allows on a non-displayable frame
     * — so this offers to restart the window immediately.
     */
    private void promptRestartForTitleBarChange() {
        int choice = JOptionPane.showConfirmDialog(this,
                "Changing the title bar style requires restarting the launcher window.\nRestart now?",
                "Restart Required", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            restartLauncherWindow();
        } else {
            notifications.info("Restart later",
                    "The new title bar style will apply the next time you restart Zero Launcher.");
        }
    }

    /**
     * Recreates the main window in place, e.g. after toggling the custom-title-bar
     * setting. Any running game keeps running, since it's a separate process.
     */
    private void restartLauncherWindow() {
        Point loc = getLocation();
        Dimension size = getSize();
        int extendedState = getExtendedState();
        dispose();
        SwingUtilities.invokeLater(() -> {
            Main next = new Main();
            if ((extendedState & JFrame.MAXIMIZED_BOTH) == 0) {
                next.setLocation(loc);
                next.setSize(size);
                next.normalBounds = next.getBounds();
            }
            next.setVisible(true);
        });
    }

    /**
     * Records the current bounds as the "normal" (restored) bounds, but only while
     * the window
     * is actually in plain NORMAL state — never while maximized or minimized, so we
     * don't
     * accidentally remember a maximized or iconified size as the restore target.
     */
    private void captureNormalBoundsIfApplicable() {
        if (isNormalState(getExtendedState()) && isShowing()) {
            normalBounds = getBounds();
        }
    }

    /**
     * True if the given extended-state value is "plain normal" — i.e. neither
     * maximized nor
     * iconified. Checked via bitmask rather than strict equality to Frame.NORMAL
     * (0), since some
     * platforms/window managers set extra bits we don't care about.
     */
    private static boolean isNormalState(int state) {
        return (state & (Frame.MAXIMIZED_BOTH | Frame.ICONIFIED)) == 0;
    }

    /** Call when the user starts dragging a resize handle or the title bar. */
    public void beginWindowAdjust() {
        userAdjustingWindow = true;
    }

    /**
     * Call when the user finishes dragging a resize handle or the title bar;
     * captures the
     * resulting bounds as the new known-good "normal" bounds.
     */
    public void endWindowAdjust() {
        userAdjustingWindow = false;
        captureNormalBoundsIfApplicable();
    }

    /**
     * Called (from a background thread) when a game launch fails while loading
     * version data.
     * Re-scans the instance's mods and resource packs in case stale or corrupt
     * files were the
     * cause, and lets the user know what happened.
     */
    private void autoRefreshModsAndResourcePacksAfterVersionLoadFailure(Instance instance) {
        log("Auto-refreshing mods and resource packs for \"" + instance.name + "\" after a version load failure…");
        try {
            ModUpdateService service = new ModUpdateService();
            Path gameDir = instanceManager.resolveGameDir(instance);

            Path modsDir = gameDir.resolve("mods");
            List<ModEntry> mods = service.scanModsDir(modsDir);
            log("Re-scanned mods folder: " + mods.size() + " mod jar(s) found.");
            if (instanceList.getSelectedValue() == instance) {
                SwingUtilities.invokeLater(() -> {
                    currentModEntries = mods;
                    filterMods();
                });
            }

            Path resourcePacksDir = gameDir.resolve("resourcepacks");
            int packCount = 0;
            if (Files.isDirectory(resourcePacksDir)) {
                try (var stream = Files.list(resourcePacksDir)) {
                    packCount = (int) stream.filter(p -> !Files.isDirectory(p)).count();
                }
            }
            log("Re-scanned resource packs folder: " + packCount + " file(s) found.");

            int finalPackCount = packCount;
            SwingUtilities.invokeLater(() -> notifications.warning("Version load failed — auto-refreshed",
                    "Re-scanned " + mods.size() + " mod(s) and " + finalPackCount + " resource pack(s) for \""
                            + instance.name + "\" in case a stale file was the cause."));
        } catch (Exception ex) {
            log("Auto-refresh after version load failure did not complete: " + ex.getMessage());
        }
    }

    private void setStatus(String msg) {
        SwingUtilities.invokeLater(() -> {
            if (statusLabel != null)
                statusLabel.setText(msg);
        });
    }

    /**
     * Kills the currently running game process(es) using the new popover menu.
     */
    private void killActiveGame() {
        if (runningInstances.isEmpty()) {
            return;
        }
        toggleKillInstancesPopover();
    }

    private RoundedPanel buildKillInstancesPopover() {
        RoundedPanel popover = new RoundedPanel(18, new Color(20, 20, 26, 250), new Color(255, 255, 255, 34));
        popover.putClientProperty("keepCustomBg", Boolean.TRUE);
        popover.setLayout(new BorderLayout());
        popover.setFrostedGlass(layeredPane, 8, new Color(12, 12, 16, 150));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(12, 16, 8, 10));

        JLabel title = new JLabel("Running Instances");
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        title.setHorizontalAlignment(SwingConstants.CENTER);
        header.add(title, BorderLayout.CENTER);

        JButton closeBtn = new JButton("\u2715");
        closeBtn.setToolTipText("Close");
        closeBtn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        closeBtn.setFocusPainted(false);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setOpaque(false);
        closeBtn.setMargin(new Insets(4, 10, 4, 10));
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.addActionListener(e -> animateKillInstancesPopoverHide());
        header.add(closeBtn, BorderLayout.EAST);

        popover.add(header, BorderLayout.NORTH);

        killInstancesListPanel = new JPanel();
        killInstancesListPanel.setOpaque(false);
        killInstancesListPanel.setLayout(new BoxLayout(killInstancesListPanel, BoxLayout.Y_AXIS));
        killInstancesListPanel.setBorder(new EmptyBorder(0, 14, 14, 14));

        JScrollPane scroll = new JScrollPane(killInstancesListPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        com.launcher.ui.SmoothScroll.install(scroll);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        popover.add(scroll, BorderLayout.CENTER);

        return popover;
    }

    private void positionKillInstancesPopover() {
        if (killInstancesPopover == null)
            return;
        int width = 440, height = 520;
        int x = layeredPane.getWidth() - width - 16;
        int y = 60; // Fixed top-right, just below any window controls
        killInstancesPopover.setBounds(x, y, width, Math.min(height, Math.max(160, layeredPane.getHeight() - y - 16)));
    }

    private void toggleKillInstancesPopover() {
        if (killInstancesPopover == null)
            return;
        if (killInstancesPopover.isVisible()) {
            animateKillInstancesPopoverHide();
        } else {
            refreshKillInstancesDialogContent();
            animateKillInstancesPopoverShow();
        }
    }

    private void animateKillInstancesPopoverShow() {
        if (killInstancesPopover == null)
            return;
        positionKillInstancesPopover();
        Rectangle target = killInstancesPopover.getBounds();
        int slide = 14;
        killInstancesPopover.setBounds(target.x, target.y - slide, target.width, target.height);
        killInstancesPopover.setAlpha(0f);
        layeredPane.setLayer(killInstancesPopover, JLayeredPane.MODAL_LAYER);
        killInstancesPopover.setVisible(true);
        long start = System.currentTimeMillis();
        int duration = 190;
        javax.swing.Timer[] holder = new javax.swing.Timer[1];
        holder[0] = new javax.swing.Timer(15, e -> {
            float p = Math.min(1f, (System.currentTimeMillis() - start) / (float) duration);
            float eased = 1 - (1 - p) * (1 - p);
            killInstancesPopover.setAlpha(eased);
            int y = target.y - Math.round(slide * (1 - eased));
            killInstancesPopover.setBounds(target.x, y, target.width, target.height);
            if (p >= 1f) {
                killInstancesPopover.setBounds(target);
                holder[0].stop();
            }
        });
        holder[0].start();
    }

    private void animateKillInstancesPopoverHide() {
        if (killInstancesPopover == null || !killInstancesPopover.isVisible())
            return;
        Rectangle from = killInstancesPopover.getBounds();
        float startAlpha = killInstancesPopover.getAlpha();
        int slide = 10;
        long start = System.currentTimeMillis();
        int duration = 150;
        javax.swing.Timer[] holder = new javax.swing.Timer[1];
        holder[0] = new javax.swing.Timer(15, e -> {
            float p = Math.min(1f, (System.currentTimeMillis() - start) / (float) duration);
            killInstancesPopover.setAlpha(startAlpha * (1 - p));
            killInstancesPopover.setBounds(from.x, from.y - Math.round(slide * p), from.width, from.height);
            if (p >= 1f) {
                holder[0].stop();
                killInstancesPopover.setVisible(false);
                killInstancesPopover.setAlpha(1f);
                killInstancesPopover.setBounds(from);
            }
        });
        holder[0].start();
    }

    private void refreshKillInstancesDialogContent() {
        if (killInstancesListPanel == null)
            return;
        com.launcher.model.LauncherSettings settings = com.launcher.manager.SettingsManager.getInstance().getSettings();
        Color text = hexToColor(settings.textColor, new Color(226, 226, 234));

        killInstancesListPanel.removeAll();

        if (runningInstances.isEmpty()) {
            JLabel empty = new JLabel("No active games.");
            empty.setForeground(text);
            empty.setHorizontalAlignment(SwingConstants.CENTER);
            empty.setAlignmentX(Component.CENTER_ALIGNMENT);
            killInstancesListPanel.add(empty);
        } else {
            int index = 1;
            for (RunningInstance ri : runningInstances) {
                killInstancesListPanel.add(buildKillInstanceCard(ri, text, index++));
                killInstancesListPanel.add(Box.createVerticalStrut(8));
            }
            JButton killAllBtn = downloadControlButton("Kill All Games", "Terminate all active games", true);
            killAllBtn.addActionListener(e -> {
                int count = 0;
                for (RunningInstance ri : runningInstances) {
                    if (ri.process() != null && ri.process().isAlive()) {
                        ri.process().destroyForcibly();
                        count++;
                    }
                }
                notifications.warning("Games terminated", "Killed " + count + " instances.");
                animateKillInstancesPopoverHide();
            });
            JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            footer.setOpaque(false);
            footer.add(killAllBtn);
            killInstancesListPanel.add(Box.createVerticalStrut(8));
            killInstancesListPanel.add(footer);
        }
        killInstancesListPanel.revalidate();
        killInstancesListPanel.repaint();
    }

    private JComponent buildKillInstanceCard(RunningInstance item, Color text, int index) {
        RoundedPanel card = new RoundedPanel(14, new Color(255, 255, 255, 14), new Color(255, 255, 255, 26));
        card.putClientProperty("keepCustomBg", Boolean.TRUE);
        card.setLayout(new BorderLayout(0, 10));
        card.setBorder(new EmptyBorder(14, 16, 14, 16));
        card.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);

        JLabel nameLbl = new JLabel(index + ". " + item.label());
        nameLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        nameLbl.setForeground(text);
        nameLbl.setHorizontalAlignment(SwingConstants.CENTER);

        JButton killBtn = downloadControlButton("Kill", "Terminate this game", true);
        killBtn.addActionListener(e -> {
            if (item.process() != null && item.process().isAlive()) {
                item.process().destroyForcibly();
                notifications.warning("Game terminated", "Killed: " + item.label());
            }
            // After killing, refresh the list. It might take a moment for the process to
            // actually die
            // and be removed from the runningInstances list by the launch thread.
            // For immediate UI feedback, we could also just hide the popover if it was the
            // last one.
            SwingUtilities.invokeLater(() -> {
                refreshKillInstancesDialogContent();
                if (runningInstances.size() <= 1) {
                    animateKillInstancesPopoverHide();
                }
            });
        });

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(killBtn);

        header.add(nameLbl, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);

        card.add(header, BorderLayout.CENTER);
        return card;
    }

    private static final java.util.regex.Pattern LOG_BRACKET_PATTERN = java.util.regex.Pattern
            .compile("\\[[^\\[\\]]{0,80}\\]");

    /**
     * (Re)builds the colorized console's style palette from the current theme
     * colors. Called
     * once when the console is built and again whenever the user changes their
     * text/accent
     * colors, so the log stays readable and on-theme instead of being stuck with
     * flat text.
     */
    private void buildLogStyles(Color text, Color accent) {
        logAttrDefault = new SimpleAttributeSet();
        StyleConstants.setForeground(logAttrDefault, text);

        logAttrBracket = new SimpleAttributeSet();
        StyleConstants.setForeground(logAttrBracket, new Color(148, 163, 184)); // muted slate — timestamps/tags
        StyleConstants.setItalic(logAttrBracket, true);

        logAttrError = new SimpleAttributeSet();
        StyleConstants.setForeground(logAttrError, new Color(248, 113, 113)); // red
        StyleConstants.setBold(logAttrError, true);

        logAttrWarn = new SimpleAttributeSet();
        StyleConstants.setForeground(logAttrWarn, new Color(251, 191, 36)); // amber

        logAttrDebug = new SimpleAttributeSet();
        StyleConstants.setForeground(logAttrDebug, new Color(148, 163, 184)); // muted slate

        logAttrInfo = new SimpleAttributeSet();
        StyleConstants.setForeground(logAttrInfo, accent); // accent-colored INFO lines pop a bit
    }

    /**
     * Picks which color a whole log line should render in based on any level
     * keyword it
     * contains (ERROR/WARN/DEBUG/etc.), falling back to the plain text color.
     */
    private SimpleAttributeSet detectLogLevelAttr(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        if (upper.contains("ERROR") || upper.contains("SEVERE") || upper.contains("FATAL")
                || upper.contains("EXCEPTION")) {
            return logAttrError;
        }
        if (upper.contains("WARN")) {
            return logAttrWarn;
        }
        if (upper.contains("DEBUG") || upper.contains("TRACE")) {
            return logAttrDebug;
        }
        if (upper.contains("INFO")) {
            return logAttrInfo;
        }
        return logAttrDefault;
    }

    /**
     * Appends one log line to the console, colorizing bracketed tags (timestamps,
     * thread
     * names, log-level tags like "[Server thread/INFO]") in a dimmed tone and the
     * rest of the
     * line in a color chosen from any level keyword present — giving the console a
     * modern,
     * syntax-highlighted look instead of flat monochrome text.
     */
    private void appendStyledLogLine(String line) {
        StyledDocument doc = logArea.getStyledDocument();
        SimpleAttributeSet lineAttr = detectLogLevelAttr(line);
        try {
            java.util.regex.Matcher m = LOG_BRACKET_PATTERN.matcher(line);
            int last = 0;
            while (m.find()) {
                if (m.start() > last) {
                    doc.insertString(doc.getLength(), line.substring(last, m.start()), lineAttr);
                }
                doc.insertString(doc.getLength(), m.group(), logAttrBracket);
                last = m.end();
            }
            if (last < line.length()) {
                doc.insertString(doc.getLength(), line.substring(last), lineAttr);
            }
            doc.insertString(doc.getLength(), "\n", lineAttr);
        } catch (BadLocationException ignored) {
            // Shouldn't happen (always inserting at doc.getLength()); if it ever does, the
            // line is simply dropped rather than crashing the UI thread.
        }
    }

    private void log(String msg) {
        String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        logQueue.add("[" + time + "] " + sanitizePrivacy(msg));
        scheduleLogFlush();
    }

    private void scheduleLogFlush() {
        if (logFlushScheduled)
            return;
        if (isMinimized) {
            long now = System.currentTimeMillis();
            if (now - lastLogFlushWhenMinimized < 2000)
                return;
            lastLogFlushWhenMinimized = now;
        }
        logFlushScheduled = true;
        SwingUtilities.invokeLater(() -> {
            logFlushScheduled = false;
            String line;
            int count = 0;
            boolean appended = false;
            while ((line = logQueue.poll()) != null) {
                appendStyledLogLine(line);
                appended = true;
                if (++count > 400)
                    break;
            }
            if (appended) {
                String full = logArea.getText();
                if (full.length() > 30000) {
                    try {
                        logArea.getStyledDocument().remove(0, full.length() - 20000);
                    } catch (BadLocationException ignored) {
                    }
                }
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
            if (!logQueue.isEmpty())
                scheduleLogFlush();
        });
    }

    private String sanitizePrivacy(String message) {
        if (message == null)
            return "";
        com.launcher.model.LauncherSettings s = com.launcher.manager.SettingsManager.getInstance().getSettings();
        if (!s.redactPaths)
            return message;
        String user = System.getProperty("user.name");
        if (user == null || user.isBlank())
            return message;
        // Case-insensitive replace so Windows paths (which don't always match the
        // OS-reported username's exact casing) are redacted too, not just an exact
        // match.
        return message.replaceAll("(?i)" + java.util.regex.Pattern.quote(user), "******");
    }

    public static Color hexToColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank())
            return fallback;
        try {
            return Color.decode(hex);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Marker system property set on the relaunched JVM so we don't relaunch forever
     * if -Xmx can't be honored for some reason.
     */
    private static final String RELAUNCH_FLAG = "zerolauncher.ramLimited";

    public static void main(String[] args) {
        if (relaunchWithConfiguredRamLimit(args)) {
            // A new JVM process has been spawned with the configured -Xmx; this process
            // exits.
            return;
        }
        try {
            UIManager.setLookAndFeel(new FlatDarkLaf());

            // ── Modern, rounded, flat theme tuning ─────────────────────────────
            // Rounded corners everywhere instead of FlatLaf's default square edges.
            UIManager.put("Button.arc", 14);
            UIManager.put("Component.arc", 12);
            UIManager.put("CheckBox.arc", 6);
            UIManager.put("ProgressBar.arc", 14);
            UIManager.put("TextComponent.arc", 10);
            UIManager.put("ScrollBar.width", 10);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
            UIManager.put("ScrollBar.trackArc", 999);
            UIManager.put("ScrollBar.showButtons", false);
            UIManager.put("TabbedPane.showTabSeparators", false);
            UIManager.put("TabbedPane.tabHeight", 40);
            UIManager.put("TabbedPane.selectedBackground", new Color(255, 255, 255, 10));
            UIManager.put("TabbedPane.hoverColor", new Color(255, 255, 255, 14));
            UIManager.put("TabbedPane.underlineColor", new Color(16, 185, 129));
            UIManager.put("TabbedPane.focusColor", new Color(16, 185, 129, 60));
            UIManager.put("TabbedPane.tabInsets", new Insets(8, 16, 8, 16));
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("Button.focusWidth", 1);
            UIManager.put("Component.innerFocusWidth", 0);
            UIManager.put("Table.showHorizontalLines", true);
            UIManager.put("Table.showVerticalLines", false);
            UIManager.put("SplitPane.arc", 12);
            UIManager.put("PopupMenu.borderInsets", new Insets(6, 1, 6, 1));
            UIManager.put("ToolTip.background", new Color(28, 28, 38));
        } catch (Exception ignored) {
        }
        try {
            // Makes sure ImageIO picks up plugins bundled into the shaded jar (e.g. the
            // WebP
            // reader) even if the classloader didn't trigger its usual automatic discovery.
            ImageIO.scanForPlugins();
        } catch (Throwable ignored) {
        }

        try {
            // Register the bundled Minecraft font and any user-added custom fonts before
            // building any UI, so they're available the moment the window/settings open.
            com.launcher.manager.FontManager.init(com.launcher.manager.SettingsManager.getInstance().getSettings());
        } catch (Throwable ignored) {
        }

        if (!com.launcher.util.SingleInstanceGuard.tryAcquire()) {
            SwingUtilities.invokeLater(Main::handleAlreadyRunning);
            return;
        }

        com.launcher.util.SingleInstanceGuard.startFocusServer(Main::bringRunningInstanceToFront);

        SwingUtilities.invokeLater(() -> {
            Main mainFrame = new Main();
            mainFrame.setVisible(true);
        });
    }

    /**
     * Shown when another copy of the launcher already holds the single-instance
     * lock.
     */
    private static void handleAlreadyRunning() {
        Object[] options = { "Open Running Launcher", "Open Anyway", "Exit" };
        int choice = JOptionPane.showOptionDialog(
                null,
                "Zero Launcher is already running.\nWhat would you like to do?",
                "Zero Launcher Already Running",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]);
        switch (choice) {
            case 0 -> { // "Open Running Launcher"
                boolean reached = com.launcher.util.SingleInstanceGuard.requestFocusOnRunningInstance();
                if (!reached) {
                    JOptionPane.showMessageDialog(null,
                            "Couldn't reach the running launcher — it may have just closed.\nStarting a new window instead.",
                            "Zero Launcher", JOptionPane.WARNING_MESSAGE);
                    new Main().setVisible(true);
                    return;
                }
                System.exit(0);
            }
            case 1 -> new Main().setVisible(true); // "Open Anyway"
            default -> System.exit(0); // "Exit" or dialog dismissed
        }
    }

    /**
     * Brings the currently running launcher window to the front; called from the
     * focus-server
     * thread when a second launch attempt asks to be redirected here instead.
     */
    private static void bringRunningInstanceToFront() {
        SwingUtilities.invokeLater(() -> {
            Main m = activeInstance;
            if (m == null)
                return;
            if ((m.getExtendedState() & JFrame.ICONIFIED) != 0) {
                m.setExtendedState(m.getExtendedState() & ~JFrame.ICONIFIED);
            }
            m.setVisible(true);
            m.toFront();
            m.requestFocus();
        });
    }

    /**
     * Reads the "Max RAM for Launcher" setting and, if this JVM wasn't already
     * started with that
     * heap limit, relaunches the launcher as a new process with the appropriate
     * -Xmx flag.
     *
     * @return true if a new process was spawned (caller should return immediately
     *         without starting the UI).
     */
    private static boolean relaunchWithConfiguredRamLimit(String[] args) {
        if (System.getProperty(RELAUNCH_FLAG) != null) {
            return false; // Already relaunched once — avoid any possibility of looping.
        }
        int maxRamMb;
        try {
            com.launcher.model.LauncherSettings settings = com.launcher.manager.SettingsManager.getInstance()
                    .getSettings();
            if (!settings.enableLauncherMaxRam) {
                return false; // Limit explicitly disabled — run with no -Xmx cap.
            }
            maxRamMb = settings.launcherMaxRamMb;
        } catch (Exception e) {
            return false;
        }
        if (maxRamMb <= 0) {
            return false;
        }
        try {
            String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            String classpath = System.getProperty("java.class.path");
            String jarPath = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();

            List<String> command = new ArrayList<>();
            command.add(javaBin);
            command.add("-D" + RELAUNCH_FLAG + "=true");
            command.add("-Xmx" + maxRamMb + "m");
            if (jarPath.endsWith(".jar")) {
                command.add("-jar");
                command.add(jarPath);
            } else {
                command.add("-cp");
                command.add(classpath);
                command.add(Main.class.getName());
            }
            command.addAll(Arrays.asList(args));

            // 🔔 Signal to the bootstrapper that we are about to relaunch.
            System.err.println("ZEROLAUNCHER_RELAUNCHING");

            new ProcessBuilder(command)
                    .inheritIO()
                    .start();
            return true;
        } catch (Exception e) {
            // If relaunching fails for any reason, just continue in the current JVM rather
            // than
            // preventing the launcher from starting at all.
            System.err.println("Could not relaunch with limited RAM: " + e.getMessage());
            return false;
        }
    }
}