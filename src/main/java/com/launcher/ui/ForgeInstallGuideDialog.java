package com.launcher.ui;

import com.launcher.Main;
import com.launcher.manager.SettingsManager;
import com.launcher.minecraft.ForgeInstaller;
import com.launcher.model.LauncherSettings;
import com.launcher.util.NativeFileChooser;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.Timer;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;

/**
 * Walks the user through installing Forge with the <em>official</em> Forge installer
 * instead of trying to drive it headlessly. Modern Forge installers are fragile to
 * automate (they change their internals every couple of Minecraft versions), so instead
 * of silently shelling out to them, this dialog shows the user exactly what to do and
 * waits until it detects that the install actually happened.
 * <p>
 * Steps shown to the user:
 * <ol>
 *   <li>Open the official Forge files page (clickable link)</li>
 *   <li>Pick the Forge build for this instance</li>
 *   <li>Download the installer ({@code .jar})</li>
 *   <li>Open/run the downloaded installer jar</li>
 *   <li>Choose "Install Client" in the installer</li>
 *   <li>Point the installer at this instance's folder (path shown, copyable)</li>
 *   <li>Confirm and wait — this dialog auto-detects completion</li>
 * </ol>
 */
public class ForgeInstallGuideDialog extends JDialog {

    private static final String FORGE_FILES_URL = "https://files.minecraftforge.net/net/minecraftforge/forge/";

    private boolean completed = false;

    private final ForgeInstaller forgeInstaller;
    private final String mcVersion;
    private final String forgeVersion;
    private final Path gameDir;

    private Timer pollTimer;
    private JButton continueButton;
    private JLabel waitStatusLabel;
    private JLabel spinnerLabel;
    private int spinnerFrame = 0;
    private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    public ForgeInstallGuideDialog(Frame owner, String mcVersion, String forgeVersion, Path gameDir) {
        super(owner, "Install Forge", true);
        this.mcVersion = mcVersion;
        this.forgeVersion = forgeVersion;
        this.gameDir = gameDir;
        this.forgeInstaller = new ForgeInstaller();

        try {
            forgeInstaller.prepareGameDir(gameDir);
        } catch (Exception ignored) {
            // Non-fatal — worst case the installer complains and the user re-runs it.
        }

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);

        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        Color panelBg = Main.hexToColor(settings.panelBgColor, new Color(19, 19, 26));
        Color cardBg = new Color(
                clamp(panelBg.getRed() + 8), clamp(panelBg.getGreen() + 8), clamp(panelBg.getBlue() + 10));
        Color textColor = Main.hexToColor(settings.textColor, new Color(226, 226, 234));
        Color mutedColor = new Color(150, 150, 160);
        Color accent = Main.hexToColor(settings.accentColor, new Color(16, 185, 129));

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(panelBg);

        // ── Header ───────────────────────────────────────────────────────────
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(panelBg);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(40, 40, 50)),
                new EmptyBorder(20, 24, 16, 24)));

        JLabel title = new JLabel("Install Forge " + forgeVersion);
        title.setFont(new Font("SansSerif", Font.BOLD, 19));
        title.setForeground(Color.WHITE);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("<html>For Minecraft " + mcVersion + " &nbsp;·&nbsp; "
                + "Forge's installer has to run on its own, so follow the steps below.</html>");
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 12));
        subtitle.setForeground(mutedColor);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle.setBorder(new EmptyBorder(4, 0, 0, 0));

        header.add(title);
        header.add(subtitle);
        root.add(header, BorderLayout.NORTH);

        // ── Steps ────────────────────────────────────────────────────────────
        JPanel steps = new JPanel();
        steps.setLayout(new BoxLayout(steps, BoxLayout.Y_AXIS));
        steps.setBackground(panelBg);
        steps.setBorder(new EmptyBorder(16, 24, 8, 24));

        steps.add(buildLinkStep(1,
                "Open the Forge downloads page",
                "Click the link below to open it in your browser.",
                FORGE_FILES_URL, cardBg, textColor, mutedColor, accent));
        steps.add(Box.createVerticalStrut(10));

        steps.add(buildTextStep(2,
                "Select the Forge version",
                "<html>Find and select build <b>" + escape(forgeVersion) + "</b> "
                        + "for Minecraft <b>" + escape(mcVersion) + "</b> on the page.</html>",
                cardBg, textColor, mutedColor, accent));
        steps.add(Box.createVerticalStrut(10));

        steps.add(buildTextStep(3,
                "Download the installer",
                "<html>Click the <b>Installer</b> download button. The file will end in "
                        + "<b>.jar</b> (e.g. <i>forge-" + escape(mcVersion) + "-" + escape(forgeVersion)
                        + "-installer.jar</i>).</html>",
                cardBg, textColor, mutedColor, accent));
        steps.add(Box.createVerticalStrut(10));

        steps.add(buildOpenStep(4,
                "Run the installer you just downloaded",
                "Click the button below — it'll try to find the installer automatically, or let you pick it.",
                cardBg, textColor, mutedColor, accent));
        steps.add(Box.createVerticalStrut(10));

        steps.add(buildTextStep(5,
                "Choose \"Install Client\"",
                "In the Forge installer window, select the <b>Install Client</b> option.",
                cardBg, textColor, mutedColor, accent));
        steps.add(Box.createVerticalStrut(10));

        steps.add(buildPathStep(6,
                "Set the install location",
                "Paste this exact path into the installer's destination field:",
                gameDir, cardBg, textColor, mutedColor, accent));
        steps.add(Box.createVerticalStrut(10));

        steps.add(buildTextStep(7,
                "Click OK and wait",
                "Click OK in the installer and let it finish. This window will update automatically once it's done.",
                cardBg, textColor, mutedColor, accent));

        JScrollPane scroll = new JScrollPane(steps);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setBackground(panelBg);
        scroll.getViewport().setBackground(panelBg);
        root.add(scroll, BorderLayout.CENTER);

        // ── Footer ───────────────────────────────────────────────────────────
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(panelBg);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(40, 40, 50)),
                new EmptyBorder(14, 24, 14, 24)));

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        statusRow.setBackground(panelBg);
        spinnerLabel = new JLabel(SPINNER_FRAMES[0]);
        spinnerLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        spinnerLabel.setForeground(accent);
        waitStatusLabel = new JLabel("Waiting for the installation to finish…");
        waitStatusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        waitStatusLabel.setForeground(mutedColor);
        statusRow.add(spinnerLabel);
        statusRow.add(waitStatusLabel);
        footer.add(statusRow, BorderLayout.WEST);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnRow.setBackground(panelBg);

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.setPreferredSize(new Dimension(90, 32));
        cancelBtn.addActionListener(e -> {
            completed = false;
            dispose();
        });

        continueButton = new JButton("Waiting…");
        continueButton.setPreferredSize(new Dimension(150, 32));
        continueButton.setEnabled(false);
        continueButton.setBackground(accent);
        continueButton.setForeground(Color.WHITE);
        continueButton.addActionListener(e -> {
            if (forgeInstaller.isInstalled(gameDir, mcVersion, forgeVersion)) {
                completed = true;
                dispose();
            }
        });

        btnRow.add(cancelBtn);
        btnRow.add(continueButton);
        footer.add(btnRow, BorderLayout.EAST);

        root.add(footer, BorderLayout.SOUTH);

        setContentPane(root);
        setSize(560, 640);
        setLocationRelativeTo(owner);

        startPolling();
    }

    /** True once the dialog detected (or the user confirmed) a completed Forge install. */
    public boolean isCompleted() {
        return completed;
    }

    // ------------------------------------------------------------------------
    // Polling
    // ------------------------------------------------------------------------

    private void startPolling() {
        pollTimer = new Timer(1000, e -> {
            spinnerFrame = (spinnerFrame + 1) % SPINNER_FRAMES.length;
            spinnerLabel.setText(SPINNER_FRAMES[spinnerFrame]);

            if (forgeInstaller.isInstalled(gameDir, mcVersion, forgeVersion)) {
                pollTimer.stop();
                spinnerLabel.setText("✔");
                spinnerLabel.setForeground(new Color(16, 185, 129));
                waitStatusLabel.setText("Forge installed — you're all set!");
                continueButton.setText("Continue");
                continueButton.setEnabled(true);
                getRootPane().setDefaultButton(continueButton);
            }
        });
        pollTimer.start();

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                if (pollTimer != null) pollTimer.stop();
            }
        });
    }

    // ------------------------------------------------------------------------
    // Step builders
    // ------------------------------------------------------------------------

    private JPanel stepCard(int number, String title, Color cardBg, Color textColor, Color accent) {
        JPanel card = new JPanel(new BorderLayout(12, 0));
        card.setBackground(cardBg);
        card.setBorder(new EmptyBorder(14, 14, 14, 14));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getMaximumSize().height));

        JLabel badge = new JLabel(String.valueOf(number), SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(accent);
                g2.fillOval(0, 0, getWidth(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        badge.setPreferredSize(new Dimension(28, 28));
        badge.setFont(new Font("SansSerif", Font.BOLD, 13));
        badge.setForeground(Color.WHITE);
        badge.setOpaque(false);

        JPanel badgeWrap = new JPanel(new BorderLayout());
        badgeWrap.setOpaque(false);
        badgeWrap.add(badge, BorderLayout.NORTH);
        card.add(badgeWrap, BorderLayout.WEST);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        titleLabel.setForeground(textColor);

        return card;
    }

    private JComponent buildTextStep(int number, String title, String bodyHtml,
                                      Color cardBg, Color textColor, Color mutedColor, Color accent) {
        JPanel card = stepCard(number, title, cardBg, textColor, accent);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        titleLabel.setForeground(textColor);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel body = new JLabel("<html><body style='width: 380px;'>" + stripHtml(bodyHtml) + "</body></html>");
        body.setFont(new Font("SansSerif", Font.PLAIN, 12));
        body.setForeground(mutedColor);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.setBorder(new EmptyBorder(4, 0, 0, 0));

        content.add(titleLabel);
        content.add(body);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildLinkStep(int number, String title, String bodyText, String url,
                                      Color cardBg, Color textColor, Color mutedColor, Color accent) {
        JPanel card = stepCard(number, title, cardBg, textColor, accent);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        titleLabel.setForeground(textColor);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel body = new JLabel(bodyText);
        body.setFont(new Font("SansSerif", Font.PLAIN, 12));
        body.setForeground(mutedColor);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.setBorder(new EmptyBorder(4, 0, 6, 0));

        JLabel link = new JLabel("<html><u>" + escape(url) + "</u></html>");
        link.setFont(new Font("SansSerif", Font.PLAIN, 12));
        link.setForeground(accent);
        link.setAlignmentX(Component.LEFT_ALIGNMENT);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openUrl(url);
            }
        });

        content.add(titleLabel);
        content.add(body);
        content.add(link);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildOpenStep(int number, String title, String bodyText,
                                      Color cardBg, Color textColor, Color mutedColor, Color accent) {
        JPanel card = stepCard(number, title, cardBg, textColor, accent);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        titleLabel.setForeground(textColor);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel body = new JLabel("<html><body style='width: 340px;'>" + bodyText + "</body></html>");
        body.setFont(new Font("SansSerif", Font.PLAIN, 12));
        body.setForeground(mutedColor);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.setBorder(new EmptyBorder(4, 0, 8, 0));

        JPanel buttonsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttonsRow.setOpaque(false);
        buttonsRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton runInstaller = new JButton("Choose & Run Installer .jar…");
        runInstaller.setBackground(accent);
        runInstaller.setForeground(Color.WHITE);
        runInstaller.addActionListener(e -> chooseAndRunInstaller());

        buttonsRow.add(runInstaller);

        JLabel hint = new JLabel("<html><body style='width: 340px;'>Tip: this looks in your Downloads folder "
                + "and picks the matching installer automatically if it finds one.</body></html>");
        hint.setFont(new Font("SansSerif", Font.PLAIN, 11));
        hint.setForeground(mutedColor);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(new EmptyBorder(6, 0, 0, 0));

        content.add(titleLabel);
        content.add(body);
        content.add(buttonsRow);
        content.add(hint);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildPathStep(int number, String title, String bodyText, Path path,
                                      Color cardBg, Color textColor, Color mutedColor, Color accent) {
        JPanel card = stepCard(number, title, cardBg, textColor, accent);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        titleLabel.setForeground(textColor);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel body = new JLabel(bodyText);
        body.setFont(new Font("SansSerif", Font.PLAIN, 12));
        body.setForeground(mutedColor);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.setBorder(new EmptyBorder(4, 0, 6, 0));

        JPanel pathRow = new JPanel(new BorderLayout(8, 0));
        pathRow.setOpaque(false);
        pathRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        pathRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

        JTextField pathField = new JTextField(path.toAbsolutePath().toString());
        pathField.setEditable(false);
        pathField.setFont(new Font("Monospaced", Font.PLAIN, 12));
        pathField.setPreferredSize(new Dimension(280, 30));
        pathField.setCaretPosition(0);

        JButton copyBtn = new JButton("Copy");
        copyBtn.setPreferredSize(new Dimension(70, 30));
        copyBtn.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(path.toAbsolutePath().toString()), null);
            copyBtn.setText("Copied!");
            Timer resetTimer = new Timer(1200, ev -> copyBtn.setText("Copy"));
            resetTimer.setRepeats(false);
            resetTimer.start();
        });

        JButton openBtn = new JButton("Open Folder");
        openBtn.setPreferredSize(new Dimension(110, 30));
        openBtn.addActionListener(e -> openFolder(path));

        pathRow.add(pathField, BorderLayout.CENTER);
        JPanel pathButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        pathButtons.setOpaque(false);
        pathButtons.add(copyBtn);
        pathButtons.add(openBtn);
        pathRow.add(pathButtons, BorderLayout.EAST);

        content.add(titleLabel);
        content.add(body);
        content.add(pathRow);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    // ------------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------------

    private void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Couldn't open the browser automatically.\nPlease visit:\n" + url,
                    "Open link", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void openFolder(Path folder) {
        try {
            File f = folder.toFile();
            if (!f.exists()) {
                f.mkdirs();
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(f);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Couldn't open the folder automatically:\n" + folder,
                    "Open folder", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * Tries to find the Forge installer jar on its own first (checking the user's Downloads
     * folder for a name matching this instance's Minecraft/Forge version, falling back to the
     * newest "forge...installer.jar" there). If nothing confident is found, falls back to
     * letting the user pick the file. Either way, the jar is then launched with
     * {@code java -jar <file>} so it runs as the real Forge installer instead of whatever the
     * OS happens to associate with {@code .jar} files.
     */
    private void chooseAndRunInstaller() {
        File jar = detectInstallerJar();
        if (jar == null) {
            File downloads = new File(System.getProperty("user.home"), "Downloads");
            jar = NativeFileChooser.openFile(this, "Select the Forge installer",
                    downloads.exists() ? downloads : null, "Forge installer (.jar)", "jar");
        } else {
            waitStatusLabel.setText("Found installer automatically: " + jar.getName());
        }
        if (jar == null) return;

        if (!jar.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            JOptionPane.showMessageDialog(this,
                    "That doesn't look like a Forge installer .jar file:\n" + jar.getName(),
                    "Not a jar file", JOptionPane.WARNING_MESSAGE);
            return;
        }

        runInstallerJar(jar);
    }

    /**
     * Smart detection: looks in the user's Downloads folder for an installer jar matching this
     * instance's Minecraft + Forge version, e.g. {@code forge-1.20.1-47.2.0-installer.jar}.
     * If no exact match exists, falls back to the most recently modified file that looks like a
     * Forge installer jar. Returns {@code null} if nothing plausible is found.
     */
    private File detectInstallerJar() {
        File downloads = new File(System.getProperty("user.home"), "Downloads");
        File[] files = downloads.listFiles();
        if (files == null) return null;

        String exactName = ("forge-" + mcVersion + "-" + forgeVersion + "-installer.jar").toLowerCase(java.util.Locale.ROOT);

        File bestFuzzy = null;
        long bestFuzzyTime = -1;
        for (File f : files) {
            if (!f.isFile()) continue;
            String lower = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (!lower.endsWith(".jar")) continue;
            if (lower.equals(exactName)) {
                return f;
            }
            if (lower.contains("forge") && lower.contains("installer") && !lower.contains("neoforge")) {
                long modified = f.lastModified();
                if (modified > bestFuzzyTime) {
                    bestFuzzyTime = modified;
                    bestFuzzy = f;
                }
            }
        }
        return bestFuzzy;
    }

    private void runInstallerJar(File jar) {
        try {
            new ProcessBuilder("java", "-jar", jar.getAbsolutePath())
                    .directory(jar.getParentFile())
                    .start();
            waitStatusLabel.setText("Installer launched — finish the steps in its window…");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Couldn't launch the installer:\n" + ex.getMessage()
                            + "\n\nMake sure Java is on your PATH, or run this from a terminal:\n"
                            + "java -jar \"" + jar.getAbsolutePath() + "\"",
                    "Launch installer", JOptionPane.WARNING_MESSAGE);
        }
    }

    // ------------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------------

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String stripHtml(String s) {
        // bodyHtml passed to buildTextStep already contains safe inline <b>/<i> tags we want to keep.
        return s;
    }
}
