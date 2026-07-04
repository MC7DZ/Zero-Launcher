package com.launcher.ui;

import com.launcher.minecraft.FabricInstaller;
import com.launcher.minecraft.VersionManifestService;
import com.launcher.model.Instance;
import com.launcher.model.ModLoaderType;
import com.launcher.Main;
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
import java.util.List;
import java.util.Optional;

public class EditInstanceDialog extends JDialog {

    private Instance result = null;

    public static Optional<Instance> show(JFrame owner, Instance inst) {
        EditInstanceDialog dialog = new EditInstanceDialog(owner, inst);
        dialog.setVisible(true);
        return Optional.ofNullable(dialog.result);
    }

    private EditInstanceDialog(JFrame owner, Instance inst) {
        super(owner, "Edit Instance — " + inst.name, true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(760, 600);
        setLocationRelativeTo(owner);

        LauncherSettings settings = SettingsManager.getInstance().getSettings();
        Color bg = Main.hexToColor(settings.bgColor, new Color(10, 10, 15));
        Color panelBg = Main.hexToColor(settings.panelBgColor, new Color(19, 19, 26));
        Color textColor = Main.hexToColor(settings.textColor, new Color(226, 226, 234));
        Color accent = Main.hexToColor(settings.accentColor, new Color(16, 185, 129));

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(panelBg);

        // Header
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        headerPanel.setBackground(panelBg);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(40, 40, 50)),
                new EmptyBorder(16, 20, 16, 20)
        ));
        JLabel titleLbl = new JLabel("Edit Instance");
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 18));
        titleLbl.setForeground(Color.WHITE);
        JLabel subLbl = new JLabel("Editing: " + inst.name);
        subLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        subLbl.setForeground(new Color(150, 150, 160));
        headerPanel.add(titleLbl);
        headerPanel.add(Box.createVerticalStrut(4));
        headerPanel.add(subLbl);
        mainPanel.add(headerPanel, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();

        // ═════════════════════════════════════════════════════════════════════
        //  TAB 1: General
        // ═════════════════════════════════════════════════════════════════════
        JPanel generalPanel = new JPanel(new GridBagLayout());
        generalPanel.setBackground(panelBg);
        generalPanel.setBorder(new EmptyBorder(16, 16, 16, 16));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 6, 6, 6);

        int row = 0;
        addSectionHeader(generalPanel, "Appearance", row++, gbc, textColor);

        final String[] chosenImagePath = {inst.imagePath};
        JLabel iconView = new JLabel();
        iconView.setPreferredSize(new Dimension(64, 64));
        iconView.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 70), 1));
        loadIcon(iconView, inst.imagePath);

        JButton changeImageBtn = new JButton("Change Image…");
        changeImageBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        JButton resetImageBtn = new JButton("Reset");
        resetImageBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        resetImageBtn.setEnabled(inst.imagePath != null);

        changeImageBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Choose Instance Image");
            fc.setFileFilter(new FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "bmp"));
            int res = fc.showOpenDialog(this);
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
            loadIcon(iconView, null);
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

        JTextField nameField = new JTextField(inst.name);
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        generalPanel.add(fieldLabel("Name", textColor), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0;
        generalPanel.add(nameField, gbc);
        row++;

        JComboBox<String> versionBox = new JComboBox<>();
        versionBox.setEnabled(false);
        JCheckBox snapshotsCb = new JCheckBox("Include snapshots");
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
        loaderBox.setSelectedItem(inst.modLoader);
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        generalPanel.add(fieldLabel("Mod loader", textColor), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0;
        generalPanel.add(loaderBox, gbc);
        row++;

        JComboBox<String> loaderVerBox = new JComboBox<>();
        loaderVerBox.setEnabled(false);
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        generalPanel.add(fieldLabel("Loader version", textColor), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0;
        generalPanel.add(loaderVerBox, gbc);
        row++;

        addSectionHeader(generalPanel, "Memory", row++, gbc, textColor);

        JSlider ramSlider = new JSlider(1024, 8192, inst.ramMb > 0 ? inst.ramMb : 3072);
        ramSlider.setMajorTickSpacing(1024);
        ramSlider.setSnapToTicks(true);
        ramSlider.setBackground(panelBg);
        JLabel ramLbl = new JLabel(String.format("%d MB  (%.1f GB)", ramSlider.getValue(), (double) ramSlider.getValue() / 1024));
        ramLbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
        ramLbl.setForeground(new Color(150, 150, 160));
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

        JTextField customDirField = new JTextField(inst.customDirectoryPath != null ? inst.customDirectoryPath : "");
        customDirField.setEnabled(false);
        JButton browseBtn = new JButton("Browse…");
        browseBtn.setEnabled(false);

        browseBtn.addActionListener(e -> {
            JFileChooser dc = new JFileChooser();
            dc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int d = dc.showOpenDialog(this);
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

        if (inst.useCustomDirectory && inst.customDirectoryPath != null &&
                !Path.of(inst.customDirectoryPath).equals(LauncherPaths.getDefaultMinecraftPath())) {
            customDirRadio.setSelected(true);
            customDirField.setEnabled(true);
            browseBtn.setEnabled(true);
        } else {
            defaultDirRadio.setSelected(true);
        }

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

        addSectionHeader(generalPanel, "Visibility", row++, gbc, textColor);
        JCheckBox hiddenCb = new JCheckBox("Hide this instance");
        hiddenCb.setBackground(panelBg);
        hiddenCb.setForeground(textColor);
        hiddenCb.setSelected(inst.hidden);

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        generalPanel.add(fieldLabel("Visibility", textColor), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0;
        generalPanel.add(hiddenCb, gbc);
        row++;

        JScrollPane generalScroll = new JScrollPane(generalPanel);
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

        final String[] chosenModpackPath = {inst.modpackFilePath};
        JLabel modpackFileHint = new JLabel(inst.modpackFilePath != null ? new File(inst.modpackFilePath).getName() : "No file selected");
        modpackFileHint.setFont(new Font("SansSerif", Font.PLAIN, 11));
        modpackFileHint.setForeground(inst.modpackFilePath != null ? textColor : new Color(150, 150, 160));

        JButton modpackBrowseBtn = new JButton("Browse…");
        JButton modpackClearBtn = new JButton("Clear");
        modpackClearBtn.setEnabled(inst.modpackFilePath != null);

        String defaultInstallPath = resolveDefaultModpackPath();
        JTextField installPathField = new JTextField(inst.modpackInstallPath != null ? inst.modpackInstallPath : defaultInstallPath);

        modpackBrowseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Select Modpack File");
            fc.setFileFilter(new FileNameExtensionFilter("Modpack files", "mrpack", "zip"));
            int res = fc.showOpenDialog(this);
            if (res == JFileChooser.APPROVE_OPTION) {
                File chosen = fc.getSelectedFile();
                chosenModpackPath[0] = chosen.getAbsolutePath();
                modpackFileHint.setText(chosen.getName());
                modpackFileHint.setForeground(textColor);
                modpackClearBtn.setEnabled(true);
            }
        });

        modpackClearBtn.addActionListener(e -> {
            chosenModpackPath[0] = null;
            modpackFileHint.setText("No file selected");
            modpackFileHint.setForeground(new Color(150, 150, 160));
            modpackClearBtn.setEnabled(false);
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

        mgbc.gridx = 0; mgbc.gridy = mrow; mgbc.gridwidth = 1; mgbc.weightx = 0;
        modpackPanel.add(fieldLabel("File (.mrpack / .zip)", textColor), mgbc);
        mgbc.gridx = 1; mgbc.gridwidth = 1; mgbc.weightx = 1.0;
        modpackPanel.add(modpackFileBox, mgbc);
        mrow++;

        addSectionHeader(modpackPanel, "Install Location", mrow++, mgbc, textColor);

        JButton installPathBrowseBtn = new JButton("Browse…");
        installPathBrowseBtn.addActionListener(e -> {
            JFileChooser dc = new JFileChooser();
            dc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int chosenRes = dc.showOpenDialog(this);
            if (chosenRes == JFileChooser.APPROVE_OPTION) {
                installPathField.setText(dc.getSelectedFile().getAbsolutePath());
            }
        });

        JButton resetInstallPathBtn = new JButton("Reset");
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

        JLabel modpackInfoLabel = new JLabel("Supported formats: .mrpack (Modrinth) and .zip modpacks.");
        modpackInfoLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        modpackInfoLabel.setForeground(new Color(150, 150, 160));
        mgbc.gridx = 0; mgbc.gridy = mrow; mgbc.gridwidth = 2; mgbc.weightx = 1.0;
        modpackPanel.add(modpackInfoLabel, mgbc);

        JScrollPane modpackScroll = new JScrollPane(modpackPanel);
        modpackScroll.setBorder(null);
        tabs.addTab("Modpack", modpackScroll);

        mainPanel.add(tabs, BorderLayout.CENTER);

        // Loaders
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
                        versionBox.setSelectedItem(inst.mcVersion);
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
                loaderVerBox.setSelectedItem(inst.modLoaderVersion != null ? inst.modLoaderVersion : "Recommended");
                return;
            }
            if (loader == ModLoaderType.NEOFORGE) {
                loaderVerBox.setEnabled(false);
                new Thread(() -> {
                    try {
                        String body = com.launcher.util.HttpUtil.getString(
                                "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge");
                        com.google.gson.JsonObject root = com.launcher.util.JsonUtil.parse(body).getAsJsonObject();
                        com.google.gson.JsonArray arr = root.getAsJsonArray("versions");
                        java.util.List<String> vers = new java.util.ArrayList<>();
                        String[] parts = mcVer.split("\\.");
                        String prefix = parts.length >= 2 ? (Integer.parseInt(parts[1]) + ".") : "";
                        for (var el : arr) { String v = el.getAsString(); if (v.startsWith(prefix)) vers.add(0, v); }
                        if (vers.isEmpty()) for (var el : arr) vers.add(0, el.getAsString());
                        SwingUtilities.invokeLater(() -> {
                            loaderVerBox.removeAllItems();
                            for (String v : vers) loaderVerBox.addItem(v);
                            loaderVerBox.setSelectedItem(inst.modLoaderVersion != null && vers.contains(inst.modLoaderVersion)
                                    ? inst.modLoaderVersion : (vers.isEmpty() ? null : vers.get(0)));
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
                    List<String> vers;
                    if (isQuilt) {
                        String body = com.launcher.util.HttpUtil.getString("https://meta.quiltmc.org/v3/versions/loader/" + mcVer);
                        com.google.gson.JsonArray arr = com.launcher.util.JsonUtil.parse(body).getAsJsonArray();
                        vers = new java.util.ArrayList<>();
                        for (var el : arr) {
                            vers.add(el.getAsJsonObject().getAsJsonObject("loader").get("version").getAsString());
                        }
                    } else {
                        vers = new FabricInstaller().fetchLoaderVersions(mcVer);
                    }
                    SwingUtilities.invokeLater(() -> {
                        loaderVerBox.removeAllItems();
                        for (String v : vers) loaderVerBox.addItem(v);
                        loaderVerBox.setSelectedItem(inst.modLoaderVersion != null && vers.contains(inst.modLoaderVersion)
                                ? inst.modLoaderVersion : (vers.isEmpty() ? null : vers.get(0)));
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
        footerPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(40, 40, 50)));

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dispose());
        footerPanel.add(cancelBtn);

        JButton saveBtn = new JButton("Save Changes");
        saveBtn.setBackground(accent);
        saveBtn.setForeground(Color.WHITE);
        saveBtn.addActionListener(e -> {
            String name = nameField.getText().trim();
            String ver = (String) versionBox.getSelectedItem();
            if (name.isEmpty() || ver == null) {
                JOptionPane.showMessageDialog(this, "Instance name and MC version are required.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            inst.name = name;
            inst.mcVersion = ver;
            inst.modLoader = (ModLoaderType) loaderBox.getSelectedItem();
            inst.modLoaderVersion = inst.modLoader == ModLoaderType.VANILLA ? null : (String) loaderVerBox.getSelectedItem();
            inst.ramMb = ramSlider.getValue();
            inst.hidden = hiddenCb.isSelected();
            inst.imagePath = chosenImagePath[0];
            inst.modpackFilePath = chosenModpackPath[0];

            if (chosenModpackPath[0] != null) {
                String path = installPathField.getText().trim().isEmpty() ? defaultInstallPath : installPathField.getText().trim();
                inst.modpackInstallPath = path;
                inst.useCustomDirectory = true;
                inst.customDirectoryPath = path;
            } else {
                inst.modpackInstallPath = null;
                if (defaultDirRadio.isSelected()) {
                    inst.useCustomDirectory = true;
                    inst.customDirectoryPath = LauncherPaths.getDefaultMinecraftPath().toAbsolutePath().toString();
                } else if (customDirRadio.isSelected()) {
                    inst.useCustomDirectory = true;
                    inst.customDirectoryPath = customDirField.getText().trim();
                } else {
                    inst.useCustomDirectory = false;
                    inst.customDirectoryPath = null;
                }
            }
            result = inst;
            dispose();
        });
        footerPanel.add(saveBtn);

        mainPanel.add(footerPanel, BorderLayout.SOUTH);
        setContentPane(mainPanel);
    }

    private static String resolveDefaultModpackPath() {
        try {
            return Path.of(System.getProperty("user.home", "."))
                       .resolve(".minecraft").resolve("ModPacks").toAbsolutePath().toString();
        } catch (Exception e) { return ".minecraft/ModPacks"; }
    }

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
            InputStream is = EditInstanceDialog.class.getResourceAsStream("/com/launcher/minecraft_image.png");
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
        l.setForeground(new Color(150, 150, 160));
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
}
