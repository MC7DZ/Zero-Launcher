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
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class CreateInstanceDialog {

    // Default modpack install base path
    private static final String DEFAULT_MODPACK_BASE = ".minecraft/ModPacks";

    public static Optional<Instance> show(Stage owner) {
        Dialog<Instance> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("New Instance");
        dialog.setHeaderText("Create a new Minecraft instance");
        dialog.getDialogPane().setPrefWidth(760);
        dialog.getDialogPane().setMinWidth(720);

        var css = CreateInstanceDialog.class.getResource("/com/launcher/styles.css");
        if (css != null) dialog.getDialogPane().getStylesheets().add(css.toExternalForm());
        com.launcher.model.LauncherSettings settings = com.launcher.manager.SettingsManager.getInstance().getSettings();
        Main.applyThemeToPane(dialog.getDialogPane(), settings);
        // Override background image so the dialog always has a solid background
        dialog.getDialogPane().setStyle(dialog.getDialogPane().getStyle()
                + "; -fx-background-image: none;");

        ButtonType createBtn = new ButtonType("Create Instance", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createBtn, ButtonType.CANCEL);

        // ─────────────────────────────────────────────────────────────────────
        //  Tab pane: "General" and "Modpack"
        // ─────────────────────────────────────────────────────────────────────
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // ═════════════════════════════════════════════════════════════════════
        //  TAB 1: General
        // ═════════════════════════════════════════════════════════════════════

        // ── Image picker ──────────────────────────────────────────────────────
        final String[] chosenImagePath = {null};  // mutable holder

        ImageView instanceIconView = new ImageView();
        instanceIconView.setFitWidth(64);
        instanceIconView.setFitHeight(64);
        instanceIconView.setPreserveRatio(true);
        instanceIconView.setStyle("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 6, 0, 0, 2);");
        loadDefaultIcon(instanceIconView);

        Button changeImageBtn = new Button("Change Image…");
        changeImageBtn.setStyle("-fx-font-size:11px; -fx-padding:4px 10px;");
        Button resetImageBtn  = new Button("Reset");
        resetImageBtn.setStyle("-fx-font-size:11px; -fx-padding:4px 10px;");
        resetImageBtn.setDisable(true);

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
                } catch (Exception ex) {
                    // ignore bad image
                }
            }
        });

        resetImageBtn.setOnAction(e -> {
            loadDefaultIcon(instanceIconView);
            chosenImagePath[0] = null;
            resetImageBtn.setDisable(true);
        });

        VBox imgBtnCol = new VBox(6, changeImageBtn, resetImageBtn);
        imgBtnCol.setAlignment(Pos.CENTER_LEFT);
        HBox imageRow = new HBox(14, instanceIconView, imgBtnCol);
        imageRow.setAlignment(Pos.CENTER_LEFT);

        // ── General fields ────────────────────────────────────────────────────
        TextField nameField = new TextField("My Instance");
        nameField.setPromptText("Instance name");

        ComboBox<String> versionBox = new ComboBox<>();
        versionBox.setMaxWidth(Double.MAX_VALUE);
        versionBox.setPromptText("Loading versions…");
        versionBox.setDisable(true);

        CheckBox snapshotsCb = new CheckBox("Include snapshots");

        ComboBox<ModLoaderType> loaderBox = new ComboBox<>();
        loaderBox.setMaxWidth(Double.MAX_VALUE);
        loaderBox.getItems().addAll(ModLoaderType.VANILLA, ModLoaderType.FABRIC, ModLoaderType.QUILT, ModLoaderType.FORGE, ModLoaderType.NEOFORGE);
        loaderBox.setValue(ModLoaderType.VANILLA);

        ComboBox<String> loaderVerBox = new ComboBox<>();
        loaderVerBox.setMaxWidth(Double.MAX_VALUE);
        loaderVerBox.setPromptText("(no loader)");
        loaderVerBox.setDisable(true);

        Slider ramSlider = new Slider(1024, 8192, 3072);
        ramSlider.setMajorTickUnit(1024);
        ramSlider.setSnapToTicks(true);
        Label ramLbl = new Label("3072 MB  (3.0 GB)");
        ramLbl.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-text-muted;");
        ramSlider.valueProperty().addListener((o, a, b) ->
            ramLbl.setText(String.format("%d MB  (%.1f GB)", b.intValue(), b.doubleValue() / 1024)));

        RadioButton defaultDirRadio = new RadioButton("Standard .minecraft directory");
        RadioButton customDirRadio  = new RadioButton("Custom directory");
        ToggleGroup dirGroup = new ToggleGroup();
        defaultDirRadio.setToggleGroup(dirGroup);
        customDirRadio.setToggleGroup(dirGroup);
        defaultDirRadio.setSelected(true);

        TextField customDirField = new TextField();
        customDirField.setPromptText("Path to directory…");
        customDirField.setDisable(true);
        Button browseBtn = new Button("Browse…");
        browseBtn.setDisable(true);
        browseBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            var d = dc.showDialog(owner);
            if (d != null) customDirField.setText(d.getAbsolutePath());
        });
        customDirRadio.selectedProperty().addListener((o, a, b) -> {
            customDirField.setDisable(!b);
            browseBtn.setDisable(!b);
        });

        // ── Background loaders ────────────────────────────────────────────────
        Runnable loadVersions = () -> {
            versionBox.setDisable(true);
            versionBox.setPromptText("Loading…");
            new Thread(() -> {
                try {
                    List<String> ids = snapshotsCb.isSelected()
                            ? new VersionManifestService().fetchAllVersionIds()
                            : new VersionManifestService().fetchReleaseVersionIds();
                    Platform.runLater(() -> {
                        versionBox.getItems().setAll(ids);
                        if (!ids.isEmpty()) versionBox.setValue(ids.get(0));
                        versionBox.setPromptText(null);
                        versionBox.setDisable(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> { versionBox.setPromptText("Failed to load (check connection)"); versionBox.setDisable(false); });
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
                loaderVerBox.setValue("Recommended"); return;
            }
            if (loader == ModLoaderType.NEOFORGE) {
                loaderVerBox.setDisable(true); loaderVerBox.setPromptText("Loading…");
                new Thread(() -> {
                    try {
                        java.util.List<String> vers = new NeoForgeInstaller().fetchVersions(mcVer);
                        Platform.runLater(() -> {
                            loaderVerBox.getItems().setAll(vers);
                            if (!vers.isEmpty()) loaderVerBox.setValue(vers.get(0));
                            loaderVerBox.setDisable(false);
                        });
                    } catch (Exception ex) {
                        Platform.runLater(() -> { loaderVerBox.setPromptText("Failed to load"); loaderVerBox.setDisable(false); });
                    }
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
                        vers = new QuiltInstaller().fetchLoaderVersions(mcVer);
                    } else {
                        vers = new FabricInstaller().fetchLoaderVersions(mcVer);
                    }
                    final java.util.List<String> fv = vers;
                    Platform.runLater(() -> { loaderVerBox.getItems().setAll(fv); if (!fv.isEmpty()) loaderVerBox.setValue(fv.get(0)); loaderVerBox.setDisable(false); });
                } catch (Exception ex) { Platform.runLater(() -> { loaderVerBox.setPromptText("Failed to load"); loaderVerBox.setDisable(false); }); }
            }, "fetch-loader-vers").start();
        };
        loaderBox.valueProperty().addListener((o, a, b) -> loadLoaderVers.run());
        versionBox.valueProperty().addListener((o, a, b) -> loadLoaderVers.run());

        // ── General tab layout ────────────────────────────────────────────────
        GridPane generalGrid = new GridPane();
        generalGrid.setHgap(12); generalGrid.setVgap(12);
        generalGrid.setPadding(new Insets(20));
        ColumnConstraints lColG = new ColumnConstraints(); lColG.setMinWidth(140); lColG.setHgrow(Priority.NEVER);
        ColumnConstraints rColG = new ColumnConstraints(); rColG.setHgrow(Priority.ALWAYS);
        ColumnConstraints xColG = new ColumnConstraints(); xColG.setMinWidth(130);
        generalGrid.getColumnConstraints().addAll(lColG, rColG, xColG);

        int r = 0;
        addSectionHeader(generalGrid, "Appearance", r++);
        generalGrid.add(fieldLabel("Icon"), 0, r);
        generalGrid.add(imageRow, 1, r++, 2, 1);

        addSectionHeader(generalGrid, "General", r++);
        generalGrid.addRow(r++, fieldLabel("Name"),           nameField,   new Label());
        generalGrid.add(fieldLabel("MC Version"), 0, r);
        generalGrid.add(versionBox, 1, r);
        generalGrid.add(snapshotsCb, 2, r++);
        generalGrid.addRow(r++, fieldLabel("Mod loader"),     loaderBox,   new Label());
        generalGrid.addRow(r++, fieldLabel("Loader version"), loaderVerBox, new Label());

        addSectionHeader(generalGrid, "Memory", r++);
        VBox ramBox = new VBox(4, ramSlider, ramLbl);
        generalGrid.addRow(r++, fieldLabel("Allocated RAM"),  ramBox,       new Label());

        addSectionHeader(generalGrid, "Directory", r++);
        generalGrid.add(fieldLabel("Game directory"), 0, r);
        generalGrid.add(defaultDirRadio, 1, r++, 2, 1);
        generalGrid.add(customDirRadio, 1, r++, 2, 1);
        HBox dirRow = new HBox(8, customDirField, browseBtn);
        HBox.setHgrow(customDirField, Priority.ALWAYS);
        generalGrid.add(dirRow, 1, r++, 2, 1);

        ScrollPane generalScroll = new ScrollPane(generalGrid);
        generalScroll.setFitToWidth(true);
        generalScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        Tab generalTab = new Tab("⚙  General", generalScroll);

        // ═════════════════════════════════════════════════════════════════════
        //  TAB 2: Modpack
        // ═════════════════════════════════════════════════════════════════════

        // Mutable holders
        final String[] chosenModpackPath = {null};

        // ── Modpack file row ──────────────────────────────────────────────────
        Label modpackFileHint = new Label("No file selected");
        modpackFileHint.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-text-muted;");
        modpackFileHint.setWrapText(true);

        Button modpackBrowseBtn = new Button("Browse…");
        Button modpackClearBtn  = new Button("Clear");
        modpackClearBtn.setDisable(true);

        // ── Install path row ──────────────────────────────────────────────────
        // Declared early so the modpack browse action can update it
        String defaultInstallPath = resolveDefaultModpackPath();
        TextField installPathField = new TextField(defaultInstallPath);
        installPathField.setPromptText(DEFAULT_MODPACK_BASE);
        HBox.setHgrow(installPathField, Priority.ALWAYS);

        // ── Detected modpack info label (shown below file selector) ─────────
        Label detectedInfoLabel = new Label("");
        detectedInfoLabel.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-accent-color; -fx-wrap-text:true;");
        detectedInfoLabel.setWrapText(true);
        detectedInfoLabel.setVisible(false);
        detectedInfoLabel.setManaged(false);

        modpackBrowseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select Modpack File");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Modpack files", "*.mrpack", "*.zip"),
                    new FileChooser.ExtensionFilter("Modrinth Modpack (*.mrpack)", "*.mrpack"),
                    new FileChooser.ExtensionFilter("ZIP Modpack (*.zip)", "*.zip"),
                    new FileChooser.ExtensionFilter("All files", "*.*")
            );
            File chosen = fc.showOpenDialog(owner);
            if (chosen != null) {
                chosenModpackPath[0] = chosen.getAbsolutePath();
                modpackFileHint.setText(chosen.getName());
                modpackFileHint.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-text-color;");
                modpackClearBtn.setDisable(false);

                // Auto-populate instance name from filename (fallback)
                String fname = chosen.getName();
                String suggested = fname.contains(".")
                        ? fname.substring(0, fname.lastIndexOf('.'))
                        : fname;
                suggested = suggested.replace('_', ' ').replace('-', ' ').trim();
                if (!suggested.isBlank()) nameField.setText(suggested);

                // Auto-update install path
                String folderName = fname.contains(".")
                        ? fname.substring(0, fname.lastIndexOf('.'))
                        : fname;
                installPathField.setText(resolveDefaultModpackPath() + java.io.File.separator + folderName);

                // ── Parse modpack metadata for version/loader auto-detection ──
                ModpackMeta meta = parseModpackMetadata(chosen);
                if (meta != null) {
                    // Override instance name if modpack has a name
                    if (meta.name != null && !meta.name.isBlank()) {
                        nameField.setText(meta.name);
                    }
                    // Set Minecraft version
                    if (meta.mcVersion != null) {
                        // Add the version if not already in the list
                        if (!versionBox.getItems().contains(meta.mcVersion)) {
                            versionBox.getItems().add(0, meta.mcVersion);
                        }
                        versionBox.setValue(meta.mcVersion);
                        versionBox.setDisable(true);
                    }
                    // Set mod loader
                    if (meta.loaderType != null) {
                        loaderBox.setValue(meta.loaderType);
                        loaderBox.setDisable(true);
                        if (meta.loaderVersion != null) {
                            if (!loaderVerBox.getItems().contains(meta.loaderVersion)) {
                                loaderVerBox.getItems().add(0, meta.loaderVersion);
                            }
                            loaderVerBox.setValue(meta.loaderVersion);
                            loaderVerBox.setDisable(true);
                        }
                    }
                    // Show detected info
                    StringBuilder info = new StringBuilder("Detected from modpack: ");
                    if (meta.mcVersion != null) info.append("MC ").append(meta.mcVersion);
                    if (meta.loaderType != null) {
                        info.append(" · ").append(meta.loaderType);
                        if (meta.loaderVersion != null) info.append(" ").append(meta.loaderVersion);
                    }
                    detectedInfoLabel.setText(info.toString());
                    detectedInfoLabel.setVisible(true);
                    detectedInfoLabel.setManaged(true);
                } else {
                    detectedInfoLabel.setVisible(false);
                    detectedInfoLabel.setManaged(false);
                }
            }
        });

        modpackClearBtn.setOnAction(e -> {
            chosenModpackPath[0] = null;
            modpackFileHint.setText("No file selected");
            modpackFileHint.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-text-muted;");
            modpackClearBtn.setDisable(true);
            installPathField.setText(defaultInstallPath);
            // Re-enable version/loader fields
            versionBox.setDisable(false);
            loaderBox.setDisable(false);
            loaderVerBox.setDisable(false);
            detectedInfoLabel.setVisible(false);
            detectedInfoLabel.setManaged(false);
        });

        HBox modpackFileBtns = new HBox(8, modpackBrowseBtn, modpackClearBtn);
        modpackFileBtns.setAlignment(Pos.CENTER_LEFT);
        VBox modpackFileBox = new VBox(6, modpackFileHint, modpackFileBtns, detectedInfoLabel);

        // ── Install path row ──────────────────────────────────────────────────
        Button installPathBrowseBtn = new Button("Browse…");
        installPathBrowseBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Choose Install Location");
            try {
                File initial = new File(installPathField.getText());
                if (initial.exists()) dc.setInitialDirectory(initial);
            } catch (Exception ignored) {}
            File chosen = dc.showDialog(owner);
            if (chosen != null) installPathField.setText(chosen.getAbsolutePath());
        });

        Button resetInstallPathBtn = new Button("Reset");
        resetInstallPathBtn.setOnAction(e -> installPathField.setText(defaultInstallPath));

        HBox installPathRow = new HBox(8, installPathField, installPathBrowseBtn, resetInstallPathBtn);
        installPathRow.setAlignment(Pos.CENTER_LEFT);

        // ── Info label ────────────────────────────────────────────────────────
        Label modpackInfoLabel = new Label(
                "Supported formats: .mrpack (Modrinth) and .zip modpacks.\n" +
                "The modpack will be extracted into the install path when you create the instance.");
        modpackInfoLabel.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-text-muted; -fx-wrap-text:true;");
        modpackInfoLabel.setWrapText(true);

        // ── Modpack tab layout ────────────────────────────────────────────────
        GridPane modpackGrid = new GridPane();
        modpackGrid.setHgap(12); modpackGrid.setVgap(12);
        modpackGrid.setPadding(new Insets(20));
        ColumnConstraints lColM = new ColumnConstraints(); lColM.setMinWidth(140); lColM.setHgrow(Priority.NEVER);
        ColumnConstraints rColM = new ColumnConstraints(); rColM.setHgrow(Priority.ALWAYS);
        modpackGrid.getColumnConstraints().addAll(lColM, rColM);

        int mr = 0;
        addSectionHeader(modpackGrid, "Modpack File", mr++, 2);
        modpackGrid.add(fieldLabel("File (.mrpack / .zip)"), 0, mr);
        modpackGrid.add(modpackFileBox, 1, mr++);

        addSectionHeader(modpackGrid, "Install Location", mr++, 2);
        modpackGrid.add(fieldLabel("Install path"), 0, mr);
        modpackGrid.add(installPathRow, 1, mr++);

        modpackGrid.add(modpackInfoLabel, 0, mr++, 2, 1);

        ScrollPane modpackScroll = new ScrollPane(modpackGrid);
        modpackScroll.setFitToWidth(true);
        modpackScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        Tab modpackTab = new Tab("📦  Modpack", modpackScroll);

        // ── Assemble tabs ─────────────────────────────────────────────────────
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
            if (bt != createBtn) return null;
            if (nameField.getText().isBlank() || versionBox.getValue() == null) return null;

            String loaderVer = loaderBox.getValue() == ModLoaderType.VANILLA ? null : loaderVerBox.getValue();
            Instance inst = new Instance(nameField.getText().trim(), versionBox.getValue(), loaderBox.getValue(), loaderVer);
            inst.ramMb = (int) ramSlider.getValue();

            // Instance image
            inst.imagePath = chosenImagePath[0]; // null → use default

            // Modpack
            if (chosenModpackPath[0] != null) {
                String installPath = installPathField.getText().isBlank()
                        ? defaultInstallPath : installPathField.getText();
                inst.modpackFilePath    = chosenModpackPath[0];
                inst.modpackInstallPath = installPath;
                // Game directory follows modpack install path automatically
                inst.useCustomDirectory  = true;
                inst.customDirectoryPath = installPath;
            } else {
                // Directory from General tab
                if (defaultDirRadio.isSelected()) {
                    inst.useCustomDirectory  = true;
                    inst.customDirectoryPath = LauncherPaths.getDefaultMinecraftPath().toAbsolutePath().toString();
                } else if (customDirRadio.isSelected()) {
                    inst.useCustomDirectory  = true;
                    inst.customDirectoryPath = customDirField.getText();
                }
            }

            return inst;
        });

        return dialog.showAndWait();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Resolve the default modpack install path: ~/.<user-home>/.minecraft/ModPacks */
    private static String resolveDefaultModpackPath() {
        try {
            Path home = Path.of(System.getProperty("user.home", "."));
            return home.resolve(".minecraft").resolve("ModPacks").toAbsolutePath().toString();
        } catch (Exception e) {
            return DEFAULT_MODPACK_BASE;
        }
    }

    /** Load the bundled default icon into an ImageView; silently falls back if not found. */
    private static void loadDefaultIcon(ImageView view) {
        try {
            InputStream is = CreateInstanceDialog.class.getResourceAsStream("/com/launcher/DefaultInstanceIcon.png");
            if (is != null) {
                view.setImage(new Image(is, 64, 64, true, true));
                return;
            }
        } catch (Exception ignored) {}
        // Fallback: programmatic placeholder (grey square with a pickaxe emoji feel)
        view.setImage(null);
    }

    private static void addSectionHeader(GridPane grid, String text, int row) {
        addSectionHeader(grid, text, row, 3);
    }

    private static void addSectionHeader(GridPane grid, String text, int row, int colspan) {
        Label l = new Label(text.toUpperCase());
        l.setStyle("-fx-font-size:10px; -fx-font-weight:bold; -fx-text-fill:-fx-text-muted; -fx-letter-spacing:1.5px; -fx-padding:8px 0 2px 0;");
        Separator sep = new Separator();
        HBox h = new HBox(8, l, sep);
        HBox.setHgrow(sep, Priority.ALWAYS);
        h.setAlignment(Pos.CENTER_LEFT);
        grid.add(h, 0, row, colspan, 1);
    }

    private static Label fieldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:12px; -fx-text-fill:-fx-text-color;");
        return l;
    }

    // ─── Modpack metadata parsing ─────────────────────────────────────────────

    /** Simple holder for detected modpack metadata. */
    private static class ModpackMeta {
        String name;
        String mcVersion;
        ModLoaderType loaderType;
        String loaderVersion;
    }

    /**
     * Parses a modpack file (.mrpack or .zip) to extract Minecraft version and mod loader info.
     * Returns null if metadata could not be parsed.
     */
    private static ModpackMeta parseModpackMetadata(File modpackFile) {
        String fileName = modpackFile.getName().toLowerCase();
        try {
            if (fileName.endsWith(".mrpack")) {
                return parseMrpackMeta(modpackFile);
            } else if (fileName.endsWith(".zip")) {
                return parseZipMeta(modpackFile);
            }
        } catch (Exception e) {
            // Silently fail — metadata detection is best-effort
        }
        return null;
    }

    /** Parses modrinth.index.json inside a .mrpack file. */
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

            // Name
            if (root.has("name")) {
                meta.name = root.get("name").getAsString();
            }

            // Dependencies: { "minecraft": "1.20.1", "fabric-loader": "0.14.21", ... }
            if (root.has("dependencies")) {
                JsonObject deps = root.getAsJsonObject("dependencies");
                if (deps.has("minecraft")) {
                    meta.mcVersion = deps.get("minecraft").getAsString();
                }
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

    /** Parses manifest.json (CurseForge format) or mmc-pack.json (MultiMC/Prism) inside a .zip. */
    private static ModpackMeta parseZipMeta(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            // Try CurseForge manifest.json
            ZipEntry manifestEntry = zip.getEntry("manifest.json");
            if (manifestEntry != null) {
                String json;
                try (InputStream is = zip.getInputStream(manifestEntry)) {
                    json = new String(is.readAllBytes());
                }
                JsonObject root = JsonUtil.parse(json).getAsJsonObject();
                ModpackMeta meta = new ModpackMeta();

                if (root.has("name")) {
                    meta.name = root.get("name").getAsString();
                }
                if (root.has("minecraft")) {
                    JsonObject mc = root.getAsJsonObject("minecraft");
                    if (mc.has("version")) {
                        meta.mcVersion = mc.get("version").getAsString();
                    }
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

            // Try MultiMC/Prism mmc-pack.json
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

            // Also try modrinth.index.json inside .zip (some packs are renamed .zip)
            ZipEntry modrinthEntry = zip.getEntry("modrinth.index.json");
            if (modrinthEntry != null) {
                // Re-use mrpack parser
                return parseMrpackMeta(file);
            }
        }
        return null;
    }
}
