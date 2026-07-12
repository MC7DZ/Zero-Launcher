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
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class CreateInstancePanel extends JPanel {

    private static final String DEFAULT_MODPACK_BASE = ".minecraft/ModPacks";

    private Consumer<Instance> onCreate;
    private Runnable onCancel;

    public CreateInstancePanel(Consumer<Instance> onCreate, Runnable onCancel) {
        this.onCreate = onCreate;
        this.onCancel = onCancel;
        initUI();
    }

    private void initUI() {
        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        Color bg = Main.hexToColor(settings.bgColor, new Color(10, 10, 15));
        Color panelBg = Main.hexToColor(settings.panelBgColor, new Color(19, 19, 26));
        Color textColor = Main.hexToColor(settings.textColor, new Color(226, 226, 234));
        Color accent = Main.hexToColor(settings.accentColor, new Color(16, 185, 129));

        setLayout(new BorderLayout());
        setBackground(panelBg);

        // Header
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        headerPanel.setBackground(panelBg);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Main.hexToColor("#282832", new Color(40, 40, 50))),
                new EmptyBorder(16, 20, 16, 20)
        ));
        JLabel titleLbl = new JLabel("New Instance");
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 18));
        titleLbl.setForeground(textColor);
        JLabel subLbl = new JLabel("Create a new Minecraft instance");
        subLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        subLbl.setForeground(Main.hexToColor("#9696a0", new Color(150, 150, 160)));
        headerPanel.add(titleLbl);
        headerPanel.add(Box.createVerticalStrut(4));
        headerPanel.add(subLbl);
        add(headerPanel, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(panelBg); // Set tab background
        tabs.setForeground(textColor); // Set tab text color

        // ═════════════════════════════════════════════════════════════════════
        //  TAB 1: General
        // ═════════════════════════════════════════════════════════════════════
        JPanel generalPanel = new JPanel(new GridBagLayout());
        generalPanel.setBackground(panelBg);
        generalPanel.setBorder(new EmptyBorder(16, 16, 16, 16));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 6, 6, 6);

        // Appearance section
        int row = 0;
        addSectionHeader(generalPanel, "Appearance", row++, gbc, textColor);

        // Icon picker row
        final String[] chosenImagePath = {null};
        JLabel iconView = new JLabel();
        iconView.setPreferredSize(new Dimension(64, 64));
        iconView.setBorder(BorderFactory.createLineBorder(Main.hexToColor("#3c3c46", new Color(60, 60, 70)), 1));
        loadDefaultIcon(iconView);

        JButton changeImageBtn = new JButton("Change Image…");
        changeImageBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        changeImageBtn.setForeground(textColor); // Dynamic text color
        JButton resetImageBtn = new JButton("Reset");
        resetImageBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        resetImageBtn.setForeground(textColor); // Dynamic text color
        resetImageBtn.setEnabled(false);

        changeImageBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Choose Instance Image");
            fc.setFileFilter(new FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "bmp"));
            int res = fc.showOpenDialog(this); // Use 'this' as parent component
            if (res == JFileChooser.APPROVE_OPTION) {
                File chosen = fc.getSelectedFile();
                try {
                    ImageIcon img = new ImageIcon(chosen.getAbsolutePath());
                    Image scaled = img.getImage().getScaledInstance(64, 64, Image.SCALE_SMOOTH);
                    iconView.setIcon(new ImageIcon(scaled));
                    chosenImagePath[0] = chosen.getAbsolutePath();
                    resetImageBtn.setEnabled(true);
                } catch (Exception ex) {}
            }
        });

        resetImageBtn.addActionListener(e -> {
            loadDefaultIcon(iconView);
            chosenImagePath[0] = null;
            resetImageBtn.setEnabled(false);
        });

        JPanel imgBtnCol = new JPanel();
        imgBtnCol.setLayout(new BoxLayout(imgBtnCol, BoxLayout.Y_AXIS));
        imgBtnCol.setBackground(panelBg);
        imgBtnCol.add(changeImageBtn);
        imgBtnCol.add(Box.createVerticalStrut(4));
        imgBtnCol.add(resetImageBtn);

        JPanel imageRowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        imageRowPanel.setBackground(panelBg);
        imageRowPanel.add(iconView);
        imageRowPanel.add(imgBtnCol);

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        generalPanel.add(fieldLabel("Icon", textColor), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0;
        generalPanel.add(imageRowPanel, gbc);
        row++;

        addSectionHeader(generalPanel, "General", row++, gbc, textColor);

        JTextField nameField = new JTextField("My Instance");
        nameField.setBackground(bg); // Dynamic background
        nameField.setForeground(textColor); // Dynamic text
        nameField.setCaretColor(textColor); // Dynamic caret
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        generalPanel.add(fieldLabel("Name", textColor), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0;
        generalPanel.add(nameField, gbc);
        row++;

        JComboBox<String> versionBox = new JComboBox<>();
        versionBox.setBackground(bg); // Dynamic background
        versionBox.setForeground(textColor); // Dynamic text
        versionBox.setEnabled(false);
        CustomToggle snapshotsCb = new CustomToggle("Include snapshots");
        snapshotsCb.setBackground(panelBg);
        snapshotsCb.setForeground(textColor);

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        generalPanel.add(fieldLabel("MC Version", textColor), gbc);
        gbc.gridx = 1; gbc.weightx = 0.6;
        generalPanel.add(versionBox, gbc);
        gbc.gridx = 2; gbc.weightx = 0.4;
        generalPanel.add(snapshotsCb, gbc);
        row++;

        JComboBox<ModLoaderType> loaderBox = new JComboBox<>(ModLoaderType.values());
        loaderBox.setBackground(bg); // Dynamic background
        loaderBox.setForeground(textColor); // Dynamic text
        loaderBox.setSelectedItem(ModLoaderType.VANILLA);
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        generalPanel.add(fieldLabel("Mod loader", textColor), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0;
        generalPanel.add(loaderBox, gbc);
        row++;

        JComboBox<String> loaderVerBox = new JComboBox<>();
        loaderVerBox.setBackground(bg); // Dynamic background
        loaderVerBox.setForeground(textColor); // Dynamic text
        loaderVerBox.setEnabled(false);
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        generalPanel.add(fieldLabel("Loader version", textColor), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0;
        generalPanel.add(loaderVerBox, gbc);
        row++;

        addSectionHeader(generalPanel, "Memory", row++, gbc, textColor);

        JSlider ramSlider = new JSlider(1024, 8192, 3072);
        ramSlider.setMajorTickSpacing(1024);
        ramSlider.setSnapToTicks(true);
        ramSlider.setBackground(panelBg);
        ramSlider.setForeground(textColor); // Set slider tick/track color
        JLabel ramLbl = new JLabel("3072 MB  (3.0 GB)");
        ramLbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
        ramLbl.setForeground(Main.hexToColor("#9696a0", new Color(150, 150, 160)));
        ramSlider.addChangeListener(e -> {
            int val = ramSlider.getValue();
            ramLbl.setText(String.format("%d MB  (%.1f GB)", val, (double) val / 1024));
        });

        JPanel ramContainer = new JPanel(new BorderLayout());
        ramContainer.setBackground(panelBg);
        ramContainer.add(ramSlider, BorderLayout.CENTER);
        ramContainer.add(ramLbl, BorderLayout.SOUTH);

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        generalPanel.add(fieldLabel("Allocated RAM", textColor), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0;
        generalPanel.add(ramContainer, gbc);
        row++;

        addSectionHeader(generalPanel, "Directory", row++, gbc, textColor);

        JRadioButton defaultDirRadio = new JRadioButton("Standard .minecraft directory");
        JRadioButton customDirRadio = new JRadioButton("Custom directory");
        defaultDirRadio.setBackground(panelBg); defaultDirRadio.setForeground(textColor);
        customDirRadio.setBackground(panelBg); customDirRadio.setForeground(textColor);
        ButtonGroup dirGroup = new ButtonGroup();
        dirGroup.add(defaultDirRadio);
        dirGroup.add(customDirRadio);
        defaultDirRadio.setSelected(true);

        JTextField customDirField = new JTextField();
        customDirField.setBackground(bg); // Dynamic background
        customDirField.setForeground(textColor); // Dynamic text
        customDirField.setCaretColor(textColor); // Dynamic caret
        customDirField.setEnabled(false);
        JButton browseBtn = new JButton("Browse…");
        browseBtn.setForeground(textColor); // Dynamic text color
        browseBtn.setEnabled(false);

        browseBtn.addActionListener(e -> {
            JFileChooser dc = new JFileChooser();
            dc.setDialogTitle("Select Directory");
            dc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int d = dc.showOpenDialog(this); // Use 'this' as parent component
            if (d == JFileChooser.APPROVE_OPTION) {
                customDirField.setText(dc.getSelectedFile().getAbsolutePath());
            }
        });

        defaultDirRadio.addActionListener(e -> {
            customDirField.setEnabled(false);
            browseBtn.setEnabled(false);
        });
        customDirRadio.addActionListener(e -> {
            customDirField.setEnabled(true);
            browseBtn.setEnabled(true);
        });

        JPanel dirFieldRow = new JPanel(new BorderLayout(8, 0));
        dirFieldRow.setBackground(panelBg);
        dirFieldRow.add(customDirField, BorderLayout.CENTER);
        dirFieldRow.add(browseBtn, BorderLayout.EAST);

        JPanel dirGroupPanel = new JPanel();
        dirGroupPanel.setLayout(new BoxLayout(dirGroupPanel, BoxLayout.Y_AXIS));
        dirGroupPanel.setBackground(panelBg);
        dirGroupPanel.add(defaultDirRadio);
        dirGroupPanel.add(customDirRadio);
        dirGroupPanel.add(Box.createVerticalStrut(4));
        dirGroupPanel.add(dirFieldRow);

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        generalPanel.add(fieldLabel("Game directory", textColor), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0;
        generalPanel.add(dirGroupPanel, gbc);
        row++;

        JScrollPane generalScroll = new JScrollPane(generalPanel);
        com.launcher.ui.SmoothScroll.install(generalScroll);
        generalScroll.setBorder(null);
        tabs.addTab("General", generalScroll);

        // ═════════════════════════════════════════════════════════════════════
        //  TAB 2: Modpack
        // ═════════════════════════════════════════════════════════════════════
        JPanel modpackPanel = new JPanel(new GridBagLayout());
        modpackPanel.setBackground(panelBg);
        modpackPanel.setBorder(new EmptyBorder(16, 16, 16, 16));
        GridBagConstraints mgbc = new GridBagConstraints();
        mgbc.fill = GridBagConstraints.HORIZONTAL;
        mgbc.insets = new Insets(6, 6, 6, 6);

        int mrow = 0;
        addSectionHeader(modpackPanel, "Modpack File", mrow++, mgbc, textColor);

        final String[] chosenModpackPath = {null};
        JLabel modpackFileHint = new JLabel("No file selected");
        modpackFileHint.setFont(new Font("SansSerif", Font.PLAIN, 11));
        modpackFileHint.setForeground(Main.hexToColor("#9696a0", new Color(150, 150, 160)));

        JButton modpackBrowseBtn = new JButton("Browse…");
        modpackBrowseBtn.setForeground(textColor); // Dynamic text color
        JButton modpackClearBtn = new JButton("Clear");
        modpackClearBtn.setForeground(textColor); // Dynamic text color
        modpackClearBtn.setEnabled(false);

        String defaultInstallPath = resolveDefaultModpackPath();
        JTextField installPathField = new JTextField(defaultInstallPath);
        installPathField.setBackground(bg); // Dynamic background
        installPathField.setForeground(textColor); // Dynamic text
        installPathField.setCaretColor(textColor); // Dynamic caret

        JLabel detectedInfoLabel = new JLabel(" ");
        detectedInfoLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        detectedInfoLabel.setForeground(accent);
        detectedInfoLabel.setVisible(false);

        modpackBrowseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Select Modpack File");
            fc.setFileFilter(new FileNameExtensionFilter("Modpack files", "mrpack"));
            int res = fc.showOpenDialog(this); // Use 'this' as parent component
            if (res == JFileChooser.APPROVE_OPTION) {
                File chosen = fc.getSelectedFile();
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
                        if (model.getIndexOf(meta.mcVersion) == -1) {
                            model.insertElementAt(meta.mcVersion, 0);
                        }
                        versionBox.setSelectedItem(meta.mcVersion);
                        versionBox.setEnabled(false);
                    }
                    if (meta.loaderType != null) {
                        loaderBox.setSelectedItem(meta.loaderType);
                        loaderBox.setEnabled(false);
                        if (meta.loaderVersion != null) {
                            DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) loaderVerBox.getModel();
                            if (model.getIndexOf(meta.loaderVersion) == -1) {
                                model.insertElementAt(meta.loaderVersion, 0);
                            }
                            loaderVerBox.setSelectedItem(meta.loaderVersion);
                            loaderVerBox.setEnabled(false);
                        }
                    }
                    StringBuilder info = new StringBuilder("Detected from modpack: ");
                    if (meta.mcVersion != null) info.append("MC ").append(meta.mcVersion);
                    if (meta.loaderType != null) {
                        info.append(" · ").append(meta.loaderType);
                        if (meta.loaderVersion != null) info.append(" ").append(meta.loaderVersion);
                    }
                    detectedInfoLabel.setText(info.toString());
                    detectedInfoLabel.setVisible(true);
                } else {
                    detectedInfoLabel.setVisible(false);
                }
            }
        });

        modpackClearBtn.addActionListener(e -> {
            chosenModpackPath[0] = null;
            modpackFileHint.setText("No file selected");
            modpackFileHint.setForeground(Main.hexToColor("#9696a0", new Color(150, 150, 160)));
            modpackClearBtn.setEnabled(false);
            installPathField.setText(defaultInstallPath);
            versionBox.setEnabled(true);
            loaderBox.setEnabled(true);
            loaderVerBox.setEnabled(true);
            detectedInfoLabel.setVisible(false);
        });

        JPanel modpackFileBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        modpackFileBtns.setBackground(panelBg);
        modpackFileBtns.add(modpackBrowseBtn);
        modpackFileBtns.add(modpackClearBtn);

        JPanel modpackFileBox = new JPanel();
        modpackFileBox.setLayout(new BoxLayout(modpackFileBox, BoxLayout.Y_AXIS));
        modpackFileBox.setBackground(panelBg);
        modpackFileBox.add(modpackFileHint);
        modpackFileBox.add(Box.createVerticalStrut(4));
        modpackFileBox.add(modpackFileBtns);
        modpackFileBox.add(Box.createVerticalStrut(4));
        modpackFileBox.add(detectedInfoLabel);

        mgbc.gridx = 0; mgbc.gridy = mrow; mgbc.gridwidth = 1; mgbc.weightx = 0;
        modpackPanel.add(fieldLabel("File (.mrpack only)", textColor), mgbc);
        mgbc.gridx = 1; mgbc.gridwidth = 1; mgbc.weightx = 1.0;
        modpackPanel.add(modpackFileBox, mgbc);
        mrow++;

        addSectionHeader(modpackPanel, "Install Location", mrow++, mgbc, textColor);

        JButton installPathBrowseBtn = new JButton("Browse…");
        installPathBrowseBtn.setForeground(textColor); // Dynamic text color
        installPathBrowseBtn.addActionListener(e -> {
            JFileChooser dc = new JFileChooser();
            dc.setDialogTitle("Select Directory");
            dc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int chosenRes = dc.showOpenDialog(this); // Use 'this' as parent component
            if (chosenRes == JFileChooser.APPROVE_OPTION) {
                installPathField.setText(dc.getSelectedFile().getAbsolutePath());
            }
        });

        JButton resetInstallPathBtn = new JButton("Reset");
        resetInstallPathBtn.setForeground(textColor); // Dynamic text color
        resetInstallPathBtn.addActionListener(e -> installPathField.setText(defaultInstallPath));

        JPanel installPathRow = new JPanel(new BorderLayout(8, 0));
        installPathRow.setBackground(panelBg);
        installPathRow.add(installPathField, BorderLayout.CENTER);
        JPanel installPathBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        installPathBtns.setBackground(panelBg);
        installPathBtns.add(installPathBrowseBtn);
        installPathBtns.add(resetInstallPathBtn);
        installPathRow.add(installPathBtns, BorderLayout.EAST);

        mgbc.gridx = 0; mgbc.gridy = mrow; mgbc.gridwidth = 1; mgbc.weightx = 0;
        modpackPanel.add(fieldLabel("Install path", textColor), mgbc);
        mgbc.gridx = 1; mgbc.gridwidth = 1; mgbc.weightx = 1.0;
        modpackPanel.add(installPathRow, mgbc);
        mrow++;

        JLabel modpackInfoLabel = new JLabel("<html>Supported formats: .mrpack (Modrinth) and modpacks.<br>The modpack will be extracted into the install path when you create the instance.</html>");
        modpackInfoLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        modpackInfoLabel.setForeground(Main.hexToColor("#9696a0", new Color(150, 150, 160)));
        mgbc.gridx = 0; mgbc.gridy = mrow; mgbc.gridwidth = 2; mgbc.weightx = 1.0;
        modpackPanel.add(modpackInfoLabel, mgbc);

        JScrollPane modpackScroll = new JScrollPane(modpackPanel);
        com.launcher.ui.SmoothScroll.install(modpackScroll);
        modpackScroll.setBorder(null);
        tabs.addTab("Modpack", modpackScroll);

        add(tabs, BorderLayout.CENTER);

        // ── Loaders ──────────────────────────────────────────────────────────
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
            loaderVerBox.removeAllItems();
            ModLoaderType loader = (ModLoaderType) loaderBox.getSelectedItem();
            String mcVer = (String) versionBox.getSelectedItem();
            if (loader == ModLoaderType.VANILLA || mcVer == null) {
                loaderVerBox.setEnabled(false);
                return;
            }
            if (loader == ModLoaderType.FORGE) {
                loaderVerBox.setEnabled(true);
                loaderVerBox.addItem("Recommended");
                loaderVerBox.addItem("Latest");
                loaderVerBox.setSelectedItem("Recommended");
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

        versionBox.addActionListener(e -> loadLoaderVers.run());
        loaderBox.addActionListener(e -> loadLoaderVers.run());
        snapshotsCb.addActionListener(e -> loadVersions.run());

        loadVersions.run();

        // Footer buttons
        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        footerPanel.setBackground(panelBg);
        footerPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Main.hexToColor("#282832", new Color(40, 40, 50))));

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> onCancel.run());
        cancelBtn.setForeground(textColor); // Dynamic text color
        footerPanel.add(cancelBtn);

        JButton createBtn = new JButton("Create Instance");
        createBtn.setBackground(accent);
        createBtn.setForeground(Color.WHITE);
        createBtn.addActionListener(e -> {
            String name = nameField.getText().trim();
            String ver = (String) versionBox.getSelectedItem();
            if (name.isEmpty() || ver == null) {
                JOptionPane.showMessageDialog(this, "Instance name and MC version are required.", "Error", JOptionPane.ERROR_MESSAGE); // Use 'this' as parent
                return;
            }
            ModLoaderType lType = (ModLoaderType) loaderBox.getSelectedItem();
            String lVer = lType == ModLoaderType.VANILLA ? null : (String) loaderVerBox.getSelectedItem();

            Instance newInstance = new Instance(name, ver, lType, lVer);
            newInstance.ramMb = ramSlider.getValue();
            newInstance.imagePath = chosenImagePath[0];

            if (chosenModpackPath[0] != null) {
                String path = installPathField.getText().trim().isEmpty() ? defaultInstallPath : installPathField.getText().trim();
                newInstance.modpackFilePath = chosenModpackPath[0];
                newInstance.modpackInstallPath = path;
                newInstance.useCustomDirectory = true;
                newInstance.customDirectoryPath = path;
            } else {
                if (defaultDirRadio.isSelected()) {
                    newInstance.useCustomDirectory = true;
                    newInstance.customDirectoryPath = LauncherPaths.getDefaultMinecraftPath().toAbsolutePath().toString();
                } else if (customDirRadio.isSelected()) {
                    newInstance.useCustomDirectory = true;
                    newInstance.customDirectoryPath = customDirField.getText().trim();
                }
            }
            onCreate.accept(newInstance);
        });
        footerPanel.add(createBtn);

        add(footerPanel, BorderLayout.SOUTH);
    }

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
            InputStream is = CreateInstancePanel.class.getResourceAsStream("/com/launcher/minecraft_image.png");
            if (is != null) {
                BufferedImage img = ImageIO.read(is);
                Image scaled = img.getScaledInstance(64, 64, Image.SCALE_SMOOTH);
                label.setIcon(new ImageIcon(scaled));
                return;
            }
        } catch (Exception ignored) {}
        label.setIcon(null);
    }

    private static void addSectionHeader(JPanel panel, String text, int row, GridBagConstraints gbc, Color textColor) {
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 3; gbc.weightx = 1.0;
        JPanel sepPanel = new JPanel(new GridBagLayout());
        sepPanel.setBackground(panel.getBackground());
        JLabel l = new JLabel(text.toUpperCase());
        l.setFont(new Font("SansSerif", Font.BOLD, 10));
        l.setForeground(Main.hexToColor("#9696a0", new Color(150, 150, 160)));
        JSeparator sep = new JSeparator();
        GridBagConstraints sgbc = new GridBagConstraints();
        sgbc.fill = GridBagConstraints.HORIZONTAL;
        sgbc.insets = new Insets(0, 8, 0, 0);
        sgbc.weightx = 1.0;
        sepPanel.add(l);
        sepPanel.add(sep, sgbc);
        panel.add(sepPanel, gbc);
    }

    private static JLabel fieldLabel(String text, Color textColor) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", Font.PLAIN, 12));
        l.setForeground(textColor);
        return l;
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
                    meta.loaderVersion = deps.get("quilt").getAsString();
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