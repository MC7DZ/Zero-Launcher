package com.launcher.ui;

import com.launcher.minecraft.FabricInstaller;
import com.launcher.minecraft.VersionManifestService;
import com.launcher.model.Instance;
import com.launcher.model.ModLoaderType;
import com.launcher.Main;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import com.launcher.manager.LauncherPaths;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class EditInstanceDialog {

    public static Optional<Instance> show(Stage owner, Instance inst) {
        Dialog<Instance> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Edit Instance — " + inst.name);
        dialog.setHeaderText("Editing: " + inst.name);
        dialog.getDialogPane().setPrefWidth(760);
        dialog.getDialogPane().setMinWidth(720);

        var css = EditInstanceDialog.class.getResource("/com/launcher/styles.css");
        if (css != null) dialog.getDialogPane().getStylesheets().add(css.toExternalForm());
        com.launcher.model.LauncherSettings settings = com.launcher.manager.SettingsManager.getInstance().getSettings();
        Main.applyThemeToPane(dialog.getDialogPane(), settings);
        // Override background image so the dialog always has a solid background
        dialog.getDialogPane().setStyle(dialog.getDialogPane().getStyle()
                + "; -fx-background-image: none;");

        ButtonType saveBtn = new ButtonType("Save Changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // ═════════════════════════════════════════════════════════════════════
        //  TAB 1: General
        // ═════════════════════════════════════════════════════════════════════

        // ── Image picker ──────────────────────────────────────────────────────
        final String[] chosenImagePath = {inst.imagePath};

        ImageView instanceIconView = new ImageView();
        instanceIconView.setFitWidth(64);
        instanceIconView.setFitHeight(64);
        instanceIconView.setPreserveRatio(true);
        instanceIconView.setStyle("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 6, 0, 0, 2);");
        loadIcon(instanceIconView, inst.imagePath);

        Button changeImageBtn = new Button("Change Image…");
        changeImageBtn.setStyle("-fx-font-size:11px; -fx-padding:4px 10px;");
        Button resetImageBtn  = new Button("Reset");
        resetImageBtn.setStyle("-fx-font-size:11px; -fx-padding:4px 10px;");
        resetImageBtn.setDisable(inst.imagePath == null);

        changeImageBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Choose Instance Image");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
            File chosen = fc.showOpenDialog(owner);
            if (chosen != null) {
                try {
                    Image img = new Image(chosen.toURI().toString(), 64, 64, true, true);
                    instanceIconView.setImage(img);
                    chosenImagePath[0] = chosen.getAbsolutePath();
                    resetImageBtn.setDisable(false);
                } catch (Exception ex) {}
            }
        });
        resetImageBtn.setOnAction(e -> {
            loadIcon(instanceIconView, null);
            chosenImagePath[0] = null;
            resetImageBtn.setDisable(true);
        });

        VBox imgBtnCol = new VBox(6, changeImageBtn, resetImageBtn);
        imgBtnCol.setAlignment(Pos.CENTER_LEFT);
        HBox imageRow = new HBox(14, instanceIconView, imgBtnCol);
        imageRow.setAlignment(Pos.CENTER_LEFT);

        // ── Other general fields ──────────────────────────────────────────────
        TextField nameField = new TextField(inst.name);

        ComboBox<String> versionBox = new ComboBox<>();
        versionBox.setMaxWidth(Double.MAX_VALUE);
        versionBox.setPromptText("Loading…");
        versionBox.setDisable(true);

        CheckBox snapshotsCb = new CheckBox("Include snapshots");

        ComboBox<ModLoaderType> loaderBox = new ComboBox<>();
        loaderBox.setMaxWidth(Double.MAX_VALUE);
        loaderBox.getItems().addAll(ModLoaderType.VANILLA, ModLoaderType.FABRIC, ModLoaderType.QUILT, ModLoaderType.FORGE, ModLoaderType.NEOFORGE);
        loaderBox.setValue(inst.modLoader);

        ComboBox<String> loaderVerBox = new ComboBox<>();
        loaderVerBox.setMaxWidth(Double.MAX_VALUE);
        loaderVerBox.setPromptText("(no loader)");
        loaderVerBox.setDisable(true);

        Slider ramSlider = new Slider(1024, 8192, inst.ramMb > 0 ? inst.ramMb : 3072);
        ramSlider.setMajorTickUnit(1024);
        ramSlider.setSnapToTicks(true);
        Label ramLbl = new Label(String.format("%d MB  (%.1f GB)", (int) ramSlider.getValue(), ramSlider.getValue() / 1024));
        ramLbl.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-text-muted;");
        ramSlider.valueProperty().addListener((o, a, b) ->
            ramLbl.setText(String.format("%d MB  (%.1f GB)", b.intValue(), b.doubleValue() / 1024)));

        RadioButton defaultDirRadio = new RadioButton("Standard .minecraft directory");
        RadioButton customDirRadio  = new RadioButton("Custom directory");
        ToggleGroup dirGroup = new ToggleGroup();
        defaultDirRadio.setToggleGroup(dirGroup);
        customDirRadio.setToggleGroup(dirGroup);

        TextField customDirField = new TextField(inst.customDirectoryPath != null ? inst.customDirectoryPath : "");
        customDirField.setDisable(true);
        Button browseBtn = new Button("Browse…");
        browseBtn.setDisable(true);
        browseBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            var d = dc.showDialog(owner);
            if (d != null) customDirField.setText(d.getAbsolutePath());
        });
        customDirRadio.selectedProperty().addListener((o, a, b) -> { customDirField.setDisable(!b); browseBtn.setDisable(!b); });

        // Pre-select directory mode
        if (inst.useCustomDirectory && inst.customDirectoryPath != null &&
                !Path.of(inst.customDirectoryPath).equals(LauncherPaths.getDefaultMinecraftPath())) {
            customDirRadio.setSelected(true);
        } else {
            defaultDirRadio.setSelected(true);
        }

        CheckBox hiddenCb = new CheckBox("Hide this instance");
        hiddenCb.setSelected(inst.hidden);

        // ─────────────────────────────────────────────────────────────────────
        //  Background loaders
        // ─────────────────────────────────────────────────────────────────────
        Runnable loadVersions = () -> {
            versionBox.setDisable(true); versionBox.setPromptText("Loading…");
            new Thread(() -> {
                try {
                    List<String> ids = snapshotsCb.isSelected()
                            ? new VersionManifestService().fetchAllVersionIds()
                            : new VersionManifestService().fetchReleaseVersionIds();
                    Platform.runLater(() -> {
                        versionBox.getItems().setAll(ids);
                        versionBox.setValue(inst.mcVersion);
                        versionBox.setPromptText(null);
                        versionBox.setDisable(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> { versionBox.setPromptText("Failed to load"); versionBox.setDisable(false); });
                }
            }, "fetch-versions").start();
        };
        loadVersions.run();
        snapshotsCb.setOnAction(e -> loadVersions.run());

        Runnable loadLoaderVers = () -> {
            loaderVerBox.getItems().clear();
            ModLoaderType loader = loaderBox.getValue();
            String mcVer = versionBox.getValue();
            if (loader == ModLoaderType.VANILLA || mcVer == null) {
                loaderVerBox.setDisable(true); loaderVerBox.setPromptText("(no loader)"); return;
            }
            if (loader == ModLoaderType.FORGE) {
                loaderVerBox.setDisable(false);
                loaderVerBox.getItems().addAll("Recommended", "Latest");
                loaderVerBox.setValue(inst.modLoaderVersion != null ? inst.modLoaderVersion : "Recommended"); return;
            }
            if (loader == ModLoaderType.NEOFORGE) {
                loaderVerBox.setDisable(true); loaderVerBox.setPromptText("Loading…");
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
                        Platform.runLater(() -> {
                            loaderVerBox.getItems().setAll(vers);
                            loaderVerBox.setValue(inst.modLoaderVersion != null && vers.contains(inst.modLoaderVersion)
                                    ? inst.modLoaderVersion : (vers.isEmpty() ? null : vers.get(0)));
                            loaderVerBox.setDisable(false);
                        });
                    } catch (Exception ex) { Platform.runLater(() -> { loaderVerBox.setPromptText("Failed to load"); loaderVerBox.setDisable(false); }); }
                }, "fetch-neoforge-vers").start();
                return;
            }
            // FABRIC or QUILT
            loaderVerBox.setDisable(true); loaderVerBox.setPromptText("Loading…");
            final boolean isQuilt = loader == ModLoaderType.QUILT;
            new Thread(() -> {
                try {
                    java.util.List<String> vers;
                    if (isQuilt) {
                        String body = com.launcher.util.HttpUtil.getString(
                                "https://meta.quiltmc.org/v3/versions/loader/" + mcVer);
                        com.google.gson.JsonArray arr = com.launcher.util.JsonUtil.parse(body).getAsJsonArray();
                        vers = new java.util.ArrayList<>();
                        for (var el : arr)
                            vers.add(el.getAsJsonObject().getAsJsonObject("loader").get("version").getAsString());
                    } else {
                        vers = new FabricInstaller().fetchLoaderVersions(mcVer);
                    }
                    final java.util.List<String> fv = vers;
                    Platform.runLater(() -> {
                        loaderVerBox.getItems().setAll(fv);
                        loaderVerBox.setValue(inst.modLoaderVersion != null && fv.contains(inst.modLoaderVersion)
                                ? inst.modLoaderVersion : (fv.isEmpty() ? null : fv.get(0)));
                        loaderVerBox.setDisable(false);
                    });
                } catch (Exception ex) { Platform.runLater(() -> { loaderVerBox.setPromptText("Failed to load"); loaderVerBox.setDisable(false); }); }
            }, "fetch-loader-vers").start();
        };
        loaderBox.valueProperty().addListener((o, a, b) -> loadLoaderVers.run());
        versionBox.valueProperty().addListener((o, a, b) -> loadLoaderVers.run());
        loadLoaderVers.run();

        // ─────────────────────────────────────────────────────────────────────
        //  Layout — General tab
        // ─────────────────────────────────────────────────────────────────────
        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(12);
        grid.setPadding(new Insets(20));
        ColumnConstraints lCol = new ColumnConstraints(); lCol.setMinWidth(140); lCol.setHgrow(Priority.NEVER);
        ColumnConstraints rCol = new ColumnConstraints(); rCol.setHgrow(Priority.ALWAYS);
        ColumnConstraints xCol = new ColumnConstraints(); xCol.setMinWidth(130);
        grid.getColumnConstraints().addAll(lCol, rCol, xCol);

        int r = 0;
        addSectionHeader(grid, "Appearance", r++, 3);
        grid.add(fl("Icon"), 0, r);
        grid.add(imageRow, 1, r++, 2, 1);

        addSectionHeader(grid, "General", r++, 3);
        grid.addRow(r++, fl("Name"),           nameField,    new Label());
        grid.add(fl("MC Version"), 0, r);
        grid.add(versionBox, 1, r);
        grid.add(snapshotsCb, 2, r++);
        grid.addRow(r++, fl("Mod loader"),     loaderBox,    new Label());
        grid.addRow(r++, fl("Loader version"), loaderVerBox, new Label());

        addSectionHeader(grid, "Memory", r++, 3);
        VBox ramBox = new VBox(4, ramSlider, ramLbl);
        grid.addRow(r++, fl("Allocated RAM"),  ramBox,       new Label());

        addSectionHeader(grid, "Directory", r++, 3);
        grid.add(fl("Game directory"), 0, r);
        grid.add(defaultDirRadio, 1, r++, 2, 1);
        grid.add(customDirRadio, 1, r++, 2, 1);
        HBox dirRow = new HBox(8, customDirField, browseBtn);
        HBox.setHgrow(customDirField, Priority.ALWAYS);
        grid.add(dirRow, 1, r++, 2, 1);

        addSectionHeader(grid, "Visibility", r++, 3);
        grid.add(hiddenCb, 1, r++, 2, 1);

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        Tab generalTab = new Tab("⚙  General", scroll);

        // ═════════════════════════════════════════════════════════════════════
        //  TAB 2: Modpack
        // ═════════════════════════════════════════════════════════════════════
        final String[] chosenModpackPath = {inst.modpackFilePath};

        Label modpackFileHint = new Label(inst.modpackFilePath != null
                ? new File(inst.modpackFilePath).getName() : "No file selected");
        modpackFileHint.setStyle("-fx-font-size:11px; -fx-text-fill:" +
                (inst.modpackFilePath != null ? "-fx-text-color" : "-fx-text-muted") + ";");
        modpackFileHint.setWrapText(true);

        Button modpackBrowseBtn = new Button("Browse…");
        Button modpackClearBtn  = new Button("Clear");
        modpackClearBtn.setDisable(inst.modpackFilePath == null);

        modpackBrowseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select Modpack File");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Modpack files", "*.mrpack", "*.zip"),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
            File chosen = fc.showOpenDialog(owner);
            if (chosen != null) {
                chosenModpackPath[0] = chosen.getAbsolutePath();
                modpackFileHint.setText(chosen.getName());
                modpackFileHint.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-text-color;");
                modpackClearBtn.setDisable(false);
            }
        });
        modpackClearBtn.setOnAction(e -> {
            chosenModpackPath[0] = null;
            modpackFileHint.setText("No file selected");
            modpackFileHint.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-text-muted;");
            modpackClearBtn.setDisable(true);
        });

        HBox modpackFileBtns = new HBox(8, modpackBrowseBtn, modpackClearBtn);
        VBox modpackFileBox  = new VBox(6, modpackFileHint, modpackFileBtns);

        // Install path
        String defaultInstallPath = resolveDefaultModpackPath();
        TextField installPathField = new TextField(
                inst.modpackInstallPath != null ? inst.modpackInstallPath : defaultInstallPath);
        HBox.setHgrow(installPathField, Priority.ALWAYS);

        Button installPathBrowseBtn = new Button("Browse…");
        installPathBrowseBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Choose Install Location");
            try { File f = new File(installPathField.getText()); if (f.exists()) dc.setInitialDirectory(f); } catch (Exception ignored) {}
            File chosen = dc.showDialog(owner);
            if (chosen != null) installPathField.setText(chosen.getAbsolutePath());
        });
        Button resetInstallPathBtn = new Button("Reset");
        resetInstallPathBtn.setOnAction(e -> installPathField.setText(defaultInstallPath));

        HBox installPathRow = new HBox(8, installPathField, installPathBrowseBtn, resetInstallPathBtn);
        installPathRow.setAlignment(Pos.CENTER_LEFT);

        Label modpackInfoLabel = new Label("Supported formats: .mrpack (Modrinth) and .zip modpacks.");
        modpackInfoLabel.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-text-muted;");
        modpackInfoLabel.setWrapText(true);

        GridPane modpackGrid = new GridPane();
        modpackGrid.setHgap(12); modpackGrid.setVgap(12);
        modpackGrid.setPadding(new Insets(20));
        ColumnConstraints lColM = new ColumnConstraints(); lColM.setMinWidth(140); lColM.setHgrow(Priority.NEVER);
        ColumnConstraints rColM = new ColumnConstraints(); rColM.setHgrow(Priority.ALWAYS);
        modpackGrid.getColumnConstraints().addAll(lColM, rColM);

        int mr = 0;
        addSectionHeader(modpackGrid, "Modpack File", mr++, 2);
        modpackGrid.add(fl("File (.mrpack / .zip)"), 0, mr);
        modpackGrid.add(modpackFileBox, 1, mr++);
        addSectionHeader(modpackGrid, "Install Location", mr++, 2);
        modpackGrid.add(fl("Install path"), 0, mr);
        modpackGrid.add(installPathRow, 1, mr++);
        modpackGrid.add(modpackInfoLabel, 0, mr++, 2, 1);

        ScrollPane modpackScroll = new ScrollPane(modpackGrid);
        modpackScroll.setFitToWidth(true);
        modpackScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        Tab modpackTab = new Tab("📦  Modpack", modpackScroll);
        tabs.getTabs().addAll(generalTab, modpackTab);

        dialog.getDialogPane().setContent(tabs);

        // Make the dialog window resizable and moveable (DECORATED style is the default for Dialog)
        dialog.getDialogPane().getScene().getWindow().setOnShown(ev -> {
            javafx.stage.Stage dlgStage = (javafx.stage.Stage) dialog.getDialogPane().getScene().getWindow();
            dlgStage.setResizable(true);
        });

        // ─────────────────────────────────────────────────────────────────────
        //  Result converter
        // ─────────────────────────────────────────────────────────────────────
        dialog.setResultConverter(bt -> {
            if (bt != saveBtn) return null;
            if (nameField.getText().isBlank() || versionBox.getValue() == null) return null;
            inst.name             = nameField.getText().trim();
            inst.mcVersion        = versionBox.getValue();
            inst.modLoader        = loaderBox.getValue();
            inst.modLoaderVersion = loaderBox.getValue() == ModLoaderType.VANILLA ? null : loaderVerBox.getValue();
            inst.ramMb            = (int) ramSlider.getValue();
            inst.hidden           = hiddenCb.isSelected();

            // Image
            inst.imagePath = chosenImagePath[0];

            // Modpack
            inst.modpackFilePath = chosenModpackPath[0];
            if (chosenModpackPath[0] != null) {
                String installPath = installPathField.getText().isBlank() ? defaultInstallPath : installPathField.getText();
                inst.modpackInstallPath  = installPath;
                // Game directory follows modpack install path automatically
                inst.useCustomDirectory  = true;
                inst.customDirectoryPath = installPath;
            } else {
                inst.modpackInstallPath = null;
                if (defaultDirRadio.isSelected()) {
                    inst.useCustomDirectory  = true;
                    inst.customDirectoryPath = LauncherPaths.getDefaultMinecraftPath().toAbsolutePath().toString();
                } else if (customDirRadio.isSelected()) {
                    inst.useCustomDirectory  = true;
                    inst.customDirectoryPath = customDirField.getText();
                } else {
                    inst.useCustomDirectory  = false;
                    inst.customDirectoryPath = null;
                }
            }
            return inst;
        });

        return dialog.showAndWait();
    }

    private static String resolveDefaultModpackPath() {
        try {
            return Path.of(System.getProperty("user.home", "."))
                       .resolve(".minecraft").resolve("ModPacks").toAbsolutePath().toString();
        } catch (Exception e) { return ".minecraft/ModPacks"; }
    }

    private static void loadIcon(ImageView view, String imagePath) {
        if (imagePath != null && !imagePath.isBlank()) {
            try {
                File f = new File(imagePath);
                if (f.exists()) { view.setImage(new Image(f.toURI().toString(), 64, 64, true, true)); return; }
            } catch (Exception ignored) {}
        }
        try {
            InputStream is = EditInstanceDialog.class.getResourceAsStream("/com/launcher/DefaultInstanceIcon.png");
            if (is != null) { view.setImage(new Image(is, 64, 64, true, true)); return; }
        } catch (Exception ignored) {}
        view.setImage(null);
    }

    private static void addSectionHeader(GridPane grid, String text, int row, int colspan) {
        Label l = new Label(text.toUpperCase());
        l.setStyle("-fx-font-size:10px; -fx-font-weight:bold; -fx-text-fill:-fx-text-muted; -fx-letter-spacing:1.5px; -fx-padding:8px 0 2px 0;");
        Separator sep = new Separator();
        HBox h = new HBox(8, l, sep);
        HBox.setHgrow(sep, Priority.ALWAYS);
        h.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        grid.add(h, 0, row, colspan, 1);
    }

    private static Label fl(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:12px; -fx-text-fill:-fx-text-color;");
        return l;
    }
}
