package com.launcher.ui;

import com.launcher.minecraft.FabricInstaller;
import com.launcher.minecraft.QuiltInstaller;
import com.launcher.minecraft.NeoForgeInstaller;
import com.launcher.minecraft.VersionManifestService;
import com.launcher.model.Instance;
import com.launcher.model.ModLoaderType;
import com.launcher.Main;
import com.launcher.manager.LauncherPaths;
import com.launcher.model.LauncherSettings;
import com.launcher.manager.SettingsManager;
import com.launcher.util.JavaInstallationFinder;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

/**
 * The "Edit Instance" screen. Rebuilt to match the same card-based visual
 * language as CreateInstancePanel (rounded "glass" cards, two balanced
 * columns, single scrolling body) instead of the old JTabbedPane +
 * GridBagLayout form. The modpack tab/fields from the old version have been
 * removed entirely — editing an existing instance no longer offers a
 * "reimport as modpack" path, just its core settings.
 */
public class EditInstancePanel extends JPanel {

    private static final int CARD_ARC = 16;
    private static final int MAX_CONTENT_WIDTH = 1040;

    private final Instance instanceToEdit;
    private final Consumer<Instance> onSave;
    private final Runnable onCancel;
    private final Runnable onDelete;

    // Resolved once in initUI() and shared by every card-building helper below.
    private Color bg, panelBg, textColor, textDim, accent, cardFill, cardBorder;

    public EditInstancePanel(Instance instanceToEdit, Consumer<Instance> onSave, Runnable onCancel) {
        this(instanceToEdit, onSave, onCancel, null);
    }

    public EditInstancePanel(Instance instanceToEdit, Consumer<Instance> onSave, Runnable onCancel, Runnable onDelete) {
        this.instanceToEdit = instanceToEdit;
        this.onSave = onSave;
        this.onCancel = onCancel;
        this.onDelete = onDelete;
        initUI();
    }

    private void initUI() {
        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        bg = Main.hexToColor(settings.bgColor, new Color(10, 10, 15));
        panelBg = Main.hexToColor(settings.panelBgColor, new Color(19, 19, 26));
        textColor = Main.hexToColor(settings.textColor, new Color(226, 226, 234));
        accent = Main.hexToColor(settings.accentColor, new Color(16, 185, 129));
        textDim = new Color(150, 150, 168);
        cardFill = new Color(255, 255, 255, 10);
        cardBorder = new Color(255, 255, 255, 18);

        setLayout(new BorderLayout());
        boolean transparent = settings.enableTransparency;
        setOpaque(!transparent);
        setBackground(panelBg);

        add(buildHeader(), BorderLayout.NORTH);

        // ── Shared state used across cards + the footer's Save action ──────
        final String[] chosenImagePath = { instanceToEdit.imagePath };
        final ModLoaderType[] selectedLoader = { instanceToEdit.modLoader != null ? instanceToEdit.modLoader : ModLoaderType.VANILLA };

        JLabel iconView = new JLabel();
        loadIcon(iconView, instanceToEdit.imagePath);

        CustomTextField nameField = new CustomTextField(instanceToEdit.name != null ? instanceToEdit.name : "");
        nameField.setFont(new Font("SansSerif", Font.BOLD, 14));
        nameField.setBorder(new EmptyBorder(4, 4, 4, 10));

        CustomComboBox<String> versionBox = new CustomComboBox<>();
        versionBox.setEnabled(false);
        CustomToggle snapshotsCb = new CustomToggle("Include snapshots");

        CustomComboBox<String> loaderVerBox = new CustomComboBox<>();
        loaderVerBox.setEnabled(false);
        JLabel loaderVerLabel = fieldLabel("Loader version");
        boolean startsVanilla = selectedLoader[0] == ModLoaderType.VANILLA;
        loaderVerBox.setVisible(!startsVanilla);
        loaderVerLabel.setVisible(!startsVanilla);

        JSlider ramSlider = new JSlider(1024, 8192, instanceToEdit.ramMb > 0 ? instanceToEdit.ramMb : 3072);
        ramSlider.setMajorTickSpacing(1024);
        ramSlider.setSnapToTicks(true);
        ramSlider.setOpaque(false);
        ramSlider.setForeground(accent);
        JLabel ramLbl = pill(String.format("%.1f GB", ramSlider.getValue() / 1024.0), accent);
        ramSlider.addChangeListener(e -> {
            int val = ramSlider.getValue();
            ramLbl.setText(String.format("%.1f GB", val / 1024.0));
        });

        CustomToggle hiddenCb = new CustomToggle("Hide this instance");
        hiddenCb.setSelected(instanceToEdit.hidden);

        // Advanced: JVM arguments + custom Java runtime. The runtime picker
        // mirrors the "Java Executable Path" control in Settings: a dropdown
        // populated by scanning the system for installed JVMs, plus a
        // Browse/Rescan pair and a manual path field for a custom install.
        CustomTextField jvmArgsField = new CustomTextField(
                instanceToEdit.jvmArgs != null ? instanceToEdit.jvmArgs : "");

        CustomComboBox<String> javaInstallDropdown = new CustomComboBox<>();
        javaInstallDropdown.setFont(new Font("SansSerif", Font.PLAIN, 12));
        javaInstallDropdown.addItem("Scanning for Java installations…");
        javaInstallDropdown.setEnabled(false);
        javaInstallDropdown.setAlignmentX(Component.LEFT_ALIGNMENT);

        CustomTextField javaPathField = new CustomTextField(
                instanceToEdit.javaPath != null ? instanceToEdit.javaPath : "");
        javaPathField.setAlignmentX(Component.LEFT_ALIGNMENT);

        GhostButton browseJavaBtn = new GhostButton("Browse…");
        GhostButton rescanJavaBtn = new GhostButton("Rescan");

        final Map<String, String> detectedJavaPaths = new LinkedHashMap<>();
        final String useDefaultLabel = "Use launcher default";
        final String customPathLabel = "Custom path (set below)";

        javaInstallDropdown.addActionListener(e -> {
            if (!javaInstallDropdown.isEnabled()) return; // still scanning
            Object selected = javaInstallDropdown.getSelectedItem();
            if (selected == null) return;
            String label = selected.toString();
            if (label.equals(customPathLabel)) return; // leave javaPathField for manual editing
            String resolvedPath = label.equals(useDefaultLabel) ? "" : detectedJavaPaths.get(label);
            javaPathField.setText(resolvedPath != null ? resolvedPath : "");
        });

        Runnable doScanJava = () -> {
            javaInstallDropdown.setEnabled(false);
            javaInstallDropdown.removeAllItems();
            javaInstallDropdown.addItem("Scanning for Java installations…");
            new SwingWorker<List<JavaInstallationFinder.JavaInstallation>, Void>() {
                @Override
                protected List<JavaInstallationFinder.JavaInstallation> doInBackground() {
                    try {
                        return JavaInstallationFinder.findInstallations();
                    } catch (Exception ex) {
                        return java.util.Collections.emptyList();
                    }
                }

                @Override
                protected void done() {
                    List<JavaInstallationFinder.JavaInstallation> installs;
                    try {
                        installs = get();
                    } catch (Exception ex) {
                        installs = java.util.Collections.emptyList();
                    }

                    javaInstallDropdown.removeAllItems();
                    detectedJavaPaths.clear();
                    javaInstallDropdown.addItem(useDefaultLabel);
                    for (JavaInstallationFinder.JavaInstallation install : installs) {
                        detectedJavaPaths.put(install.displayName, install.javaExecutablePath);
                        javaInstallDropdown.addItem(install.displayName);
                    }
                    javaInstallDropdown.addItem(customPathLabel);

                    String currentPath = javaPathField.getText().trim();
                    if (currentPath.isEmpty()) {
                        javaInstallDropdown.setSelectedItem(useDefaultLabel);
                    } else {
                        String matchLabel = null;
                        for (Map.Entry<String, String> entry : detectedJavaPaths.entrySet()) {
                            if (entry.getValue().equals(currentPath)) {
                                matchLabel = entry.getKey();
                                break;
                            }
                        }
                        javaInstallDropdown.setSelectedItem(matchLabel != null ? matchLabel : customPathLabel);
                    }
                    javaInstallDropdown.setEnabled(true);
                }
            }.execute();
        };
        doScanJava.run();
        rescanJavaBtn.addActionListener(e -> doScanJava.run());

        browseJavaBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Select Java Executable");
            fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
            String exeName = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
            fc.setSelectedFile(new File(exeName));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                String chosen = fc.getSelectedFile().getAbsolutePath();
                javaPathField.setText(chosen);
                javaInstallDropdown.setSelectedItem(customPathLabel);
            }
        });

        // Directory mode segmented control
        boolean usesCustomDir = instanceToEdit.useCustomDirectory && instanceToEdit.customDirectoryPath != null
                && !instanceToEdit.customDirectoryPath.equals(
                        LauncherPaths.getDefaultMinecraftPath().toAbsolutePath().toString());
        JToggleButton defaultDirBtn = new SegButton("Default location", !usesCustomDir);
        JToggleButton customDirBtn = new SegButton("Custom location", usesCustomDir);
        group(defaultDirBtn, customDirBtn);
        CustomTextField customDirField = new CustomTextField(
                instanceToEdit.customDirectoryPath != null ? instanceToEdit.customDirectoryPath : "");
        customDirField.setEnabled(usesCustomDir);
        GhostButton browseDirBtn = new GhostButton("Browse…");
        browseDirBtn.setEnabled(usesCustomDir);
        browseDirBtn.addActionListener(e -> {
            JFileChooser dc = new JFileChooser();
            dc.setDialogTitle("Select Directory");
            dc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (dc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                customDirField.setText(dc.getSelectedFile().getAbsolutePath());
            }
        });
        defaultDirBtn.addActionListener(e -> {
            customDirField.setEnabled(false);
            browseDirBtn.setEnabled(false);
        });
        customDirBtn.addActionListener(e -> {
            customDirField.setEnabled(true);
            browseDirBtn.setEnabled(true);
        });

        JPanel dirFieldRow = new JPanel(new BorderLayout(8, 0));
        dirFieldRow.setOpaque(false);
        dirFieldRow.add(customDirField, BorderLayout.CENTER);
        dirFieldRow.add(browseDirBtn, BorderLayout.EAST);

        // ── Loader segmented control ─────────────────────────────────────
        Runnable[] loadLoaderVersHolder = new Runnable[1];
        JPanel loaderSeg = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        loaderSeg.setOpaque(false);
        java.util.List<JToggleButton> loaderBtns = new java.util.ArrayList<>();
        for (ModLoaderType type : ModLoaderType.values()) {
            JToggleButton b = new SegButton(prettyLoaderName(type), type == selectedLoader[0]);
            b.addActionListener(e -> {
                selectedLoader[0] = type;
                boolean vanilla = type == ModLoaderType.VANILLA;
                loaderVerBox.setEnabled(!vanilla);
                loaderVerLabel.setVisible(!vanilla);
                loaderVerBox.setVisible(!vanilla);
                if (loadLoaderVersHolder[0] != null) loadLoaderVersHolder[0].run();
            });
            loaderBtns.add(b);
            loaderSeg.add(b);
        }
        group(loaderBtns.toArray(new JToggleButton[0]));

        // ── Two balanced columns ──────────────────────────────────────────
        // Left: Appearance & Name, then Performance (RAM + visibility).
        // Right: Version & Loader, then Game Directory.
        // Plain BoxLayout throughout (no GridBagLayout fill/weighty tricks,
        // no frozen setPreferredSize snapshots) — see CreateInstancePanel for
        // why that combination was avoided.
        JPanel leftCol = new JPanel();
        leftCol.setOpaque(false);
        leftCol.setLayout(new BoxLayout(leftCol, BoxLayout.Y_AXIS));
        leftCol.add(sectionCard("Appearance & Name", identityContent(iconView, nameField, chosenImagePath)));
        leftCol.add(Box.createVerticalStrut(12));
        leftCol.add(sectionCard("Performance", ramContent(ramSlider, ramLbl, hiddenCb)));
        leftCol.add(Box.createVerticalStrut(12));
        leftCol.add(sectionCard("Advanced (Java & JVM)",
                advancedContent(javaInstallDropdown, javaPathField, browseJavaBtn, rescanJavaBtn, jvmArgsField)));

        JPanel rightCol = new JPanel();
        rightCol.setOpaque(false);
        rightCol.setLayout(new BoxLayout(rightCol, BoxLayout.Y_AXIS));
        rightCol.add(sectionCard("Version & Loader", versionAndLoaderContent(
                versionBox, snapshotsCb, loaderSeg, loaderVerLabel, loaderVerBox)));
        rightCol.add(Box.createVerticalStrut(12));
        rightCol.add(sectionCard("Game Directory", directoryContent(defaultDirBtn, customDirBtn, dirFieldRow)));

        leftCol.setAlignmentY(Component.TOP_ALIGNMENT);
        rightCol.setAlignmentY(Component.TOP_ALIGNMENT);
        JPanel mainRow = new JPanel();
        mainRow.setOpaque(false);
        mainRow.setLayout(new BoxLayout(mainRow, BoxLayout.X_AXIS));
        mainRow.add(leftCol);
        mainRow.add(Box.createHorizontalStrut(14));
        mainRow.add(rightCol);
        mainRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setAlignmentX(Component.CENTER_ALIGNMENT);
        column.setMaximumSize(new Dimension(MAX_CONTENT_WIDTH, Integer.MAX_VALUE));
        column.add(mainRow);

        JPanel centered = new JPanel();
        centered.setOpaque(false);
        centered.setLayout(new BoxLayout(centered, BoxLayout.X_AXIS));
        centered.add(Box.createHorizontalGlue());
        centered.add(column);
        centered.add(Box.createHorizontalGlue());

        JPanel scrollHost = new JPanel(new BorderLayout());
        scrollHost.setOpaque(false);
        scrollHost.setBorder(new EmptyBorder(16, 24, 16, 24));
        scrollHost.add(centered, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(scrollHost);
        SmoothScroll.install(scroll);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        // ── Loaders (network calls) ──────────────────────────────────────
        Runnable loadVersions = () -> {
            versionBox.setEnabled(false);
            new Thread(() -> {
                try {
                    List<String> ids = snapshotsCb.isSelected()
                            ? new VersionManifestService().fetchAllVersionIds()
                            : new VersionManifestService().fetchReleaseVersionIds();
                    SwingUtilities.invokeLater(() -> {
                        versionBox.removeAllItems();
                        for (String id : ids) versionBox.addItem(id);
                        versionBox.setSelectedItem(instanceToEdit.mcVersion);
                        versionBox.setEnabled(true);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        versionBox.removeAllItems();
                        versionBox.addItem("Failed to load versions");
                        versionBox.setEnabled(true);
                    });
                }
            }, "fetch-versions").start();
        };

        Runnable loadLoaderVers = () -> {
            loaderVerBox.removeAllItems();
            ModLoaderType loader = selectedLoader[0];
            String mcVer = (String) versionBox.getSelectedItem();
            if (loader == ModLoaderType.VANILLA || mcVer == null) {
                loaderVerBox.setEnabled(false);
                return;
            }
            if (loader == ModLoaderType.FORGE) {
                loaderVerBox.setEnabled(true);
                loaderVerBox.addItem("Recommended");
                loaderVerBox.addItem("Latest");
                loaderVerBox.setSelectedItem(instanceToEdit.modLoaderVersion != null
                        ? instanceToEdit.modLoaderVersion : "Recommended");
                return;
            }
            if (loader == ModLoaderType.NEOFORGE) {
                loaderVerBox.setEnabled(false);
                new Thread(() -> {
                    try {
                        List<String> vers = new NeoForgeInstaller().fetchVersions(mcVer);
                        SwingUtilities.invokeLater(() -> {
                            loaderVerBox.removeAllItems();
                            for (String v : vers) loaderVerBox.addItem(v);
                            loaderVerBox.setSelectedItem(instanceToEdit.modLoaderVersion != null
                                    && vers.contains(instanceToEdit.modLoaderVersion)
                                    ? instanceToEdit.modLoaderVersion : (vers.isEmpty() ? null : vers.get(0)));
                            loaderVerBox.setEnabled(true);
                        });
                    } catch (Exception ex) {
                        SwingUtilities.invokeLater(() -> {
                            loaderVerBox.removeAllItems();
                            loaderVerBox.addItem("Failed to load");
                            loaderVerBox.setEnabled(true);
                        });
                    }
                }, "fetch-neoforge-vers").start();
                return;
            }
            loaderVerBox.setEnabled(false);
            final boolean isQuilt = loader == ModLoaderType.QUILT;
            new Thread(() -> {
                try {
                    List<String> vers = isQuilt ? new QuiltInstaller().fetchLoaderVersions(mcVer)
                                                : new FabricInstaller().fetchLoaderVersions(mcVer);
                    SwingUtilities.invokeLater(() -> {
                        loaderVerBox.removeAllItems();
                        for (String v : vers) loaderVerBox.addItem(v);
                        loaderVerBox.setSelectedItem(instanceToEdit.modLoaderVersion != null
                                && vers.contains(instanceToEdit.modLoaderVersion)
                                ? instanceToEdit.modLoaderVersion : (vers.isEmpty() ? null : vers.get(0)));
                        loaderVerBox.setEnabled(true);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        loaderVerBox.removeAllItems();
                        loaderVerBox.addItem("Failed to load");
                        loaderVerBox.setEnabled(true);
                    });
                }
            }, "fetch-loader-vers").start();
        };
        loadLoaderVersHolder[0] = loadLoaderVers;

        versionBox.addActionListener(e -> loadLoaderVers.run());
        snapshotsCb.addActionListener(e -> loadVersions.run());
        loadVersions.run();

        // ── Footer ────────────────────────────────────────────────────────
        JPanel footerPanel = new JPanel(new BorderLayout());
        footerPanel.setOpaque(false);
        footerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(255, 255, 255, 18)),
                new EmptyBorder(14, 24, 14, 24)));

        GhostButton cancelBtn = new GhostButton("Cancel");
        cancelBtn.addActionListener(e -> onCancel.run());
        JPanel leftFooter = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftFooter.setOpaque(false);
        leftFooter.add(cancelBtn);
        if (onDelete != null) {
            GhostButton deleteBtn = new GhostButton("Delete Instance");
            deleteBtn.setForeground(new Color(240, 90, 90));
            deleteBtn.addActionListener(e -> {
                int res = JOptionPane.showConfirmDialog(this,
                        "Are you sure you want to delete \"" + instanceToEdit.name + "\"? This cannot be undone.",
                        "Delete Instance", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (res == JOptionPane.YES_OPTION) onDelete.run();
            });
            leftFooter.add(deleteBtn);
        }
        footerPanel.add(leftFooter, BorderLayout.WEST);

        PrimaryButton saveBtn = new PrimaryButton("Save Changes");
        saveBtn.addActionListener(e -> {
            String name = nameField.getText().trim();
            String ver = (String) versionBox.getSelectedItem();
            if (name.isEmpty() || ver == null) {
                JOptionPane.showMessageDialog(this, "Instance name and MC version are required.", "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
            instanceToEdit.name = name;
            instanceToEdit.mcVersion = ver;
            instanceToEdit.modLoader = selectedLoader[0];
            instanceToEdit.modLoaderVersion = selectedLoader[0] == ModLoaderType.VANILLA
                    ? null : (String) loaderVerBox.getSelectedItem();
            instanceToEdit.ramMb = ramSlider.getValue();
            instanceToEdit.hidden = hiddenCb.isSelected();
            instanceToEdit.imagePath = chosenImagePath[0];
            String jvmArgs = jvmArgsField.getText().trim();
            instanceToEdit.jvmArgs = jvmArgs.isEmpty() ? null : jvmArgs;
            String javaPath = javaPathField.getText().trim();
            instanceToEdit.javaPath = javaPath.isEmpty() ? null : javaPath;

            if (defaultDirBtn.isSelected()) {
                instanceToEdit.useCustomDirectory = false;
                instanceToEdit.customDirectoryPath = null;
            } else {
                instanceToEdit.useCustomDirectory = true;
                instanceToEdit.customDirectoryPath = customDirField.getText().trim();
            }
            onSave.accept(instanceToEdit);
        });
        JPanel rightFooter = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        rightFooter.setOpaque(false);
        rightFooter.add(saveBtn);
        footerPanel.add(rightFooter, BorderLayout.EAST);

        add(footerPanel, BorderLayout.SOUTH);

        // This panel is constructed on demand (when the user clicks Edit),
        // which is always *after* the app's one-time startup theme pass has
        // already run — so it never gets the transparency treatment applied
        // to the rest of the UI unless it applies its own rule here.
        applyLocalTranslucency(this, transparent);
    }

    /**
     * Mirrors Main's setComponentTranslucent(): flips setOpaque(false) on
     * plain structural panels (BoxLayout/GridBagLayout/BorderLayout) when
     * transparency is enabled, so the app's shared gradient/background shows
     * through instead of a flat panel color — while leaving text fields,
     * combo boxes, sliders, and custom-painted cards to manage their own
     * opacity/painting untouched.
     */
    private void applyLocalTranslucency(Component comp, boolean transparent) {
        if (comp instanceof Card) {
            // Cards paint their own translucent fill regardless of setOpaque;
            // leave them alone.
        } else if (comp instanceof JPanel panel) {
            if (panel.getClass() == JPanel.class || panel.getLayout() instanceof BoxLayout
                    || panel.getLayout() instanceof GridBagLayout || panel.getLayout() instanceof BorderLayout) {
                panel.setOpaque(!transparent);
            }
        } else if (comp instanceof JScrollPane scroll) {
            scroll.setOpaque(!transparent);
            scroll.getViewport().setOpaque(!transparent);
        }
        if (comp instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyLocalTranslucency(child, transparent);
            }
        }
    }

    // ── Header ─────────────────────────────────────────────────────────────
    private JPanel buildHeader() {
        JPanel headerPanel = new JPanel(new BorderLayout(12, 0));
        headerPanel.setOpaque(false);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(255, 255, 255, 18)),
                new EmptyBorder(18, 24, 18, 24)));

        GhostButton backBtn = new GhostButton("←");
        backBtn.setFont(new Font("SansSerif", Font.BOLD, 14));
        backBtn.addActionListener(e -> onCancel.run());
        headerPanel.add(backBtn, BorderLayout.WEST);

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel titleLbl = new JLabel("Edit Instance");
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 20));
        titleLbl.setForeground(textColor);
        JLabel subLbl = new JLabel("Editing: " + instanceToEdit.name + "  ·  " + statsSummary());
        subLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        subLbl.setForeground(textDim);
        titles.add(titleLbl);
        titles.add(Box.createVerticalStrut(2));
        titles.add(subLbl);
        headerPanel.add(titles, BorderLayout.CENTER);

        GhostButton openFolderBtn = new GhostButton("Open Folder");
        openFolderBtn.addActionListener(e -> openInstanceFolder());
        headerPanel.add(openFolderBtn, BorderLayout.EAST);

        return headerPanel;
    }

    /** Small "3.2h played · created Jan 12, 2026 · Installed" style summary line. */
    private String statsSummary() {
        StringBuilder sb = new StringBuilder();
        double hours = instanceToEdit.playTimeSeconds / 3600.0;
        sb.append(hours >= 0.05 ? String.format("%.1fh played", hours) : "Never played");
        if (instanceToEdit.createdAt > 0) {
            sb.append("  ·  created ")
              .append(new java.text.SimpleDateFormat("MMM d, yyyy").format(new java.util.Date(instanceToEdit.createdAt)));
        }
        sb.append("  ·  ").append(instanceToEdit.installed ? "Installed" : "Not installed yet");
        return sb.toString();
    }

    /** Opens the instance's game directory in the OS file browser. */
    private void openInstanceFolder() {
        try {
            java.nio.file.Path dir = (instanceToEdit.useCustomDirectory
                    && instanceToEdit.customDirectoryPath != null && !instanceToEdit.customDirectoryPath.isBlank())
                    ? java.nio.file.Path.of(instanceToEdit.customDirectoryPath)
                    : LauncherPaths.defaultInstanceDir(instanceToEdit.name);
            File dirFile = dir.toFile();
            if (!dirFile.exists()) dirFile.mkdirs();
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dirFile);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Couldn't open the instance folder: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── Card content builders ────────────────────────────────────────────────
    private JPanel identityContent(JLabel iconView, CustomTextField nameField, String[] chosenImagePath) {
        iconView.setPreferredSize(new Dimension(64, 64));
        iconView.setMinimumSize(new Dimension(64, 64));

        GhostButton changeImageBtn = new GhostButton("Change Image…");
        GhostButton resetImageBtn = new GhostButton("Reset");
        resetImageBtn.setEnabled(chosenImagePath[0] != null);

        changeImageBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Choose Instance Image");
            fc.setFileFilter(new FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "bmp"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File chosen = fc.getSelectedFile();
                try {
                    ImageIcon img = new ImageIcon(chosen.getAbsolutePath());
                    Image scaled = img.getImage().getScaledInstance(64, 64, Image.SCALE_SMOOTH);
                    iconView.setIcon(new ImageIcon(scaled));
                    chosenImagePath[0] = chosen.getAbsolutePath();
                    resetImageBtn.setEnabled(true);
                } catch (Exception ignored) {}
            }
        });
        resetImageBtn.addActionListener(e -> {
            loadIcon(iconView, null);
            chosenImagePath[0] = null;
            resetImageBtn.setEnabled(false);
        });

        JPanel imgBtnCol = new JPanel();
        imgBtnCol.setLayout(new BoxLayout(imgBtnCol, BoxLayout.Y_AXIS));
        imgBtnCol.setOpaque(false);
        imgBtnCol.add(changeImageBtn);
        imgBtnCol.add(Box.createVerticalStrut(6));
        imgBtnCol.add(resetImageBtn);

        JPanel iconRow = new JPanel(new BorderLayout(14, 0));
        iconRow.setOpaque(false);
        iconRow.add(new RoundImageWrapper(iconView), BorderLayout.WEST);
        iconRow.add(imgBtnCol, BorderLayout.CENTER);

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(iconRow);
        content.add(Box.createVerticalStrut(14));
        content.add(fieldLabel("Instance name"));
        content.add(Box.createVerticalStrut(4));
        nameField.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(nameField);
        return content;
    }

    private JPanel versionAndLoaderContent(CustomComboBox<String> versionBox, CustomToggle snapshotsCb,
            JPanel loaderSeg, JLabel loaderVerLabel, CustomComboBox<String> loaderVerBox) {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JPanel verRow = new JPanel(new BorderLayout(10, 0));
        verRow.setOpaque(false);
        verRow.add(versionBox, BorderLayout.CENTER);
        snapshotsCb.setOpaque(false);
        verRow.add(snapshotsCb, BorderLayout.EAST);
        content.add(fieldLabel("Minecraft version"));
        content.add(Box.createVerticalStrut(4));
        content.add(verRow);
        content.add(Box.createVerticalStrut(14));
        content.add(fieldLabel("Mod loader"));
        content.add(Box.createVerticalStrut(6));
        content.add(loaderSeg);
        content.add(Box.createVerticalStrut(10));
        loaderVerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(loaderVerLabel);
        content.add(Box.createVerticalStrut(4));
        loaderVerBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(loaderVerBox);
        return content;
    }

    private JPanel ramContent(JSlider ramSlider, JLabel ramLbl, CustomToggle hiddenCb) {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JLabel ramTitle = fieldLabel("Allocated RAM");
        JPanel labelRow = new JPanel(new BorderLayout(10, 0));
        labelRow.setOpaque(false);
        labelRow.add(ramTitle, BorderLayout.WEST);
        labelRow.add(ramSlider, BorderLayout.CENTER);
        labelRow.add(ramLbl, BorderLayout.EAST);
        content.add(labelRow);

        content.add(Box.createVerticalStrut(16));
        hiddenCb.setOpaque(false);
        hiddenCb.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(hiddenCb);
        return content;
    }

    private JPanel directoryContent(JToggleButton defaultDirBtn, JToggleButton customDirBtn, JPanel dirFieldRow) {
        JPanel content = new JPanel(new BorderLayout(10, 0));
        content.setOpaque(false);
        JPanel seg = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        seg.setOpaque(false);
        seg.add(defaultDirBtn);
        seg.add(customDirBtn);
        content.add(seg, BorderLayout.WEST);
        content.add(dirFieldRow, BorderLayout.CENTER);
        return content;
    }

    private JPanel advancedContent(CustomComboBox<String> javaInstallDropdown, CustomTextField javaPathField,
            JButton browseBtn, JButton rescanBtn, CustomTextField jvmArgsField) {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        content.add(fieldLabel("Java runtime"));
        content.add(Box.createVerticalStrut(4));
        javaInstallDropdown.setAlignmentX(Component.LEFT_ALIGNMENT);
        javaInstallDropdown.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                javaInstallDropdown.getPreferredSize().height));
        content.add(javaInstallDropdown);
        content.add(Box.createVerticalStrut(6));

        JPanel javaRow = new JPanel(new BorderLayout(8, 0));
        javaRow.setOpaque(false);
        javaRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        javaPathField.setToolTipText("Full path to the java/java.exe binary — filled in automatically when you pick a detected runtime above");
        javaRow.add(javaPathField, BorderLayout.CENTER);
        JPanel javaBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        javaBtns.setOpaque(false);
        javaBtns.add(browseBtn);
        javaBtns.add(rescanBtn);
        javaRow.add(javaBtns, BorderLayout.EAST);
        content.add(javaRow);

        content.add(Box.createVerticalStrut(14));
        content.add(fieldLabel("JVM arguments"));
        content.add(Box.createVerticalStrut(4));
        jvmArgsField.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(jvmArgsField);
        return content;
    }

    // ── Small styling helpers ────────────────────────────────────────────────
    private static void group(JToggleButton... buttons) {
        ButtonGroup g = new ButtonGroup();
        for (JToggleButton b : buttons) g.add(b);
    }

    private static String prettyLoaderName(ModLoaderType type) {
        return switch (type) {
            case VANILLA -> "Vanilla";
            case FABRIC -> "Fabric";
            case QUILT -> "Quilt";
            case FORGE -> "Forge";
            case NEOFORGE -> "NeoForge";
        };
    }

    private JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", Font.BOLD, 11));
        l.setForeground(textDim);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JLabel pill(String text, Color accentColor) {
        JLabel lbl = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        lbl.setOpaque(false);
        lbl.setForeground(accentColor);
        lbl.setBackground(new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 30));
        lbl.setFont(new Font("SansSerif", Font.BOLD, 11));
        lbl.setBorder(new EmptyBorder(3, 9, 3, 9));
        return lbl;
    }

    /** Wraps a section's content in a titled, rounded "glass" card. */
    private JPanel sectionCard(String title, JComponent content) {
        Card card = new Card(CARD_ARC, cardFill, cardBorder);
        card.setLayout(new BorderLayout());
        card.setBorder(new EmptyBorder(14, 22, 14, 22));

        JLabel titleLbl = new JLabel(title.toUpperCase());
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 11));
        titleLbl.setForeground(textDim);
        titleLbl.setBorder(new EmptyBorder(0, 0, 12, 0));
        card.add(titleLbl, BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        return card;
    }

    /** Rounded-rect background "glass" card, self-contained (no cross-package dependency on Main). */
    private static class Card extends JPanel {
        private final int radius;
        private final Color fill;
        private final Color border;

        Card(int radius, Color fill, Color border) {
            this.radius = radius;
            this.fill = fill;
            this.border = border;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            RoundRectangle2D.Float shape = new RoundRectangle2D.Float(
                    0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, radius, radius);
            g2.setColor(fill);
            g2.fill(shape);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Clips a preview icon into a rounded square with a faint edge ring. */
    private static class RoundImageWrapper extends JPanel {
        private final JLabel inner;

        RoundImageWrapper(JLabel inner) {
            this.inner = inner;
            setOpaque(false);
            setLayout(null);
            setPreferredSize(new Dimension(64, 64));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int s = Math.min(getWidth(), getHeight());
            RoundRectangle2D.Float shape = new RoundRectangle2D.Float(0, 0, s, s, 14, 14);
            Shape oldClip = g2.getClip();
            g2.clip(shape);
            if (inner.getIcon() != null) {
                inner.getIcon().paintIcon(this, g2, 0, 0);
            } else {
                g2.setColor(new Color(255, 255, 255, 20));
                g2.fill(shape);
            }
            g2.setClip(oldClip);
            g2.setColor(new Color(255, 255, 255, 26));
            g2.draw(shape);
            g2.dispose();
        }
    }

    /** Filled, accent-colored primary action button ("Save Changes"). */
    private class PrimaryButton extends JButton {
        PrimaryButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setForeground(Color.WHITE);
            setFont(new Font("SansSerif", Font.BOLD, 13));
            setBorder(new EmptyBorder(10, 22, 10, 22));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = accent;
            if (getModel().isPressed()) fill = fill.darker();
            else if (getModel().isRollover()) {
                fill = new Color(Math.min(255, fill.getRed() + 14), Math.min(255, fill.getGreen() + 14),
                        Math.min(255, fill.getBlue() + 14));
            }
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Transparent, outlined secondary/utility button (Cancel, Browse…, etc.). */
    private class GhostButton extends JButton {
        private Color customFg = null;

        GhostButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setForeground(textColor);
            setFont(new Font("SansSerif", Font.PLAIN, 12));
            setBorder(new EmptyBorder(7, 14, 7, 14));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        public void setForeground(Color fg) {
            super.setForeground(fg);
            // Track any foreground explicitly set after construction (e.g. the
            // red "Delete Instance" button) so paintComponent doesn't clobber it.
            if (fg != null && !fg.equals(textColor) && !fg.equals(textDim)) {
                customFg = fg;
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = isEnabled()
                    ? (getModel().isRollover() ? new Color(255, 255, 255, 24) : new Color(255, 255, 255, 12))
                    : new Color(255, 255, 255, 6);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            g2.setColor(new Color(255, 255, 255, 22));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g2.dispose();
            super.setForeground(isEnabled() ? (customFg != null ? customFg : textColor) : textDim);
            super.paintComponent(g);
        }
    }

    /** Pill-shaped segmented-control button (loader picker, dir choice). */
    private class SegButton extends JToggleButton {
        SegButton(String text, boolean selected) {
            super(text, selected);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setFont(new Font("SansSerif", Font.BOLD, 11));
            setBorder(new EmptyBorder(6, 12, 6, 12));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean sel = isSelected();
            Color fill = sel ? accent : (getModel().isRollover() ? new Color(255, 255, 255, 20)
                    : new Color(255, 255, 255, 10));
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            g2.dispose();
            setForeground(sel ? Color.WHITE : textDim);
            super.paintComponent(g);
        }
    }

    // ── Filesystem helpers ────────────────────────────────────────────────
    private static void loadIcon(JLabel label, String imagePath) {
        if (imagePath != null && !imagePath.isBlank()) {
            try {
                File f = new File(imagePath);
                if (f.exists()) {
                    ImageIcon img = new ImageIcon(f.getAbsolutePath());
                    Image scaled = img.getImage().getScaledInstance(64, 64, Image.SCALE_SMOOTH);
                    label.setIcon(new ImageIcon(scaled));
                    return;
                }
            } catch (Exception ignored) {}
        }
        try {
            InputStream is = EditInstancePanel.class.getResourceAsStream("/com/launcher/logos/loader.png");
            if (is != null) {
                BufferedImage img = ImageIO.read(is);
                Image scaled = img.getScaledInstance(64, 64, Image.SCALE_SMOOTH);
                label.setIcon(new ImageIcon(scaled));
                return;
            }
        } catch (Exception ignored) {}
        label.setIcon(null);
    }
}
