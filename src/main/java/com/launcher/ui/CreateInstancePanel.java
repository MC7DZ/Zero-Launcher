package com.launcher.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.launcher.minecraft.FabricInstaller;
import com.launcher.minecraft.QuiltInstaller;
import com.launcher.minecraft.NeoForgeInstaller;
import com.launcher.minecraft.VersionManifestService;
import com.launcher.model.Instance;
import com.launcher.model.ModLoaderType;
import com.launcher.Main;
import com.launcher.util.JsonUtil;
import com.launcher.manager.LauncherPaths;
import com.launcher.model.LauncherSettings;
import com.launcher.manager.SettingsManager;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The "New Instance" screen, shown inline in the launcher's card-stack
 * (swapped in via CardLayout — see Main#CREATE_INSTANCE_VIEW) rather than as
 * a popup dialog. Laid out as a single scrolling column of rounded "glass"
 * cards instead of the old JTabbedPane + GridBagLayout form, to match the
 * visual language used by the Discover and Instances tabs elsewhere in the
 * app.
 */
public class CreateInstancePanel extends JPanel {

    private static final String DEFAULT_MODPACK_BASE = ".minecraft/ModPacks";
    private static final int CARD_ARC = 16;
    private static final int MAX_CONTENT_WIDTH = 1040;

    private final Consumer<Instance> onCreate;
    private final Runnable onCancel;

    // Resolved once in initUI() and shared by every card-building helper below.
    private Color bg, panelBg, textColor, textDim, accent, cardFill, cardBorder;

    public CreateInstancePanel(Consumer<Instance> onCreate, Runnable onCancel) {
        this.onCreate = onCreate;
        this.onCancel = onCancel;
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
        setOpaque(true);
        setBackground(panelBg);

        add(buildHeader(), BorderLayout.NORTH);

        // ── Shared state used across cards + the footer's Create action ────
        final String[] chosenImagePath = { null };
        final String[] chosenModpackPath = { null };
        final ModLoaderType[] selectedLoader = { ModLoaderType.VANILLA };
        final boolean[] modpackMode = { false };

        JLabel iconView = new JLabel();
        loadDefaultIcon(iconView);

        CustomTextField nameField = new CustomTextField("My Instance");
        nameField.setFont(new Font("SansSerif", Font.BOLD, 14));
        nameField.setBorder(new EmptyBorder(4, 4, 4, 10));

        CustomComboBox<String> versionBox = new CustomComboBox<>();
        versionBox.setEnabled(false);
        CustomToggle snapshotsCb = new CustomToggle("Include snapshots");

        CustomComboBox<String> loaderVerBox = new CustomComboBox<>();
        loaderVerBox.setEnabled(false);
        JLabel loaderVerLabel = fieldLabel("Loader version");

        JSlider ramSlider = new JSlider(1024, 8192, 3072);
        ramSlider.setMajorTickSpacing(1024);
        ramSlider.setSnapToTicks(true);
        ramSlider.setOpaque(false);
        ramSlider.setForeground(accent);
        JLabel ramLbl = pill("3.0 GB", accent);
        ramSlider.addChangeListener(e -> {
            int val = ramSlider.getValue();
            ramLbl.setText(String.format("%.1f GB", val / 1024.0));
        });

        // Directory mode segmented control
        JToggleButton defaultDirBtn = new SegButton("Default location", true);
        JToggleButton customDirBtn = new SegButton("Custom location", false);
        group(defaultDirBtn, customDirBtn);
        CustomTextField customDirField = new CustomTextField();
        customDirField.setEnabled(false);
        GhostButton browseDirBtn = new GhostButton("Browse…");
        browseDirBtn.setEnabled(false);
        browseDirBtn.addActionListener(e -> {
            File dir = com.launcher.util.NativeFileChooser.openDirectory(this, "Select Directory");
            if (dir != null) {
                customDirField.setText(dir.getAbsolutePath());
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

        // ── Loader segmented control ─────────────────────────────────────────
        Runnable[] loadLoaderVersHolder = new Runnable[1];
        JPanel loaderSeg = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        loaderSeg.setOpaque(false);
        java.util.List<JToggleButton> loaderBtns = new java.util.ArrayList<>();
        for (ModLoaderType type : ModLoaderType.values()) {
            JToggleButton b = new SegButton(prettyLoaderName(type), type == ModLoaderType.VANILLA);
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
        loaderVerBox.setVisible(false);
        loaderVerLabel.setVisible(false);

        // ── Modpack file card state ──────────────────────────────────────────
        String defaultInstallPath = resolveDefaultModpackPath();
        JLabel modpackFileHint = new JLabel("No file selected");
        modpackFileHint.setFont(new Font("SansSerif", Font.PLAIN, 12));
        modpackFileHint.setForeground(textDim);
        GhostButton modpackBrowseBtn = new GhostButton("Browse…");
        GhostButton modpackClearBtn = new GhostButton("Clear");
        modpackClearBtn.setEnabled(false);
        CustomTextField installPathField = new CustomTextField(defaultInstallPath);
        JLabel detectedInfoLabel = pill(" ", accent);
        detectedInfoLabel.setVisible(false);
        GhostButton installPathBrowseBtn = new GhostButton("Browse…");
        GhostButton resetInstallPathBtn = new GhostButton("Reset");

        modpackBrowseBtn.addActionListener(e -> {
            File chosen = com.launcher.util.NativeFileChooser.openFile(this, "Select Modpack File", "Modpack files", "mrpack");
            if (chosen == null) return;
            chosenModpackPath[0] = chosen.getAbsolutePath();
            modpackFileHint.setText(chosen.getName());
            modpackFileHint.setForeground(textColor);
            modpackClearBtn.setEnabled(true);

            String fname = chosen.getName();
            String suggested = fname.contains(".") ? fname.substring(0, fname.lastIndexOf('.')) : fname;
            suggested = suggested.replace('_', ' ').replace('-', ' ').trim();
            if (!suggested.isEmpty()) nameField.setText(suggested);

            String folderName = fname.contains(".") ? fname.substring(0, fname.lastIndexOf('.')) : fname;
            installPathField.setText(resolveDefaultModpackPath() + File.separator + folderName);

            ModpackMeta meta = parseModpackMetadata(chosen);
            if (meta != null) {
                if (meta.name != null && !meta.name.isEmpty()) nameField.setText(meta.name);
                if (meta.mcVersion != null) {
                    DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) versionBox.getModel();
                    if (model.getIndexOf(meta.mcVersion) == -1) model.insertElementAt(meta.mcVersion, 0);
                    versionBox.setSelectedItem(meta.mcVersion);
                    versionBox.setEnabled(false);
                }
                if (meta.loaderType != null) {
                    for (JToggleButton b : loaderBtns) {
                        if (prettyLoaderName(meta.loaderType).equals(b.getText())) b.setSelected(true);
                    }
                    selectedLoader[0] = meta.loaderType;
                    for (JToggleButton b : loaderBtns) b.setEnabled(false);
                    if (meta.loaderVersion != null) {
                        DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) loaderVerBox.getModel();
                        if (model.getIndexOf(meta.loaderVersion) == -1) model.insertElementAt(meta.loaderVersion, 0);
                        loaderVerBox.setSelectedItem(meta.loaderVersion);
                        loaderVerBox.setEnabled(false);
                        loaderVerBox.setVisible(true);
                        loaderVerLabel.setVisible(true);
                    }
                }
                StringBuilder info = new StringBuilder("Detected: ");
                if (meta.mcVersion != null) info.append("MC ").append(meta.mcVersion);
                if (meta.loaderType != null) {
                    info.append("  ·  ").append(prettyLoaderName(meta.loaderType));
                    if (meta.loaderVersion != null) info.append(" ").append(meta.loaderVersion);
                }
                detectedInfoLabel.setText(info.toString());
                detectedInfoLabel.setVisible(true);
            } else {
                detectedInfoLabel.setVisible(false);
            }
        });

        modpackClearBtn.addActionListener(e -> {
            chosenModpackPath[0] = null;
            modpackFileHint.setText("No file selected");
            modpackFileHint.setForeground(textDim);
            modpackClearBtn.setEnabled(false);
            installPathField.setText(defaultInstallPath);
            versionBox.setEnabled(true);
            for (JToggleButton b : loaderBtns) b.setEnabled(true);
            detectedInfoLabel.setVisible(false);
        });

        installPathBrowseBtn.addActionListener(e -> {
            File dir = com.launcher.util.NativeFileChooser.openDirectory(this, "Select Directory");
            if (dir != null) {
                installPathField.setText(dir.getAbsolutePath());
            }
        });
        resetInstallPathBtn.addActionListener(e -> installPathField.setText(defaultInstallPath));

        // ── Assemble: mode switch (Fresh vs Modpack) ─────────────────────────
        JPanel freshCards = new JPanel();
        freshCards.setOpaque(false);
        freshCards.setLayout(new BoxLayout(freshCards, BoxLayout.Y_AXIS));
        freshCards.add(sectionCard("Version & Loader", versionAndLoaderContent(
                versionBox, snapshotsCb, loaderSeg, loaderVerLabel, loaderVerBox)));
        freshCards.add(Box.createVerticalStrut(14));
        freshCards.add(sectionCard("Game Directory", directoryContent(
                defaultDirBtn, customDirBtn, dirFieldRow)));

        JPanel modpackCards = new JPanel();
        modpackCards.setOpaque(false);
        modpackCards.setLayout(new BoxLayout(modpackCards, BoxLayout.Y_AXIS));
        modpackCards.add(sectionCard("Modpack File", modpackFileContent(
                modpackFileHint, modpackBrowseBtn, modpackClearBtn, detectedInfoLabel)));
        modpackCards.add(Box.createVerticalStrut(14));
        modpackCards.add(sectionCard("Install Location", installLocationContent(
                installPathField, installPathBrowseBtn, resetInstallPathBtn)));

        CardLayout modeLayout = new CardLayout();
        JPanel modeSwitcher = new JPanel(modeLayout);
        modeSwitcher.setOpaque(false);
        modeSwitcher.add(freshCards, "FRESH");
        modeSwitcher.add(modpackCards, "MODPACK");

        JToggleButton freshModeBtn = new SegButton("Fresh Instance", true);
        JToggleButton modpackModeBtn = new SegButton("Import Modpack", false);
        Font sourceBtnFont = new Font("SansSerif", Font.BOLD, 13);
        Border sourceBtnBorder = new EmptyBorder(11, 22, 11, 22);
        freshModeBtn.setFont(sourceBtnFont);
        freshModeBtn.setBorder(sourceBtnBorder);
        modpackModeBtn.setFont(sourceBtnFont);
        modpackModeBtn.setBorder(sourceBtnBorder);
        group(freshModeBtn, modpackModeBtn);
        freshModeBtn.addActionListener(e -> {
            modpackMode[0] = false;
            modeLayout.show(modeSwitcher, "FRESH");
        });
        modpackModeBtn.addActionListener(e -> {
            modpackMode[0] = true;
            modeLayout.show(modeSwitcher, "MODPACK");
        });
        JPanel modeSegRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        modeSegRow.setOpaque(false);
        modeSegRow.add(freshModeBtn);
        modeSegRow.add(modpackModeBtn);

        // ── Prominent Source card at the very top ────────────────────────────
        // Built as its own horizontal banner (title + toggle row side-by-side)
        // rather than reusing the vertical sectionCard layout, so it comes out
        // wide and short instead of tall and content-width-sized.
        JPanel sourceCard = sourceBanner(modeSegRow);

        // ── Two balanced columns ──────────────────────────────────────────────
        // Left: Appearance & Name, then Performance, stacked.
        // Right: Version & Loader (or Modpack File), then Game Directory (or
        // Install Location), stacked.
        //
        // Rebuilt with plain BoxLayout on both axes instead of GridBagLayout's
        // fill/weighty machinery, and with NO manually-frozen preferredSize
        // snapshots anywhere in this method. Freezing a container's preferred
        // size (via setPreferredSize(getPreferredSize())) bakes in whatever
        // height Swing happened to compute at that exact moment — before the
        // component tree is displayable and fonts/metrics are fully resolved —
        // which is what was silently truncating the Performance / Game
        // Directory cards. Letting every container size itself naturally on
        // each real layout pass avoids that class of bug entirely.
        JPanel leftCol = new JPanel();
        leftCol.setOpaque(false);
        leftCol.setLayout(new BoxLayout(leftCol, BoxLayout.Y_AXIS));
        leftCol.add(sectionCard("Appearance & Name", identityContent(iconView, nameField, chosenImagePath)));
        leftCol.add(Box.createVerticalStrut(12));
        leftCol.add(sectionCard("Performance", ramContent(ramSlider, ramLbl)));

        JPanel rightCol = new JPanel();
        rightCol.setOpaque(false);
        rightCol.setLayout(new BoxLayout(rightCol, BoxLayout.Y_AXIS));
        rightCol.add(modeSwitcher);

        // Equal-width side-by-side columns via plain BoxLayout on the X axis.
        // Both columns get the same maximum width so BoxLayout stretches them
        // equally, and the taller of the two naturally sets the row's height —
        // no fill/weighty GridBagLayout constraints needed.
        leftCol.setAlignmentY(Component.TOP_ALIGNMENT);
        rightCol.setAlignmentY(Component.TOP_ALIGNMENT);
        JPanel mainRow = new JPanel();
        mainRow.setOpaque(false);
        mainRow.setLayout(new BoxLayout(mainRow, BoxLayout.X_AXIS));
        mainRow.add(leftCol);
        mainRow.add(Box.createHorizontalStrut(14));
        mainRow.add(rightCol);

        sourceCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        // ── Column of cards ───────────────────────────────────────────────────
        // Source now leads as a single, full-width, prominent banner card;
        // the two-column area beneath holds everything else. Width is capped
        // via setMaximumSize only (never a frozen setPreferredSize), so height
        // is always recomputed fresh from the actual current content.
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setAlignmentX(Component.CENTER_ALIGNMENT);
        column.setMaximumSize(new Dimension(MAX_CONTENT_WIDTH, Integer.MAX_VALUE));
        column.add(sourceCard);
        column.add(Box.createVerticalStrut(12));
        column.add(mainRow);

        // Center the (width-capped) column horizontally without GridBagLayout:
        // a horizontal BoxLayout row with glue on both sides does the same job
        // and keeps every container in this method on plain BoxLayout.
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

        // ── Loaders (network calls) ─────────────────────────────────────────
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
            ModLoaderType loader = selectedLoader[0];
            String mcVer = (String) versionBox.getSelectedItem();
            if (loader == ModLoaderType.VANILLA || mcVer == null) {
                loaderVerBox.setEnabled(false);
                return;
            }
            if (loader == ModLoaderType.FORGE) {
                loaderVerBox.removeAllItems();
                loaderVerBox.setEnabled(true);
                loaderVerBox.addItem("Recommended");
                loaderVerBox.addItem("Latest");
                loaderVerBox.setSelectedItem("Recommended");
                return;
            }
            loaderVerBox.setEnabled(false);
            if (loader == ModLoaderType.NEOFORGE) {
                new Thread(() -> {
                    try {
                        List<String> vers = new NeoForgeInstaller().fetchVersions(mcVer);
                        SwingUtilities.invokeLater(() -> {
                            loaderVerBox.removeAllItems();
                            for (String v : vers) loaderVerBox.addItem(v);
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
            final boolean isQuilt = loader == ModLoaderType.QUILT;
            new Thread(() -> {
                try {
                    List<String> vers = isQuilt ? new QuiltInstaller().fetchLoaderVersions(mcVer)
                                                : new FabricInstaller().fetchLoaderVersions(mcVer);
                    SwingUtilities.invokeLater(() -> {
                        loaderVerBox.removeAllItems();
                        for (String v : vers) loaderVerBox.addItem(v);
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

        // ── Footer ───────────────────────────────────────────────────────────
        JPanel footerPanel = new JPanel(new BorderLayout());
        footerPanel.setOpaque(false);
        footerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(255, 255, 255, 18)),
                new EmptyBorder(14, 24, 14, 24)));

        GhostButton cancelBtn = new GhostButton("Cancel");
        cancelBtn.addActionListener(e -> onCancel.run());
        JPanel leftFooter = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftFooter.setOpaque(false);
        leftFooter.add(cancelBtn);
        footerPanel.add(leftFooter, BorderLayout.WEST);

        PrimaryButton createBtn = new PrimaryButton("Create Instance");
        createBtn.addActionListener(e -> {
            String name = nameField.getText().trim();
            String ver = (String) versionBox.getSelectedItem();
            ModLoaderType lType = selectedLoader[0];
            String lVer = lType == ModLoaderType.VANILLA ? null : (String) loaderVerBox.getSelectedItem();
            
            boolean invalidMcVer = (ver == null || ver.contains("load") || ver.contains("Load") || ver.contains("Fail"));
            boolean invalidLoaderVer = (lType != ModLoaderType.VANILLA && (lVer == null || lVer.contains("load") || lVer.contains("Load") || lVer.contains("Fail")));
            
            if (name.isEmpty() || invalidMcVer || invalidLoaderVer) {
                JOptionPane.showMessageDialog(this, "Please choose a valid Instance name, MC version, and Loader version.", "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            Instance newInstance = new Instance(name, ver, lType, lVer);
            newInstance.ramMb = ramSlider.getValue();
            newInstance.imagePath = chosenImagePath[0];

            if (chosenModpackPath[0] != null) {
                String path = installPathField.getText().trim().isEmpty() ? defaultInstallPath
                        : installPathField.getText().trim();
                newInstance.modpackFilePath = chosenModpackPath[0];
                newInstance.modpackInstallPath = path;
                newInstance.useCustomDirectory = true;
                newInstance.customDirectoryPath = path;
            } else {
                if (defaultDirBtn.isSelected()) {
                    newInstance.useCustomDirectory = true;
                    newInstance.customDirectoryPath = LauncherPaths.getDefaultMinecraftPath().toAbsolutePath()
                            .toString();
                } else {
                    newInstance.useCustomDirectory = true;
                    newInstance.customDirectoryPath = customDirField.getText().trim();
                }
            }
            onCreate.accept(newInstance);
        });
        JPanel rightFooter = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        rightFooter.setOpaque(false);
        rightFooter.add(createBtn);
        footerPanel.add(rightFooter, BorderLayout.EAST);

        add(footerPanel, BorderLayout.SOUTH);
    }

    // ── Header ───────────────────────────────────────────────────────────────
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
        JLabel titleLbl = new JLabel("New Instance");
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 20));
        titleLbl.setForeground(textColor);
        JLabel subLbl = new JLabel("Set up a new Minecraft instance");
        subLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        subLbl.setForeground(textDim);
        titles.add(titleLbl);
        titles.add(Box.createVerticalStrut(2));
        titles.add(subLbl);
        headerPanel.add(titles, BorderLayout.CENTER);

        return headerPanel;
    }

    // ── Card content builders ────────────────────────────────────────────────
    private JPanel identityContent(JLabel iconView, CustomTextField nameField, String[] chosenImagePath) {
        iconView.setPreferredSize(new Dimension(64, 64));
        iconView.setMinimumSize(new Dimension(64, 64));

        GhostButton changeImageBtn = new GhostButton("Change Image…");
        GhostButton resetImageBtn = new GhostButton("Reset");
        resetImageBtn.setEnabled(false);

        changeImageBtn.addActionListener(e -> {
            File chosen = com.launcher.util.NativeFileChooser.openFile(this, "Choose Instance Image", "Images", "png", "jpg", "jpeg", "gif", "bmp");
            if (chosen != null) {
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
            loadDefaultIcon(iconView);
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

    private JPanel ramContent(JSlider ramSlider, JLabel ramLbl) {
        JPanel content = new JPanel(new BorderLayout(10, 0));
        content.setOpaque(false);
        JLabel ramTitle = fieldLabel("Allocated RAM");

        // Title, slider, and value pill all share one row, with the slider
        // taking up the middle space between the two labels.
        JPanel labelRow = new JPanel(new BorderLayout(10, 0));
        labelRow.setOpaque(false);
        labelRow.add(ramTitle, BorderLayout.WEST);
        labelRow.add(ramSlider, BorderLayout.CENTER);
        labelRow.add(ramLbl, BorderLayout.EAST);

        content.add(labelRow, BorderLayout.CENTER);
        return content;
    }

    private JPanel directoryContent(JToggleButton defaultDirBtn, JToggleButton customDirBtn, JPanel dirFieldRow) {
        JPanel content = new JPanel(new BorderLayout(10, 0));
        content.setOpaque(false);
        JPanel seg = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        seg.setOpaque(false);
        seg.add(defaultDirBtn);
        seg.add(customDirBtn);

        // Field + Browse… sit to the right of the segmented control, on the
        // same row, instead of dropping to a new line below it.
        content.add(seg, BorderLayout.WEST);
        content.add(dirFieldRow, BorderLayout.CENTER);
        return content;
    }

    private JPanel modpackFileContent(JLabel modpackFileHint, JButton browseBtn, JButton clearBtn,
            JLabel detectedInfoLabel) {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JLabel hint = new JLabel("Supports .mrpack (Modrinth) and CurseForge/MultiMC-style zip modpacks.");
        hint.setFont(new Font("SansSerif", Font.PLAIN, 11));
        hint.setForeground(textDim);
        content.add(hint);
        content.add(Box.createVerticalStrut(10));

        JPanel fileRow = new JPanel(new BorderLayout(10, 0));
        fileRow.setOpaque(false);
        modpackFileHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        fileRow.add(modpackFileHint, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.setOpaque(false);
        btns.add(browseBtn);
        btns.add(clearBtn);
        fileRow.add(btns, BorderLayout.EAST);
        content.add(fileRow);
        content.add(Box.createVerticalStrut(8));
        detectedInfoLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(detectedInfoLabel);
        return content;
    }

    private JPanel installLocationContent(CustomTextField installPathField, JButton browseBtn, JButton resetBtn) {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(fieldLabel("Install path"));
        content.add(Box.createVerticalStrut(6));
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(installPathField, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btns.setOpaque(false);
        btns.add(browseBtn);
        btns.add(resetBtn);
        row.add(btns, BorderLayout.EAST);
        content.add(row);
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

    /** Wide, short horizontal banner card: just the Fresh/Modpack toggle, centered. */
    private JPanel sourceBanner(JComponent toggleRow) {
        Card card = new Card(CARD_ARC, cardFill, cardBorder);
        card.setLayout(new GridBagLayout());
        card.setBorder(new EmptyBorder(16, 22, 16, 22));
        card.add(toggleRow, new GridBagConstraints());
        return card;
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

    /** Rounded-rect background/border "glass" card, self-contained (no cross-package dependency on Main). */
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
            // inner is not added as a child — its icon is painted directly (clipped)
            // below, so the label itself never renders un-clipped on top.
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

    /** Filled, accent-colored primary action button (e.g. "Create Instance"). */
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
            setForeground(isEnabled() ? textColor : textDim);
            super.paintComponent(g);
        }
    }

    /** Pill-shaped segmented-control button (loader picker, mode switch, dir choice). */
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

    // ── Filesystem / metadata helpers (unchanged behavior from the original) ─
    private static String resolveDefaultModpackPath() {
        try {
            Path home = Path.of(System.getProperty("user.home", "."));
            return home.resolve(".minecraft").resolve("ModPacks").toAbsolutePath().toString();
        } catch (Exception e) {
            return DEFAULT_MODPACK_BASE;
        }
    }

    private static void loadDefaultIcon(JLabel label) {
        try {
            InputStream is = CreateInstancePanel.class.getResourceAsStream("/com/launcher/logos/loader.png");
            if (is != null) {
                BufferedImage img = ImageIO.read(is);
                Image scaled = img.getScaledInstance(64, 64, Image.SCALE_SMOOTH);
                label.setIcon(new ImageIcon(scaled));
                return;
            }
        } catch (Exception ignored) {}
        label.setIcon(null);
    }

    private static class ModpackMeta {
        String name;
        String mcVersion;
        ModLoaderType loaderType;
        String loaderVersion;
    }

    private static ModpackMeta parseModpackMetadata(File modpackFile) {
        String fileName = modpackFile.getName().toLowerCase();
        try {
            if (fileName.endsWith(".mrpack")) {
                return parseMrpackMeta(modpackFile);
            } else if (fileName.endsWith(".zip")) {
                return parseZipMeta(modpackFile);
            }
        } catch (Exception e) {}
        return null;
    }

    private static ModpackMeta parseMrpackMeta(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry("modrinth.index.json");
            if (entry == null) return null;
            String json;
            try (InputStream is = zip.getInputStream(entry)) {
                json = new String(is.readAllBytes());
            }
            JsonObject root = JsonUtil.parse(json).getAsJsonObject();
            ModpackMeta meta = new ModpackMeta();
            if (root.has("name")) meta.name = root.get("name").getAsString();
            if (root.has("dependencies")) {
                JsonObject deps = root.getAsJsonObject("dependencies");
                if (deps.has("minecraft")) meta.mcVersion = deps.get("minecraft").getAsString();
                if (deps.has("fabric-loader")) {
                    meta.loaderType = ModLoaderType.FABRIC;
                    meta.loaderVersion = deps.get("fabric-loader").getAsString();
                } else if (deps.has("quilt-loader")) {
                    meta.loaderType = ModLoaderType.QUILT;
                    meta.loaderVersion = deps.get("quilt-loader").getAsString();
                } else if (deps.has("forge")) {
                    meta.loaderType = ModLoaderType.FORGE;
                    meta.loaderVersion = deps.get("forge").getAsString();
                } else if (deps.has("neoforge")) {
                    meta.loaderType = ModLoaderType.NEOFORGE;
                    meta.loaderVersion = deps.get("neoforge").getAsString();
                }
            }
            return meta;
        }
    }

    private static ModpackMeta parseZipMeta(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry manifestEntry = zip.getEntry("manifest.json");
            if (manifestEntry != null) {
                String json;
                try (InputStream is = zip.getInputStream(manifestEntry)) {
                    json = new String(is.readAllBytes());
                }
                JsonObject root = JsonUtil.parse(json).getAsJsonObject();
                ModpackMeta meta = new ModpackMeta();
                if (root.has("name")) meta.name = root.get("name").getAsString();
                if (root.has("minecraft")) {
                    JsonObject mc = root.getAsJsonObject("minecraft");
                    if (mc.has("version")) meta.mcVersion = mc.get("version").getAsString();
                    if (mc.has("modLoaders")) {
                        JsonArray loaders = mc.getAsJsonArray("modLoaders");
                        if (!loaders.isEmpty()) {
                            JsonObject loader = loaders.get(0).getAsJsonObject();
                            String loaderId = loader.has("id") ? loader.get("id").getAsString() : "";
                            if (loaderId.startsWith("forge-")) {
                                meta.loaderType = ModLoaderType.FORGE;
                                meta.loaderVersion = loaderId.substring("forge-".length());
                            } else if (loaderId.startsWith("fabric-")) {
                                meta.loaderType = ModLoaderType.FABRIC;
                                meta.loaderVersion = loaderId.substring("fabric-".length());
                            } else if (loaderId.startsWith("neoforge-")) {
                                meta.loaderType = ModLoaderType.NEOFORGE;
                                meta.loaderVersion = loaderId.substring("neoforge-".length());
                            } else if (loaderId.startsWith("quilt-")) {
                                meta.loaderType = ModLoaderType.QUILT;
                                meta.loaderVersion = loaderId.substring("quilt-".length());
                            }
                        }
                    }
                }
                return meta;
            }
            ZipEntry mmcEntry = zip.getEntry("mmc-pack.json");
            if (mmcEntry != null) {
                String json;
                try (InputStream is = zip.getInputStream(mmcEntry)) {
                    json = new String(is.readAllBytes());
                }
                JsonObject root = JsonUtil.parse(json).getAsJsonObject();
                ModpackMeta meta = new ModpackMeta();
                if (root.has("components")) {
                    JsonArray comps = root.getAsJsonArray("components");
                    for (var el : comps) {
                        JsonObject comp = el.getAsJsonObject();
                        String uid = comp.has("uid") ? comp.get("uid").getAsString() : "";
                        String ver = comp.has("version") ? comp.get("version").getAsString() : null;
                        if ("net.minecraft".equals(uid)) {
                            meta.mcVersion = ver;
                        } else if ("net.fabricmc.fabric-loader".equals(uid)) {
                            meta.loaderType = ModLoaderType.FABRIC;
                            meta.loaderVersion = ver;
                        } else if ("org.quiltmc.quilt-loader".equals(uid)) {
                            meta.loaderType = ModLoaderType.QUILT;
                            meta.loaderVersion = ver;
                        } else if ("net.minecraftforge".equals(uid)) {
                            meta.loaderType = ModLoaderType.FORGE;
                            meta.loaderVersion = ver;
                        } else if ("net.neoforged".equals(uid)) {
                            meta.loaderType = ModLoaderType.NEOFORGE;
                            meta.loaderVersion = ver;
                        }
                    }
                }
                return meta;
            }
            ZipEntry modrinthEntry = zip.getEntry("modrinth.index.json");
            if (modrinthEntry != null) {
                return parseMrpackMeta(file);
            }
        }
        return null;
    }
}
