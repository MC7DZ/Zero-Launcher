package com.launcher;

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
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import com.launcher.util.JsonUtil;
import com.launcher.manager.LauncherPaths;

public class Main extends Application {

    private final AccountManager accountManager = new AccountManager();
    private final InstanceManager instanceManager = new InstanceManager();

    private Stage primaryStage;

    /**
     * Runs {@code action} while preserving the primary stage's maximized state.
     * JavaFX restores (un-maximizes) the owner window when a child Dialog or
     * FileChooser is shown; this wrapper saves and re-applies the flag so the
     * launcher stays maximized.
     */
    private void withMaximizeGuard(Runnable action) {
        boolean wasMaximized = primaryStage != null && primaryStage.isMaximized();
        action.run();
        if (wasMaximized && primaryStage != null && !primaryStage.isMaximized()) {
            primaryStage.setMaximized(true);
        }
    }
    private final java.util.concurrent.ConcurrentLinkedQueue<String> logQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean logFlushScheduled = false;
    private volatile boolean isMinimized = false;
    private long lastLogFlushWhenMinimized = 0;

    private ComboBox<Account> accountBox;
    private ListView<Instance> instanceList;
    private TextArea logArea;
    private Button playButton;
    private Button killButton;
    private Process activeProcess;

    // ─── Status bar ───────────────────────────────────────────────────────────
    private Label statusLabel;

    // ─── Dawn client ──────────────────────────────────────────────────────────
    private Label dawnStatusLabel;
    private Button installDawnButton;
    private Button deleteDawnButton;

    // Dawn client JAR is stored in the instance's mods folder
    private static final String DAWN_JAR_NAME = "dawn-standalone.jar";
    private static final String DAWN_DOWNLOAD_URL =
            "https://cdn.dawn.gg/files/standalone/libraries/dawn-standalone.jar";

    // ─── Mods tab ─────────────────────────────────────────────────────────────
    private Label modsHeaderLabel;
    private Label modsCountLabel;
    private ListView<ModEntry> modsList;
    private TextField modsSearchField;
    private Button checkUpdatesBtn;
    private Button updateAllBtn;
    private Button updateSelectedBtn;
    private Button refreshModsBtn;
    private Button deleteModBtn;
    private Button dedupeModsBtn;
    private List<ModEntry> currentModEntries = new java.util.ArrayList<>();

    // Mod icons are loaded asynchronously (Modrinth) and cached by URL so list
    // cells don't re-request the same image every time they're recycled.
    private final java.util.Map<String, Image> modIconCache = new java.util.concurrent.ConcurrentHashMap<>();
    private Image defaultModIcon;

    // ─── Transparent / blur backdrop ──────────────────────────────────────────
    // Sits behind the real UI inside sceneRoot; paints the background color/image
    // and, when enabled, a blurred backdrop so panels can be made translucent.
    private javafx.scene.layout.StackPane backdropLayer;

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        stage.setTitle("Zero Launcher");
        stage.setMinWidth(820);
        stage.setMinHeight(560);

        var iconUrl = getClass().getResource("/com/launcher/ZeroLauncherIcon.png");
        if (iconUrl != null) stage.getIcons().add(new Image(iconUrl.toExternalForm()));

        BorderPane root = new BorderPane();
        root.setTop(buildTopBar());

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // Wrap root in a StackPane so a backdrop layer can sit behind it.
        // The backdrop paints the background color/image and (when the
        // transparent/blur effect is enabled) a blurred backdrop, while
        // panels above it become translucent so it shows through.
        javafx.scene.layout.StackPane sceneRoot = new javafx.scene.layout.StackPane();
        backdropLayer = new javafx.scene.layout.StackPane();
        backdropLayer.setMouseTransparent(true);
        backdropLayer.setPickOnBounds(false);
        sceneRoot.getChildren().addAll(backdropLayer, root);

        com.launcher.model.LauncherSettings initSettings = com.launcher.manager.SettingsManager.getInstance().getSettings();
        int initW = (initSettings.launcherWidth  >= 820) ? initSettings.launcherWidth  : 960;
        int initH = (initSettings.launcherHeight >= 560) ? initSettings.launcherHeight : 660;
        Scene scene = new Scene(sceneRoot, initW, initH);

        Tab instancesTab = new Tab("⚡  Instances", buildInstanceArea(stage));
        Tab modsTab      = new Tab("📦  Mods",      buildModsArea());
        Tab settingsTab  = new Tab("⚙  Settings",  buildSettingsArea(scene));
        tabPane.getTabs().addAll(instancesTab, modsTab, settingsTab);

        root.setCenter(tabPane);
        root.setBottom(buildLogArea());

        var resourceUrl = getClass().getResource("/com/launcher/styles.css");
        if (resourceUrl != null) scene.getStylesheets().add(resourceUrl.toExternalForm());

        com.launcher.model.LauncherSettings settings = com.launcher.manager.SettingsManager.getInstance().getSettings();
        applyTheme(scene, settings);

        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            com.launcher.model.LauncherSettings s = com.launcher.manager.SettingsManager.getInstance().getSettings();
            if (s.clearSessionOnExit) {
                for (Account acc : accountManager.getAccounts()) accountManager.addOrUpdate(acc);
            }
        });
        stage.iconifiedProperty().addListener((obs, was, is) -> {
            isMinimized = is;
            if (is) {
                logFlushScheduled = false;
                long now = System.currentTimeMillis();
                if (now - lastLogFlushWhenMinimized > 30_000) { lastLogFlushWhenMinimized = now; System.gc(); }
            }
        });
        stage.show();

        refreshAccounts();
        refreshInstances();
        updateDawnStatus(instanceList.getSelectionModel().getSelectedItem());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TOP BAR — account selector + launcher branding
    // ══════════════════════════════════════════════════════════════════════════
    private VBox buildTopBar() {
        Label logo = new Label("ZERO");
        logo.setStyle("-fx-font-size:17px; -fx-font-weight:bold; -fx-text-fill:#ffffff; -fx-letter-spacing:3px;");
        Label logoSub = new Label("LAUNCHER");
        logoSub.setStyle("-fx-font-size:8px; -fx-text-fill:-fx-accent-color; -fx-letter-spacing:4px; -fx-font-weight:bold;");
        VBox brand = new VBox(0, logo, logoSub);
        brand.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        accountBox = new ComboBox<>();
        accountBox.setPrefWidth(210);
        accountBox.setPromptText("No account selected");
        accountBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Account a) {
                if (a == null) return "";
                com.launcher.model.LauncherSettings s = com.launcher.manager.SettingsManager.getInstance().getSettings();
                return (s.hideUsername ? "●●●●●" : a.username) + "  ·  Offline";
            }
            @Override public Account fromString(String s) { return null; }
        });
        accountBox.setOnAction(e -> {
            if (accountBox.getValue() != null) accountManager.setActiveAccount(accountBox.getValue());
        });

        Button addAccBtn = new Button("+ Account");
        addAccBtn.setOnAction(e -> LoginDialog.show(primaryStage, acc -> {
            accountManager.addOrUpdate(acc);
            refreshAccounts();
        }));

        Button removeAccBtn = new Button("Remove");
        removeAccBtn.setOnAction(e -> {
            Account sel = accountBox.getValue();
            if (sel != null) { accountManager.remove(sel); refreshAccounts(); }
        });

        HBox accountRow = new HBox(8, new Label("Account:"), accountBox, addAccBtn, removeAccBtn);
        accountRow.setAlignment(Pos.CENTER_LEFT);

        HBox bar = new HBox(20, brand, spacer, accountRow);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(12, 20, 12, 20));
        bar.getStyleClass().add("top-bar");

        Label note = new Label("Microsoft accounts are not supported — use the in-game account switcher instead.");
        note.setStyle("-fx-font-size:10px; -fx-text-fill:-fx-text-muted; -fx-padding:0 0 6px 20px;");

        VBox container = new VBox(bar, note);
        container.setStyle("-fx-background-color:-fx-panel-background; -fx-background-image: none; -fx-border-color:transparent transparent -fx-border-subtle transparent; -fx-border-width:0 0 1px 0;");
        return container;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  INSTANCE PANEL
    // ══════════════════════════════════════════════════════════════════════════
    private VBox buildInstanceArea(Stage stage) {
        VBox root = new VBox(12);
        root.setPadding(new Insets(16));

        // ── toolbar ──────────────────────────────────────────────────────────
        Button newBtn  = new Button("＋  New Instance");
        Button editBtn = new Button("✏  Edit");
        Button delBtn  = new Button("✕  Delete");
        delBtn.getStyleClass().add("btn-danger");

        Button openFolderBtn = new Button("📂  Open Folder");
        openFolderBtn.setTooltip(new Tooltip("Open the instance's game directory in the file explorer"));

        playButton = new Button("▶  Play");
        playButton.setId("playButton");
        killButton = new Button("■  Kill");
        killButton.setId("killButton");
        killButton.setDisable(true);

        Region tbSpacer = new Region();
        HBox.setHgrow(tbSpacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, newBtn, editBtn, delBtn, openFolderBtn, tbSpacer, killButton, playButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // ── instance list ─────────────────────────────────────────────────────
        instanceList = new ListView<>();
        VBox.setVgrow(instanceList, Priority.ALWAYS);
        instanceList.setCellFactory(lv -> new InstanceCell());

        // ── Dawn client section ───────────────────────────────────────────────
        dawnStatusLabel = new Label("Select an instance to manage Dawn Client.");
        dawnStatusLabel.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-text-muted;");

        installDawnButton = new Button("⬇  Install Dawn Client");
        installDawnButton.setDisable(true);
        installDawnButton.setTooltip(new Tooltip("Download and install the Dawn Client mod into this instance"));

        deleteDawnButton = new Button("✕  Remove Dawn Client");
        deleteDawnButton.setDisable(true);
        deleteDawnButton.getStyleClass().add("btn-danger");
        deleteDawnButton.setTooltip(new Tooltip("Remove Dawn Client from this instance"));

        HBox dawnButtons = new HBox(8, installDawnButton, deleteDawnButton);
        dawnButtons.setAlignment(Pos.CENTER_LEFT);

        VBox dawnSection = new VBox(6, dawnStatusLabel, dawnButtons);
        dawnSection.setPadding(new Insets(10, 12, 10, 12));
        dawnSection.setStyle("-fx-background-color:-fx-surface; -fx-background-radius:8px; -fx-border-color:-fx-border-subtle; -fx-border-radius:8px; -fx-border-width:1px;");

        Label dawnHeader = new Label("Dawn Client");
        dawnHeader.setStyle("-fx-font-size:13px; -fx-font-weight:bold; -fx-text-fill:-fx-accent-color;");
        dawnSection.getChildren().add(0, dawnHeader);

        // ── selection listener ────────────────────────────────────────────────
        instanceList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            updateDawnStatus(newVal);
            refreshModsView(newVal);
        });

        // ── wire toolbar buttons ──────────────────────────────────────────────
        newBtn.setOnAction(e -> withMaximizeGuard(() ->
            CreateInstanceDialog.show(stage).ifPresent(inst -> {
                instanceManager.add(inst);
                refreshInstances();
                // Extract modpack if one was selected
                if (inst.modpackFilePath != null && !inst.modpackFilePath.isBlank()) {
                    extractModpack(inst);
                }
            })
        ));

        editBtn.setOnAction(e -> {
            Instance sel = instanceList.getSelectionModel().getSelectedItem();
            if (sel == null) { log("Select an instance to edit."); return; }
            withMaximizeGuard(() ->
                EditInstanceDialog.show(stage, sel).ifPresent(upd -> {
                    instanceManager.update(upd);
                    refreshInstances();
                })
            );
        });

        delBtn.setOnAction(e -> {
            Instance sel = instanceList.getSelectionModel().getSelectedItem();
            if (sel == null) { log("Select an instance to delete."); return; }

            // Determine whether the instance lives directly inside .minecraft
            // (scanned vanilla install) vs. a subdirectory (modpack/custom path).
            Path defaultMcPath = com.launcher.manager.LauncherPaths.getDefaultMinecraftPath();
            Path gameDir2      = instanceManager.resolveGameDir(sel);
            boolean isModpack  = sel.modpackInstallPath != null && !sel.modpackInstallPath.isBlank();

            // Effective directory that "owns" this instance on disk
            Path effectiveDir  = isModpack ? java.nio.file.Path.of(sel.modpackInstallPath) : gameDir2;

            // True when the instance IS the root .minecraft folder (scanned vanilla).
            // In this case deleting the whole folder would break the user's real Minecraft,
            // so we only delete the version jar file(s) for that instance.
            final boolean isRootMinecraft;
            boolean _rootCheck;
            try {
                _rootCheck = Files.exists(effectiveDir)
                        && effectiveDir.toRealPath().equals(defaultMcPath.toRealPath());
            } catch (Exception ignored) {
                _rootCheck = effectiveDir.toAbsolutePath().equals(defaultMcPath.toAbsolutePath());
            }
            isRootMinecraft = _rootCheck;

            // Build description for the dialog so the user knows what will happen
            String deleteLabel;
            String deleteDescription;
            if (isRootMinecraft) {
                deleteLabel = "Delete Instance Jar Only";
                deleteDescription = "This instance points to your .minecraft folder.\n"
                        + "Only the version jar file(s) for \"" + sel.name + "\" will be deleted.\n"
                        + "Your saves, mods, and other versions are NOT affected.";
            } else {
                deleteLabel = "Delete Instance Files";
                deleteDescription = "All files in the instance folder will be permanently deleted:\n"
                        + effectiveDir.toAbsolutePath();
            }

            Alert alert = new Alert(Alert.AlertType.NONE);
            alert.initOwner(stage);
            alert.setTitle("Delete Instance");
            alert.setHeaderText("Delete \"" + sel.name + "\"?");
            alert.setContentText(deleteDescription);

            ButtonType removeLauncherOnly = new ButtonType("Remove from Launcher Only",
                    ButtonBar.ButtonData.LEFT);
            ButtonType deleteFiles        = new ButtonType(deleteLabel,
                    ButtonBar.ButtonData.OTHER);
            ButtonType cancel             = new ButtonType("Cancel",
                    ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(removeLauncherOnly, deleteFiles, cancel);

            // Style the dialog — NO background image so it stays readable
            var css2 = getClass().getResource("/com/launcher/styles.css");
            if (css2 != null) alert.getDialogPane().getStylesheets().add(css2.toExternalForm());
            com.launcher.model.LauncherSettings alertSettings =
                    com.launcher.manager.SettingsManager.getInstance().getSettings();
            applyThemeToPane(alert.getDialogPane(), alertSettings);
            // Force solid background regardless of user background-image setting
            alert.getDialogPane().setStyle(alert.getDialogPane().getStyle()
                    + "; -fx-background-image: none;");

            alert.showAndWait().ifPresent(choice -> {
                if (choice == cancel) return;

                if (choice == deleteFiles) {
                    try {
                        if (isRootMinecraft) {
                            // ── Safe mode: only delete the version jar(s) for this instance ──
                            // Version jars live under .minecraft/versions/<mcVersion>/<mcVersion>.jar
                            String mcVer = sel.mcVersion;
                            if (mcVer != null && !mcVer.isBlank()) {
                                Path versionFolder = defaultMcPath.resolve("versions").resolve(mcVer);
                                Path jarFile = versionFolder.resolve(mcVer + ".jar");
                                if (Files.exists(jarFile)) {
                                    Files.delete(jarFile);
                                    log("Deleted version jar: " + jarFile);
                                } else {
                                    log("No version jar found at: " + jarFile);
                                }
                            } else {
                                log("Warning: could not determine MC version to delete jar.");
                            }
                        } else {
                            // ── Full delete: remove the entire instance/modpack folder ──
                            if (Files.exists(effectiveDir)) {
                                try (var walk = Files.walk(effectiveDir)) {
                                    walk.sorted(java.util.Comparator.reverseOrder())
                                        .map(java.nio.file.Path::toFile)
                                        .forEach(java.io.File::delete);
                                }
                                log("Deleted instance files: " + effectiveDir);
                            }
                            // Also clean up launcher-internal cache dir if different
                            Path launcherInstanceDir =
                                    com.launcher.manager.LauncherPaths.defaultInstanceDir(sel.id);
                            if (Files.exists(launcherInstanceDir)
                                    && !launcherInstanceDir.toAbsolutePath()
                                                           .equals(effectiveDir.toAbsolutePath())) {
                                try (var walk2 = Files.walk(launcherInstanceDir)) {
                                    walk2.sorted(java.util.Comparator.reverseOrder())
                                         .map(java.nio.file.Path::toFile)
                                         .forEach(java.io.File::delete);
                                }
                                log("Deleted launcher instance dir: " + launcherInstanceDir);
                            }
                        }
                    } catch (Exception ex) {
                        log("Warning: could not fully delete files — " + ex.getMessage());
                    }
                }

                // In both cases, remove from the launcher list
                instanceManager.remove(sel);
                refreshInstances();
            });
        });

        openFolderBtn.setOnAction(e -> {
            Instance sel = instanceList.getSelectionModel().getSelectedItem();
            if (sel == null) { log("Select an instance first."); return; }
            Path gameDir = instanceManager.resolveGameDir(sel);
            withMaximizeGuard(() -> {
                try {
                    if (!Files.exists(gameDir)) Files.createDirectories(gameDir);
                    String os = System.getProperty("os.name", "").toLowerCase();
                    ProcessBuilder pb;
                    if (os.contains("win")) {
                        pb = new ProcessBuilder("explorer.exe", gameDir.toAbsolutePath().toString());
                    } else if (os.contains("mac")) {
                        pb = new ProcessBuilder("open", gameDir.toAbsolutePath().toString());
                    } else {
                        pb = new ProcessBuilder("xdg-open", gameDir.toAbsolutePath().toString());
                    }
                    pb.start();
                } catch (Exception ex) {
                    log("Could not open folder: " + ex.getMessage());
                }
            });
        });

        playButton.setOnAction(e -> {
            Instance sel = instanceList.getSelectionModel().getSelectedItem();
            Account acc  = accountBox.getValue();
            if (sel == null) { log("Select an instance first."); return; }
            if (acc == null) { log("Add and select an account first."); return; }
            launchInstance(sel, acc);
        });

        killButton.setOnAction(e -> {
            if (activeProcess != null && activeProcess.isAlive()) {
                log("Killing Minecraft...");
                activeProcess.destroyForcibly();
            }
        });

        // ── Dawn client buttons ───────────────────────────────────────────────
        installDawnButton.setOnAction(e -> {
            Instance sel = instanceList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            installDawn(sel);
        });

        deleteDawnButton.setOnAction(e -> {
            Instance sel = instanceList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            removeDawn(sel);
        });

        // ── empty-state hint ──────────────────────────────────────────────────
        Label empty = new Label("No instances yet — click  ＋ New Instance  to get started.");
        empty.setStyle("-fx-text-fill:-fx-text-muted; -fx-font-size:13px;");
        instanceList.setPlaceholder(empty);

        root.getChildren().addAll(toolbar, instanceList, dawnSection);
        return root;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MODS TAB
    // ══════════════════════════════════════════════════════════════════════════
    @SuppressWarnings("unchecked")
    private VBox buildModsArea() {
        VBox root = new VBox(12);
        root.setPadding(new Insets(16));

        // ── Header ────────────────────────────────────────────────────────────
        modsHeaderLabel = new Label("Select an instance to view its mods.");
        modsHeaderLabel.setStyle("-fx-font-size:14px; -fx-font-weight:bold; -fx-text-fill:-fx-text-color;");

        modsCountLabel = new Label("");
        modsCountLabel.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-text-muted;");

        VBox headerCol = new VBox(2, modsHeaderLabel, modsCountLabel);

        // ── Toolbar ───────────────────────────────────────────────────────────
        refreshModsBtn = new Button("🔃  Refresh");
        checkUpdatesBtn = new Button("🔍  Check for Updates");
        updateAllBtn = new Button("⬆  Update All");
        updateSelectedBtn = new Button("⬆  Update Selected");
        deleteModBtn = new Button("🗑  Delete Mod");
        dedupeModsBtn = new Button("✦  Remove Duplicates");
        updateAllBtn.setDisable(true);
        updateSelectedBtn.setDisable(true);
        checkUpdatesBtn.setDisable(true);
        refreshModsBtn.setDisable(true);
        deleteModBtn.setDisable(true);
        dedupeModsBtn.setDisable(true);
        deleteModBtn.getStyleClass().add("btn-danger");

        Region modsToolbarSpacer = new Region();
        HBox.setHgrow(modsToolbarSpacer, Priority.ALWAYS);
        HBox modsToolbar = new HBox(8, headerCol, modsToolbarSpacer, deleteModBtn, dedupeModsBtn, refreshModsBtn, checkUpdatesBtn, updateSelectedBtn, updateAllBtn);
        modsToolbar.setAlignment(Pos.CENTER_LEFT);

        // ── Mods list (modern card-style rows, matching the Instances tab) ────
        modsList = new ListView<>();
        modsList.setPlaceholder(new Label("No mods found. Select a modded instance."));
        modsList.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        modsList.setCellFactory(lv -> new ModCell());
        modsList.getStyleClass().add("mods-list");
        VBox.setVgrow(modsList, Priority.ALWAYS);

        // ── Wire toolbar buttons ──────────────────────────────────────────────
        refreshModsBtn.setOnAction(e -> {
            Instance sel = instanceList.getSelectionModel().getSelectedItem();
            refreshModsView(sel);
        });

        checkUpdatesBtn.setOnAction(e -> {
            Instance sel = instanceList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            checkModUpdates(sel);
        });

        updateSelectedBtn.setOnAction(e -> {
            Instance sel = instanceList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            var selectedMods = modsList.getSelectionModel().getSelectedItems();
            if (selectedMods.isEmpty()) { log("Select mod(s) to update."); return; }
            updateMods(sel, new java.util.ArrayList<>(selectedMods));
        });

        updateAllBtn.setOnAction(e -> {
            Instance sel = instanceList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            updateAllMods(sel);
        });

        // ── Enable/disable "Update Selected" based on whether any selected
        //    mod actually has an update available ─────────────────────────────
        modsList.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<ModEntry>) c -> {
                    boolean hasUpdate = modsList.getSelectionModel().getSelectedItems().stream()
                            .anyMatch(m -> "Update available".equals(m.status));
                    updateSelectedBtn.setDisable(!hasUpdate);
                    deleteModBtn.setDisable(modsList.getSelectionModel().getSelectedItems().isEmpty());
                });

        // ── Delete selected mod(s) ─────────────────────────────────────────
        deleteModBtn.setOnAction(e -> {
            Instance sel = instanceList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            var selected = new java.util.ArrayList<>(modsList.getSelectionModel().getSelectedItems());
            if (selected.isEmpty()) { log("Select mod(s) to delete."); return; }

            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.initOwner(primaryStage);
            confirm.setTitle("Delete Mod" + (selected.size() > 1 ? "s" : ""));
            confirm.setHeaderText("Delete " + selected.size() + " selected mod" + (selected.size() > 1 ? "s" : "") + "?");
            confirm.setContentText("The file(s) will be permanently deleted from the mods folder.");
            var css3 = getClass().getResource("/com/launcher/styles.css");
            if (css3 != null) confirm.getDialogPane().getStylesheets().add(css3.toExternalForm());
            applyThemeToPane(confirm.getDialogPane(),
                    com.launcher.manager.SettingsManager.getInstance().getSettings());
            confirm.getDialogPane().setStyle(confirm.getDialogPane().getStyle()
                    + "; -fx-background-image: none;");

            confirm.showAndWait().ifPresent(bt -> {
                if (bt != ButtonType.OK) return;
                int deleted = 0;
                for (ModEntry mod : selected) {
                    try {
                        Files.deleteIfExists(java.nio.file.Path.of(mod.filePath));
                        deleted++;
                    } catch (Exception ex) {
                        log("Could not delete " + mod.fileName + ": " + ex.getMessage());
                    }
                }
                log("Deleted " + deleted + " mod file(s).");
                refreshModsView(sel);
            });
        });

        // ── Remove duplicate mods (keep latest version of each mod) ──────────
        dedupeModsBtn.setOnAction(e -> {
            Instance sel = instanceList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            if (currentModEntries.isEmpty()) { log("No mods loaded."); return; }

            java.util.Map<String, java.util.List<ModEntry>> groups = new java.util.LinkedHashMap<>();
            for (ModEntry mod : currentModEntries) {
                String key = (mod.modrinthId != null && !mod.modrinthId.isBlank())
                        ? "id:" + mod.modrinthId
                        : "name:" + normalizeModBaseName(mod.fileName);
                groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(mod);
            }

            java.util.List<ModEntry> toDelete = new java.util.ArrayList<>();
            for (var entry : groups.entrySet()) {
                var group = entry.getValue();
                if (group.size() <= 1) continue;
                group.sort((a, b2) -> {
                    if (a.currentVersion == null && b2.currentVersion == null) return 0;
                    if (a.currentVersion == null) return 1;
                    if (b2.currentVersion == null) return -1;
                    return compareVersionStrings(b2.currentVersion, a.currentVersion);
                });
                toDelete.addAll(group.subList(1, group.size()));
            }

            if (toDelete.isEmpty()) { log("No duplicate mods found."); return; }

            StringBuilder sb = new StringBuilder();
            for (ModEntry m : toDelete) sb.append("  \u2022 ").append(m.fileName).append("\n");

            Alert confirm2 = new Alert(Alert.AlertType.CONFIRMATION);
            confirm2.initOwner(primaryStage);
            confirm2.setTitle("Remove Duplicates");
            confirm2.setHeaderText("Remove " + toDelete.size() + " duplicate mod file(s)?");
            confirm2.setContentText("Older duplicates to delete:\n" + sb);
            var css4 = getClass().getResource("/com/launcher/styles.css");
            if (css4 != null) confirm2.getDialogPane().getStylesheets().add(css4.toExternalForm());
            applyThemeToPane(confirm2.getDialogPane(),
                    com.launcher.manager.SettingsManager.getInstance().getSettings());
            confirm2.getDialogPane().setStyle(confirm2.getDialogPane().getStyle()
                    + "; -fx-background-image: none;");

            confirm2.showAndWait().ifPresent(bt -> {
                if (bt != ButtonType.OK) return;
                int removed = 0;
                for (ModEntry mod : toDelete) {
                    try {
                        Files.deleteIfExists(java.nio.file.Path.of(mod.filePath));
                        removed++;
                    } catch (Exception ex) {
                        log("Could not delete " + mod.fileName + ": " + ex.getMessage());
                    }
                }
                log("Removed " + removed + " duplicate mod file(s).");
                refreshModsView(sel);
            });
        });

        // ── Search bar ────────────────────────────────────────────────────────
        modsSearchField = new TextField();
        modsSearchField.setPromptText("🔎  Search mods by name…");
        modsSearchField.getStyleClass().add("search-field");
        HBox.setHgrow(modsSearchField, Priority.ALWAYS);

        Button clearSearchBtn = new Button("✕");
        clearSearchBtn.setTooltip(new Tooltip("Clear search"));
        clearSearchBtn.setStyle("-fx-padding:4px 8px; -fx-font-size:11px;");
        clearSearchBtn.setVisible(false);

        modsSearchField.textProperty().addListener((obs, oldV, newV) -> {
            clearSearchBtn.setVisible(newV != null && !newV.isBlank());
            applyModsFilter(newV);
        });
        clearSearchBtn.setOnAction(e -> modsSearchField.clear());

        HBox searchRow = new HBox(6, modsSearchField, clearSearchBtn);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        root.getChildren().addAll(modsToolbar, searchRow, modsList);
        return root;
    }

    /** Strips version suffixes from a mod filename to get a comparable base name. */
    private String normalizeModBaseName(String fileName) {
        if (fileName == null) return "";
        String n = fileName.replaceAll("(?i)\\.jar$", "");
        n = n.replaceAll("(?i)[-_](mc|fabric|forge|quilt|neoforge)[\\d._-]*", "");
        n = n.replaceAll("[-_]\\d+[\\d._]*.*$", "");
        return n.toLowerCase();
    }

    /** Simple best-effort semantic version comparator. */
    private int compareVersionStrings(String a, String b) {
        String[] pa = a.split("[.\\-]");
        String[] pb = b.split("[.\\-]");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            String sa = i < pa.length ? pa[i].replaceAll("[^0-9]", "") : "0";
            String sb2 = i < pb.length ? pb[i].replaceAll("[^0-9]", "") : "0";
            if (sa.isEmpty()) sa = "0";
            if (sb2.isEmpty()) sb2 = "0";
            try {
                int diff = Integer.compare(Integer.parseInt(sa), Integer.parseInt(sb2));
                if (diff != 0) return diff;
            } catch (NumberFormatException ignored) {
                int diff = sa.compareTo(sb2);
                if (diff != 0) return diff;
            }
        }
        return 0;
    }

    /** Card-style cell for the Mods list — icon, name, file/version, and a status badge. */
    private class ModCell extends ListCell<ModEntry> {
        private final javafx.scene.image.ImageView iconView = new javafx.scene.image.ImageView();
        private final Label nameLabel = new Label();
        private final Label fileLabel = new Label();
        private final Label versionLabel = new Label();
        private final Label sizeLabel = new Label();
        private final Label statusBadge = new Label();
        private final HBox card;

        ModCell() {
            iconView.setFitWidth(36);
            iconView.setFitHeight(36);
            iconView.setPreserveRatio(true);
            iconView.setSmooth(true);
            javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(36, 36);
            clip.setArcWidth(9);
            clip.setArcHeight(9);
            iconView.setClip(clip);

            nameLabel.getStyleClass().add("mod-name");
            fileLabel.getStyleClass().add("mod-file");
            versionLabel.getStyleClass().add("mod-version");
            sizeLabel.getStyleClass().add("mod-file");

            HBox metaRow = new HBox(8, fileLabel, versionLabel);
            metaRow.setAlignment(Pos.CENTER_LEFT);

            VBox textCol = new VBox(4, nameLabel, metaRow);
            textCol.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(textCol, Priority.ALWAYS);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            VBox rightCol = new VBox(6, statusBadge, sizeLabel);
            rightCol.setAlignment(Pos.CENTER_RIGHT);

            card = new HBox(12, iconView, textCol, spacer, rightCol);
            card.setAlignment(Pos.CENTER_LEFT);
            card.getStyleClass().add("mod-card");

            setText(null);
            setStyle("-fx-padding:0; -fx-background-color:transparent;");
        }

        @Override
        protected void updateItem(ModEntry mod, boolean empty) {
            super.updateItem(mod, empty);
            if (empty || mod == null) {
                setGraphic(null);
                return;
            }

            nameLabel.setText(mod.displayName());
            fileLabel.setText(mod.fileName);
            versionLabel.setText(mod.currentVersion != null && !mod.currentVersion.isBlank()
                    ? "v" + mod.currentVersion : "");
            sizeLabel.setText(mod.formattedSize());

            String status = mod.status != null ? mod.status : "Unknown";
            statusBadge.setText(status);
            statusBadge.getStyleClass().removeAll(
                    "mod-status-uptodate", "mod-status-update", "mod-status-unknown",
                    "mod-status-checking", "mod-status-error");
            statusBadge.getStyleClass().add(switch (status) {
                case "Up to date" -> "mod-status-uptodate";
                case "Update available" -> "mod-status-update";
                case "Unknown" -> "mod-status-unknown";
                case "Error" -> "mod-status-error";
                default -> "mod-status-checking";
            });

            setModIcon(iconView, mod);
            setGraphic(card);
        }

        @Override
        public void updateSelected(boolean selected) {
            super.updateSelected(selected);
            card.getStyleClass().remove("mod-card-selected");
            if (selected) card.getStyleClass().add("mod-card-selected");
        }
    }

    /** Resolves and caches a mod's icon (Modrinth icon if identified, otherwise a default). */
    private void setModIcon(javafx.scene.image.ImageView view, ModEntry mod) {
        if (mod.iconUrl != null && !mod.iconUrl.isBlank()) {
            Image img = modIconCache.computeIfAbsent(mod.iconUrl,
                    url -> new Image(url, 72, 72, true, true, true));
            view.setImage(img);
        } else {
            view.setImage(getDefaultModIcon());
        }
    }

    private Image getDefaultModIcon() {
        if (defaultModIcon == null) {
            try {
                java.io.InputStream is = getClass().getResourceAsStream("/com/launcher/DefaultInstanceIcon.png");
                if (is != null) defaultModIcon = new Image(is, 72, 72, true, true);
            } catch (Exception ignored) {}
        }
        return defaultModIcon;
    }

    /** Filters the mods list to entries whose name contains the query (case-insensitive). */
    private void applyModsFilter(String query) {
        if (currentModEntries == null || currentModEntries.isEmpty()) return;
        if (query == null || query.isBlank()) {
            modsList.getItems().setAll(currentModEntries);
        } else {
            String q = query.trim().toLowerCase();
            java.util.List<ModEntry> filtered = currentModEntries.stream()
                    .filter(m -> {
                        String dn = m.displayName();
                        if (dn       != null && dn.toLowerCase().contains(q))          return true;
                        if (m.fileName != null && m.fileName.toLowerCase().contains(q)) return true;
                        return false;
                    })
                    .collect(java.util.stream.Collectors.toList());
            modsList.getItems().setAll(filtered);
        }
        modsCountLabel.setText(modsList.getItems().size() + " / " + currentModEntries.size() + " mod(s)");
    }

    private void refreshModsView(Instance instance) {
        if (instance == null) {
            modsHeaderLabel.setText("Select an instance to view its mods.");
            modsCountLabel.setText("");
            modsList.getItems().clear();
            currentModEntries.clear();
            checkUpdatesBtn.setDisable(true);
            refreshModsBtn.setDisable(true);
            updateAllBtn.setDisable(true);
            updateSelectedBtn.setDisable(true);
            deleteModBtn.setDisable(true);
            dedupeModsBtn.setDisable(true);
            return;
        }

        modsHeaderLabel.setText("Mods for: " + instance.name);
        refreshModsBtn.setDisable(false);

        Path modsDir = instanceManager.resolveGameDir(instance).resolve("mods");
        try {
            ModUpdateService service = new ModUpdateService();
            currentModEntries = service.scanModsDir(modsDir);
            applyModsFilter(modsSearchField != null ? modsSearchField.getText() : null);
            modsCountLabel.setText(currentModEntries.size() + " mod(s) found");
            checkUpdatesBtn.setDisable(currentModEntries.isEmpty());
            dedupeModsBtn.setDisable(currentModEntries.isEmpty());
            updateAllBtn.setDisable(true);
            updateSelectedBtn.setDisable(true);
            deleteModBtn.setDisable(true);
            log("Found " + currentModEntries.size() + " mod(s) in " + modsDir);
        } catch (Exception ex) {
            log("Failed to scan mods directory: " + ex.getMessage());
            modsList.getItems().clear();
            modsCountLabel.setText("");
            currentModEntries.clear();
        }
    }

    private void checkModUpdates(Instance instance) {
        checkUpdatesBtn.setDisable(true);
        setStatus("Checking for mod updates…");

        new Thread(() -> {
            try {
                ModUpdateService service = new ModUpdateService();

                // Step 1: Identify mods on Modrinth
                log("Identifying mods on Modrinth…");
                service.identifyMods(currentModEntries, this::log);
                Platform.runLater(() -> modsList.refresh());

                // Step 2: Check for updates
                String loaderName = instance.modLoader != null && instance.modLoader != ModLoaderType.VANILLA
                        ? instance.modLoader.name().toLowerCase() : null;
                log("Checking for compatible updates (MC " + instance.mcVersion + ", loader: " + loaderName + ")…");
                service.checkUpdates(currentModEntries, instance.mcVersion, loaderName, this::log);

                Platform.runLater(() -> {
                    modsList.refresh();
                    long updatable = currentModEntries.stream()
                            .filter(m -> "Update available".equals(m.status)).count();
                    updateAllBtn.setDisable(updatable == 0);
                    checkUpdatesBtn.setDisable(false);
                    setStatus(updatable > 0
                            ? updatable + " mod update(s) available."
                            : "All mods are up to date.");
                });

            } catch (Exception ex) {
                log("Error checking mod updates: " + ex.getMessage());
                Platform.runLater(() -> {
                    checkUpdatesBtn.setDisable(false);
                    setStatus("Update check failed — see console.");
                });
            }
        }, "mod-update-check").start();
    }

    private void updateMods(Instance instance, List<ModEntry> modsToUpdate) {
        List<ModEntry> updatable = modsToUpdate.stream()
                .filter(m -> "Update available".equals(m.status) && m.updateUrl != null)
                .toList();
        if (updatable.isEmpty()) { log("No updatable mods selected."); return; }

        updateSelectedBtn.setDisable(true);
        updateAllBtn.setDisable(true);
        setStatus("Updating " + updatable.size() + " mod(s)…");

        Path modsDir = instanceManager.resolveGameDir(instance).resolve("mods");
        new Thread(() -> {
            ModUpdateService service = new ModUpdateService();
            int success = 0;
            for (ModEntry mod : updatable) {
                if (service.downloadUpdate(mod, modsDir, this::log)) success++;
            }
            int finalSuccess = success;
            Platform.runLater(() -> {
                modsList.refresh();
                long remaining = currentModEntries.stream()
                        .filter(m -> "Update available".equals(m.status)).count();
                updateAllBtn.setDisable(remaining == 0);
                setStatus("Updated " + finalSuccess + "/" + updatable.size() + " mod(s).");
            });
        }, "mod-update-download").start();
    }

    private void updateAllMods(Instance instance) {
        updateMods(instance, new java.util.ArrayList<>(currentModEntries));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DAWN CLIENT LOGIC
    // ══════════════════════════════════════════════════════════════════════════
    private Path getDawnJarPath(Instance instance) {
        Path gameDir = instanceManager.resolveGameDir(instance);
        return gameDir.resolve("mods").resolve(DAWN_JAR_NAME);
    }

    private void updateDawnStatus(Instance instance) {
        if (instance == null) {
            dawnStatusLabel.setText("Select an instance to manage Dawn Client.");
            installDawnButton.setDisable(true);
            deleteDawnButton.setDisable(true);
            return;
        }
        Path dawnJar = getDawnJarPath(instance);
        boolean installed = Files.exists(dawnJar);
        if (installed) {
            dawnStatusLabel.setText("✅  Dawn Client is installed for: " + instance.name);
            installDawnButton.setDisable(true);
            deleteDawnButton.setDisable(false);
        } else {
            dawnStatusLabel.setText("Dawn Client is not installed for: " + instance.name);
            installDawnButton.setDisable(false);
            deleteDawnButton.setDisable(true);
        }
    }

    private void installDawn(Instance instance) {
        installDawnButton.setDisable(true);
        dawnStatusLabel.setText("Downloading Dawn Client...");
        new Thread(() -> {
            try {
                Path dawnJar = getDawnJarPath(instance);
                Files.createDirectories(dawnJar.getParent());
                log("Downloading Dawn Client from " + DAWN_DOWNLOAD_URL);
                com.launcher.util.HttpUtil.downloadToFile(DAWN_DOWNLOAD_URL, dawnJar);
                log("Dawn Client installed to: " + dawnJar);
                Platform.runLater(() -> updateDawnStatus(instance));
            } catch (Exception ex) {
                log("Failed to install Dawn Client: " + ex.getMessage());
                Platform.runLater(() -> {
                    dawnStatusLabel.setText("Download failed — check console.");
                    installDawnButton.setDisable(false);
                });
            }
        }, "dawn-install").start();
    }

    private void removeDawn(Instance instance) {
        Path dawnJar = getDawnJarPath(instance);
        try {
            Files.deleteIfExists(dawnJar);
            log("Dawn Client removed from: " + instance.name);
        } catch (Exception ex) {
            log("Failed to remove Dawn Client: " + ex.getMessage());
        }
        updateDawnStatus(instance);
    }

    // Custom list cell
    private class InstanceCell extends ListCell<Instance> {
        @Override
        protected void updateItem(Instance i, boolean empty) {
            super.updateItem(i, empty);
            if (empty || i == null) { setGraphic(null); setText(null); return; }

            // ── Instance icon ─────────────────────────────────────────────────
            javafx.scene.image.ImageView iconView = new javafx.scene.image.ImageView();
            iconView.setFitWidth(40);
            iconView.setFitHeight(40);
            iconView.setPreserveRatio(true);
            iconView.setSmooth(true);
            // clip to rounded rectangle
            javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(40, 40);
            clip.setArcWidth(8); clip.setArcHeight(8);
            iconView.setClip(clip);

            boolean iconLoaded = false;
            if (i.imagePath != null && !i.imagePath.isBlank()) {
                try {
                    java.io.File f = new java.io.File(i.imagePath);
                    if (f.exists()) {
                        iconView.setImage(new javafx.scene.image.Image(f.toURI().toString(), 40, 40, true, true));
                        iconLoaded = true;
                    }
                } catch (Exception ignored) {}
            }
            if (!iconLoaded) {
                try {
                    java.io.InputStream is = getClass().getResourceAsStream("/com/launcher/DefaultInstanceIcon.png");
                    if (is != null) iconView.setImage(new javafx.scene.image.Image(is, 40, 40, true, true));
                } catch (Exception ignored) {}
            }

            // ── Status dot ────────────────────────────────────────────────────
            Circle dot = new Circle(5);
            dot.setFill(i.installed ? Color.web("#10b981") : Color.web("#f59e0b"));
            Tooltip.install(dot, new Tooltip(i.installed ? "Installed" : "Not installed"));

            Label name = new Label(i.name);
            name.getStyleClass().add("instance-name");

            Label verTag = new Label("MC " + i.mcVersion);
            verTag.getStyleClass().add("tag-version");

            HBox tags = new HBox(6, dot, verTag);
            tags.setAlignment(Pos.CENTER_LEFT);

            if (i.modLoader != ModLoaderType.VANILLA) {
                String loaderColor = switch (i.modLoader) {
                    case FABRIC   -> "#dda0dd";
                    case QUILT    -> "#c084fc";
                    case NEOFORGE -> "#e76e39";
                    default       -> "#f97316"; // FORGE
                };
                Label loaderTag = new Label(i.modLoader.toString()
                        + (i.modLoaderVersion != null ? " " + i.modLoaderVersion : ""));
                loaderTag.getStyleClass().add("tag-loader");
                loaderTag.setStyle("-fx-background-color:" + loaderColor + ";");
                tags.getChildren().add(loaderTag);
            }

            int ramGb = i.ramMb > 0 ? Math.max(1, i.ramMb / 1024) : 3;
            Label ramTag = new Label(ramGb + " GB RAM");
            ramTag.getStyleClass().add("tag-ram");
            tags.getChildren().add(ramTag);

            // Modpack indicator
            if (i.modpackFilePath != null && !i.modpackFilePath.isBlank()) {
                Label mpTag = new Label("Modpack");
                mpTag.setStyle("-fx-background-color:#0891b2; -fx-text-fill:#ffffff; -fx-font-size:10px; -fx-padding:2px 6px; -fx-background-radius:4px; -fx-font-weight:bold;");
                tags.getChildren().add(mpTag);
            }

            // Dawn indicator
            if (Files.exists(getDawnJarPath(i))) {
                Label dawnTag = new Label("Dawn");
                dawnTag.setStyle("-fx-background-color:#7c3aed; -fx-text-fill:#ffffff; -fx-font-size:10px; -fx-padding:2px 6px; -fx-background-radius:4px; -fx-font-weight:bold;");
                tags.getChildren().add(dawnTag);
            }

            String dirStr = (i.useCustomDirectory && i.customDirectoryPath != null)
                    ? i.customDirectoryPath : "Default directory";
            Label pathLbl = new Label(sanitizePrivacy(dirStr));
            pathLbl.getStyleClass().add("instance-path");

            VBox textCol = new VBox(5, name, tags, pathLbl);

            HBox card = new HBox(12, iconView, textCol);
            card.setPadding(new Insets(8, 12, 8, 12));
            card.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(textCol, Priority.ALWAYS);

            setGraphic(card);
            setText(null);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LOG / CONSOLE
    // ══════════════════════════════════════════════════════════════════════════
    private VBox buildLogArea() {
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(140);
        logArea.setMinHeight(80);

        Label title = new Label("Console");
        title.setStyle("-fx-font-weight:bold; -fx-font-size:11px; -fx-text-fill:-fx-text-muted;");

        statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-text-muted;");

        Button copyBtn = new Button("Copy");
        copyBtn.setStyle("-fx-font-size:10px; -fx-padding:3px 10px;");
        copyBtn.setOnAction(e -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(logArea.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
        });

        Button clearBtn = new Button("Clear");
        clearBtn.setStyle("-fx-font-size:10px; -fx-padding:3px 10px;");
        clearBtn.setOnAction(e -> logArea.clear());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, title, statusLabel, spacer, copyBtn, clearBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6, header, logArea);
        box.setPadding(new Insets(10, 16, 12, 16));
        box.setStyle("-fx-background-color:-fx-panel-background; -fx-border-color:-fx-border-subtle transparent transparent transparent; -fx-border-width:1px 0 0 0;");
        return box;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SETTINGS
    // ══════════════════════════════════════════════════════════════════════════
    private ScrollPane buildSettingsArea(Scene scene) {
        com.launcher.manager.SettingsManager mgr = com.launcher.manager.SettingsManager.getInstance();
        com.launcher.model.LauncherSettings s = mgr.getSettings();

        VBox root = new VBox(16);
        root.setPadding(new Insets(20));

        // ── Appearance ────────────────────────────────────────────────────────
        VBox appearance = card();
        Label appTitle = sectionTitle("Appearance");

        // Helper: build a color row with picker + hex field + reset button
        // Accent color
        ColorPicker accentPicker = colorPicker(s.accentColor, "#10b981");
        TextField accentHex = hexField(s.accentColor, "#10b981");
        Button accentReset = resetBtn();
        wireColorRow(accentPicker, accentHex, accentReset, "#10b981",
                hex -> { s.accentColor = hex; mgr.save(); applyTheme(scene, s); refreshInstances(); });

        // Window background
        ColorPicker bgPicker = colorPicker(s.bgColor, "#0a0a0f");
        TextField bgHex = hexField(s.bgColor, "#0a0a0f");
        Button bgReset = resetBtn();
        wireColorRow(bgPicker, bgHex, bgReset, "#0a0a0f",
                hex -> { s.bgColor = hex; mgr.save(); applyTheme(scene, s); });

        // Panel background
        ColorPicker panelPicker = colorPicker(s.panelBgColor, "#13131a");
        TextField panelHex = hexField(s.panelBgColor, "#13131a");
        Button panelReset = resetBtn();
        wireColorRow(panelPicker, panelHex, panelReset, "#13131a",
                hex -> { s.panelBgColor = hex; mgr.save(); applyTheme(scene, s); });

        // Text color
        ColorPicker textPicker = colorPicker(s.textColor, "#e2e2ea");
        TextField textHex = hexField(s.textColor, "#e2e2ea");
        Button textReset = resetBtn();
        wireColorRow(textPicker, textHex, textReset, "#e2e2ea",
                hex -> { s.textColor = hex; mgr.save(); applyTheme(scene, s); });

        // Console background
        ColorPicker logBgPicker = colorPicker(s.logBgColor, "#060608");
        TextField logBgHex = hexField(s.logBgColor, "#060608");
        Button logBgReset = resetBtn();
        wireColorRow(logBgPicker, logBgHex, logBgReset, "#060608",
                hex -> { s.logBgColor = hex; mgr.save(); applyTheme(scene, s); });

        // Font
        ComboBox<String> fontPicker = new ComboBox<>();
        fontPicker.setEditable(true);
        fontPicker.getItems().addAll("Inter", "Segoe UI", "Arial", "Verdana", "Roboto", "Consolas", "SansSerif");
        fontPicker.setValue(s.fontFamily != null && !s.fontFamily.isBlank() ? s.fontFamily : "Inter");
        fontPicker.setOnAction(e -> { s.fontFamily = fontPicker.getValue(); mgr.save(); applyTheme(scene, s); });

        GridPane grid = settingsGrid();
        grid.addRow(0, settingLabel("Accent color"),        colorRow(accentPicker, accentHex, accentReset));
        grid.addRow(1, settingLabel("Window background"),   colorRow(bgPicker, bgHex, bgReset));
        grid.addRow(2, settingLabel("Panel background"),    colorRow(panelPicker, panelHex, panelReset));
        grid.addRow(3, settingLabel("Text color"),          colorRow(textPicker, textHex, textReset));
        grid.addRow(4, settingLabel("Console background"),  colorRow(logBgPicker, logBgHex, logBgReset));
        grid.addRow(5, settingLabel("Font family"),         fontPicker);

        // Background image
        Label imgNote = new Label(s.useBackgroundImage && s.backgroundImagePath != null && !s.backgroundImagePath.isBlank()
                ? "Image: " + new java.io.File(s.backgroundImagePath).getName()
                : "No background image");
        imgNote.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-text-muted;");

        CheckBox useBgImg = new CheckBox("Use custom background image");
        useBgImg.setSelected(s.useBackgroundImage);
        useBgImg.setOnAction(e -> { s.useBackgroundImage = useBgImg.isSelected(); mgr.save(); applyTheme(scene, s); });

        Button chooseImg = new Button("Choose Image…");
        chooseImg.setOnAction(e -> withMaximizeGuard(() -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle("Background Image");
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Images","*.png","*.jpg","*.jpeg","*.bmp","*.gif"));
            java.io.File f = fc.showOpenDialog(primaryStage);
            if (f != null) {
                s.backgroundImagePath = f.getAbsolutePath();
                s.useBackgroundImage = true;
                useBgImg.setSelected(true);
                imgNote.setText("Image: " + f.getName());
                mgr.save(); applyTheme(scene, s);
            }
        }));
        Button clearImg = new Button("Remove Image");
        clearImg.setOnAction(e -> {
            s.backgroundImagePath = ""; s.useBackgroundImage = false;
            useBgImg.setSelected(false); imgNote.setText("No background image");
            mgr.save(); applyTheme(scene, s);
        });
        HBox imgBtns = new HBox(8, chooseImg, clearImg);

        // Transparent / blur effect
        CheckBox enableBlurCb = styledCheckbox("Enable transparent/blur effect", s.enableBlurEffect);

        Slider blurSlider = new Slider(1, 40, s.blurStrength > 0 ? s.blurStrength : 10);
        blurSlider.setPrefWidth(220);
        blurSlider.setDisable(!s.enableBlurEffect);

        Label blurValueLabel = new Label(String.valueOf((int) blurSlider.getValue()));
        blurValueLabel.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-text-muted; -fx-min-width:24px;");

        HBox blurSliderRow = new HBox(10, blurSlider, blurValueLabel);
        blurSliderRow.setAlignment(Pos.CENTER_LEFT);

        Label blurNote = new Label("Makes panels translucent for a frosted-glass look. Looks best with a background image.");
        blurNote.setStyle("-fx-font-size:10px; -fx-text-fill:-fx-text-muted; -fx-wrap-text:true;");

        enableBlurCb.setOnAction(e -> {
            s.enableBlurEffect = enableBlurCb.isSelected();
            blurSlider.setDisable(!s.enableBlurEffect);
            mgr.save();
            applyTheme(scene, s);
        });

        blurSlider.valueProperty().addListener((obs, oldV, newV) -> {
            s.blurStrength = newV.intValue();
            blurValueLabel.setText(String.valueOf(newV.intValue()));
            mgr.save();
            applyTheme(scene, s);
        });

        GridPane blurGrid = settingsGrid();
        blurGrid.addRow(0, settingLabel("Blur strength"), blurSliderRow);

        appearance.getChildren().addAll(appTitle, grid, new Separator(), useBgImg, imgBtns, imgNote,
                new Separator(), enableBlurCb, blurGrid, blurNote);

        // ── Behavior ──────────────────────────────────────────────────────────
        VBox behavior = card();
        Label behTitle = sectionTitle("Launcher Behavior");

        CheckBox minimizeCb = styledCheckbox("Minimize launcher when Minecraft launches (like the original Minecraft launcher)", s.minimizeOnLaunch);
        minimizeCb.setOnAction(e -> { s.minimizeOnLaunch = minimizeCb.isSelected(); mgr.save(); });

        CheckBox scanCb = styledCheckbox("Scan .minecraft folder for installed versions on startup", s.scanOnStartup);
        scanCb.setOnAction(e -> { s.scanOnStartup = scanCb.isSelected(); mgr.save(); });

        CheckBox showHiddenCb = styledCheckbox("Show hidden instances", s.showHiddenInstances);
        showHiddenCb.setOnAction(e -> { s.showHiddenInstances = showHiddenCb.isSelected(); mgr.save(); refreshInstances(); });

        CheckBox closeAfterLaunchCb = styledCheckbox("Close launcher after Minecraft launches (saves RAM)", s.closeAfterLaunch);
        closeAfterLaunchCb.setOnAction(e -> { s.closeAfterLaunch = closeAfterLaunchCb.isSelected(); mgr.save(); });

        CheckBox showConsoleOnLaunchCb = styledCheckbox("Keep console visible while game is running", s.showConsoleOnLaunch);
        showConsoleOnLaunchCb.setOnAction(e -> { s.showConsoleOnLaunch = showConsoleOnLaunchCb.isSelected(); mgr.save(); });

        behavior.getChildren().addAll(behTitle, minimizeCb, scanCb, showHiddenCb, closeAfterLaunchCb, showConsoleOnLaunchCb);

        // ── Window Size ───────────────────────────────────────────────────────
        VBox windowSizeCard = card();
        Label winSizeTitle = sectionTitle("Window Size");

        Label winSizeNote = new Label("Set the launcher width and height when it opens. Minimum: 820 × 560. Changes take effect on next launch.");
        winSizeNote.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-text-muted; -fx-wrap-text:true;");

        int savedW = (s.launcherWidth  >= 820) ? s.launcherWidth  : 960;
        int savedH = (s.launcherHeight >= 560) ? s.launcherHeight : 660;

        Spinner<Integer> widthSpinner  = new Spinner<>(820, 3840, savedW, 10);
        Spinner<Integer> heightSpinner = new Spinner<>(560, 2160, savedH, 10);
        widthSpinner.setEditable(true);
        heightSpinner.setEditable(true);
        widthSpinner.setPrefWidth(110);
        heightSpinner.setPrefWidth(110);

        widthSpinner.valueProperty().addListener((obs, oldV, newV) -> {
            s.launcherWidth = newV; mgr.save();
        });
        heightSpinner.valueProperty().addListener((obs, oldV, newV) -> {
            s.launcherHeight = newV; mgr.save();
        });

        Button applyWinSize = new Button("Apply Now");
        applyWinSize.setOnAction(e -> {
            if (primaryStage != null && !primaryStage.isMaximized()) {
                primaryStage.setWidth(s.launcherWidth  >= 820 ? s.launcherWidth  : 960);
                primaryStage.setHeight(s.launcherHeight >= 560 ? s.launcherHeight : 660);
            }
        });

        GridPane winGrid = settingsGrid();
        winGrid.addRow(0, settingLabel("Width (px)"),  widthSpinner);
        winGrid.addRow(1, settingLabel("Height (px)"), heightSpinner);

        windowSizeCard.getChildren().addAll(winSizeTitle, winGrid, applyWinSize, winSizeNote);

        // ── Performance ───────────────────────────────────────────────────────
        VBox performance = card();
        Label perfTitle = sectionTitle("Performance");

        Label defaultRamLabel = settingLabel("Default RAM (GB)");
        Spinner<Integer> ramSpinner = new Spinner<>(1, 64, s.defaultRamGb > 0 ? s.defaultRamGb : 3);
        ramSpinner.setEditable(true);
        ramSpinner.setPrefWidth(90);
        ramSpinner.valueProperty().addListener((obs, oldV, newV) -> { s.defaultRamGb = newV; mgr.save(); });

        Label jvmArgsLabel = settingLabel("Extra JVM arguments");
        TextField jvmArgsField = new TextField(s.extraJvmArgs != null ? s.extraJvmArgs : "");
        jvmArgsField.setPromptText("e.g. -XX:+UseZGC -Dfml.ignoreInvalidMinecraftCertificates=true");
        jvmArgsField.textProperty().addListener((obs, oldV, newV) -> { s.extraJvmArgs = newV; mgr.save(); });
        HBox.setHgrow(jvmArgsField, Priority.ALWAYS);

        GridPane perfGrid = settingsGrid();
        perfGrid.addRow(0, defaultRamLabel, ramSpinner);
        perfGrid.addRow(1, jvmArgsLabel, jvmArgsField);

        performance.getChildren().addAll(perfTitle, perfGrid);

        // ── Privacy ───────────────────────────────────────────────────────────
        VBox privacy = card();
        Label privTitle = sectionTitle("Privacy & Security");

        CheckBox hideUserCb = styledCheckbox("Hide username in launcher UI", s.hideUsername);
        hideUserCb.setOnAction(e -> { s.hideUsername = hideUserCb.isSelected(); mgr.save(); refreshAccounts(); });

        CheckBox redactPathsCb = styledCheckbox("Redact OS username from log paths", s.redactPaths);
        redactPathsCb.setOnAction(e -> { s.redactPaths = redactPathsCb.isSelected(); mgr.save(); refreshInstances(); });

        CheckBox redactTokensCb = styledCheckbox("Redact Minecraft session tokens in logs", s.redactTokens);
        redactTokensCb.setOnAction(e -> { s.redactTokens = redactTokensCb.isSelected(); mgr.save(); });

        CheckBox clearSessionCb = styledCheckbox("Clear account sessions when the launcher closes", s.clearSessionOnExit);
        clearSessionCb.setOnAction(e -> { s.clearSessionOnExit = clearSessionCb.isSelected(); mgr.save(); });

        privacy.getChildren().addAll(privTitle, hideUserCb, redactPathsCb, redactTokensCb, clearSessionCb);

        // ── About ─────────────────────────────────────────────────────────────
        VBox about = card();
        Label aboutTitle = sectionTitle("About");
        Label aboutInfo = new Label("Zero Launcher  —  a lightweight, privacy-respecting Minecraft launcher.\nBuilt with JavaFX. Dawn Client integration included.");
        aboutInfo.setStyle("-fx-font-size:12px; -fx-text-fill:-fx-text-muted; -fx-wrap-text:true;");
        Button openDataDir = new Button("📂 Open Launcher Data Folder");
        openDataDir.setOnAction(e -> {
            try {
                Path dir = LauncherPaths.instancesFile().getParent();
                if (!Files.exists(dir)) Files.createDirectories(dir);
                String os = System.getProperty("os.name", "").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("win")) {
                    pb = new ProcessBuilder("explorer.exe", dir.toAbsolutePath().toString());
                } else if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", dir.toAbsolutePath().toString());
                } else {
                    pb = new ProcessBuilder("xdg-open", dir.toAbsolutePath().toString());
                }
                pb.start();
            } catch (Exception ex) { log("Could not open data folder: " + ex.getMessage()); }
        });
        about.getChildren().addAll(aboutTitle, aboutInfo, openDataDir);

        root.getChildren().addAll(appearance, behavior, windowSizeCard, performance, privacy, about);

        ScrollPane sp = new ScrollPane(root);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setStyle("-fx-background-color:transparent; -fx-border-color:transparent;");
        return sp;
    }

    // ── Color row helpers ─────────────────────────────────────────────────────

    /** Builds a hex input TextField pre-filled with a color value */
    private static TextField hexField(String hex, String fallback) {
        String val = (hex != null && !hex.isBlank()) ? hex : fallback;
        TextField tf = new TextField(val);
        tf.setPrefWidth(86);
        tf.setPromptText("#rrggbb");
        tf.setStyle("-fx-font-family: Consolas, monospace; -fx-font-size:11px;");
        return tf;
    }

    /** Builds a reset (↺) button */
    private static Button resetBtn() {
        Button b = new Button("↺");
        b.setTooltip(new Tooltip("Reset to default"));
        b.setStyle("-fx-font-size:12px; -fx-padding:4px 8px;");
        return b;
    }

    /** Packs picker + hex + reset into a single HBox */
    private static HBox colorRow(ColorPicker picker, TextField hex, Button reset) {
        HBox hb = new HBox(6, picker, hex, reset);
        hb.setAlignment(Pos.CENTER_LEFT);
        return hb;
    }

    /**
     * Wires the three controls so they stay in sync, and calls {@code onChange} with
     * the new hex string whenever any of them is updated.
     */
    private void wireColorRow(ColorPicker picker, TextField hexTf, Button resetBtn,
                               String defaultHex, java.util.function.Consumer<String> onChange) {
        // Picker → hex field + callback
        picker.setOnAction(e -> {
            String hex = toHex(picker.getValue());
            hexTf.setText(hex);
            onChange.accept(hex);
        });

        // Hex field → picker + callback (on Enter or focus-lost)
        Runnable applyHex = () -> {
            String raw = hexTf.getText().trim();
            if (!raw.startsWith("#")) raw = "#" + raw;
            try {
                Color c = Color.web(raw);
                picker.setValue(c);
                hexTf.setStyle("-fx-font-family: Consolas, monospace; -fx-font-size:11px;");
                onChange.accept(toHex(c));
            } catch (Exception ex) {
                hexTf.setStyle("-fx-font-family: Consolas, monospace; -fx-font-size:11px; -fx-border-color:#ef4444;");
            }
        };
        hexTf.setOnAction(e -> applyHex.run());
        hexTf.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) applyHex.run();
        });

        // Reset → default
        resetBtn.setOnAction(e -> {
            try { picker.setValue(Color.web(defaultHex)); } catch (Exception ignored) {}
            hexTf.setText(defaultHex);
            hexTf.setStyle("-fx-font-family: Consolas, monospace; -fx-font-size:11px;");
            onChange.accept(defaultHex);
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MODPACK EXTRACTION
    // ══════════════════════════════════════════════════════════════════════════
    private void extractModpack(Instance instance) {
        playButton.setDisable(true);
        setStatus("Extracting modpack for " + instance.name + "…");
        log("Starting modpack extraction: " + instance.modpackFilePath);

        new Thread(() -> {
            try {
                Path modpackFile = Path.of(instance.modpackFilePath);
                String installPath = (instance.modpackInstallPath != null && !instance.modpackInstallPath.isBlank())
                        ? instance.modpackInstallPath
                        : instanceManager.resolveGameDir(instance).toAbsolutePath().toString();
                Path installDir = Path.of(installPath);

                ModpackExtractor extractor = new ModpackExtractor();
                extractor.extract(modpackFile, installDir, this::log);

                log("Modpack extracted successfully to: " + installDir);
                setStatus("Modpack extracted — " + instance.name);
                Platform.runLater(() -> instanceList.refresh());

            } catch (Exception ex) {
                log("ERROR extracting modpack: " + ex.getMessage());
                setStatus("Modpack extraction failed — see console.");
            } finally {
                Platform.runLater(() -> playButton.setDisable(false));
            }
        }, "modpack-extract").start();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LAUNCH LOGIC
    // ══════════════════════════════════════════════════════════════════════════
    private void launchInstance(Instance instance, Account account) {
        playButton.setDisable(true);
        setStatus("Preparing " + instance.name + "…");

        new Thread(() -> {
            try {
                Path gameDir = instanceManager.resolveGameDir(instance);
                Path nativesDir = gameDir.resolve("natives");
                GameInstaller installer = new GameInstaller();
                VersionManifestService manifestService = new VersionManifestService();
                JsonObject versionJson = null;

                if (instance.modLoader == ModLoaderType.VANILLA) {
                    Path localJson = LauncherPaths.findLocalVersionJson(instance.mcVersion, gameDir);
                    if (localJson != null) {
                        log("Loading local version JSON for " + instance.mcVersion + "…");
                        try { versionJson = JsonUtil.parse(Files.readString(localJson)).getAsJsonObject(); }
                        catch (Exception ex) { log("Local JSON unreadable, fetching from network."); }
                    }
                    if (versionJson == null) {
                        log("Fetching vanilla version " + instance.mcVersion + "…");
                        setStatus("Fetching version data…");
                        var urls = manifestService.fetchVersionUrls();
                        String url = urls.get(instance.mcVersion);
                        if (url == null) throw new RuntimeException("Unknown version: " + instance.mcVersion);
                        versionJson = manifestService.fetchVersionJson(url);
                    }

                } else if (instance.modLoader == ModLoaderType.FABRIC) {
                    log("Fetching Fabric profile " + instance.modLoaderVersion + "…");
                    setStatus("Fetching Fabric profile…");
                    versionJson = new FabricInstaller().fetchProfileJson(instance.mcVersion, instance.modLoaderVersion);

                } else if (instance.modLoader == ModLoaderType.QUILT) {
                    log("Fetching Quilt profile " + instance.modLoaderVersion + "…");
                    setStatus("Fetching Quilt profile…");
                    versionJson = new QuiltInstaller().fetchProfileJson(instance.mcVersion, instance.modLoaderVersion);

                } else if (instance.modLoader == ModLoaderType.NEOFORGE) {
                    log("Installing NeoForge " + instance.modLoaderVersion + "…");
                    setStatus("Installing NeoForge…");
                    NeoForgeInstaller nfi = new NeoForgeInstaller();
                    String vid = nfi.installClient(instance.mcVersion, instance.modLoaderVersion, gameDir, this::log);
                    versionJson = nfi.loadGeneratedVersionJson(gameDir, vid);

                } else {
                    // FORGE
                    ForgeInstaller fi = new ForgeInstaller();
                    String fv = instance.modLoaderVersion;
                    if ("Recommended".equals(fv) || "Latest".equals(fv))
                        fv = fi.fetchPromotedLatest(instance.mcVersion, "Recommended".equals(fv));
                    String vid = fi.installClient(instance.mcVersion, fv, gameDir, this::log);
                    versionJson = fi.loadGeneratedVersionJson(gameDir, vid);
                }

                setStatus("Resolving dependencies…");
                JsonObject merged = installer.resolveInheritance(versionJson, this::log);
                log("Downloading/verifying files…");
                setStatus("Installing files…");
                ResolvedVersion resolved = installer.installAndResolve(merged, nativesDir, this::log);

                instance.installed = true;
                instanceManager.save();
                Platform.runLater(() -> instanceList.refresh());

                log("Launching Minecraft in separate window…");
                setStatus("Running " + instance.name);

                GameLauncher launcher = new GameLauncher();
                Process process = launcher.launch(instance, gameDir, nativesDir, resolved, account, this::log);
                this.activeProcess = process;

                Platform.runLater(() -> {
                    killButton.setDisable(false);
                    com.launcher.model.LauncherSettings cs = com.launcher.manager.SettingsManager.getInstance().getSettings();
                    if (primaryStage != null && cs.minimizeOnLaunch) {
                        primaryStage.setIconified(true);
                    }
                    if (com.launcher.manager.SettingsManager.getInstance().getSettings().closeAfterLaunch) {
                        Platform.exit();
                    }
                });
                System.gc();

                try (var reader = process.inputReader()) {
                    String line;
                    while ((line = reader.readLine()) != null) log("[game] " + line);
                }
                int exit = process.waitFor();
                log("Minecraft exited with code " + exit);
                setStatus(exit == 0 ? "Game closed normally." : "Game exited (code " + exit + ")");

            } catch (Exception ex) {
                log("ERROR: " + ex.getMessage());
                setStatus("Launch failed — see console.");
            } finally {
                this.activeProcess = null;
                Platform.runLater(() -> {
                    playButton.setDisable(false);
                    killButton.setDisable(true);
                    if (primaryStage != null) {
                        primaryStage.setIconified(false);
                        primaryStage.toFront();
                    }
                });
                System.gc();
            }
        }, "launch-thread").start();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  THEME
    // ══════════════════════════════════════════════════════════════════════════
    private void applyTheme(Scene scene, com.launcher.model.LauncherSettings settings) {
        if (scene == null || settings == null) return;
        scene.getRoot().setStyle(buildMainSceneStyle(settings));
        if (logArea != null)
            logArea.setStyle("-fx-control-inner-background:" + safeColor(settings.logBgColor, "#060608") + ";");
        refreshBackdrop(settings);
    }

    public static void applyThemeToPane(javafx.scene.layout.Pane pane, com.launcher.model.LauncherSettings settings) {
        if (pane == null || settings == null) return;
        pane.setStyle(buildThemeStyle(settings));
    }

    private static String buildThemeStyle(com.launcher.model.LauncherSettings settings) {
        String accent   = safeColor(settings.accentColor, "#10b981");
        String hover    = lighten(accent);
        String dim      = hexToRgba(accent, 0.15);
        String logBg    = safeColor(settings.logBgColor,   "#060608");
        String panel    = safeColor(settings.panelBgColor, "#13131a");
        String text     = safeColor(settings.textColor,    "#e2e2ea");
        String bg       = safeColor(settings.bgColor,      "#0a0a0f");
        String font     = (settings.fontFamily == null || settings.fontFamily.isBlank()) ? "Inter" : settings.fontFamily.replace("\"","");

        StringBuilder sb = new StringBuilder();
        sb.append("-fx-accent-color:").append(accent).append(";");
        sb.append("-fx-accent-hover:").append(hover).append(";");
        sb.append("-fx-accent-dim:").append(dim).append(";");
        sb.append("-fx-log-bg-color:").append(logBg).append(";");
        sb.append("-fx-panel-background:").append(panel).append(";");
        sb.append("-fx-surface:").append(panel).append(";");
        sb.append("-fx-text-color:").append(text).append(";");
        sb.append("-fx-font-name:\"").append(font).append("\";");

        java.io.File imgFile = (settings.backgroundImagePath != null && !settings.backgroundImagePath.isBlank())
                ? new java.io.File(settings.backgroundImagePath) : null;

        if (settings.useBackgroundImage && imgFile != null && imgFile.isFile()) {
            sb.append("-fx-app-background:").append(bg).append(";");
            sb.append("-fx-background-color:").append(bg).append(";");
            sb.append("-fx-background-image:url('").append(imgFile.toURI()).append("');");
            sb.append("-fx-background-size:cover;");
            sb.append("-fx-background-position:center center;");
            sb.append("-fx-background-repeat:no-repeat;");
        } else {
            sb.append("-fx-app-background:").append(bg).append(";");
            sb.append("-fx-background-color:").append(bg).append(";");
        }
        return sb.toString();
    }

    /**
     * Builds the style applied to the main window's root only (never to dialogs).
     * When the transparent/blur effect is enabled, panels become translucent and
     * the root's own background is cleared so the {@link #backdropLayer} — which
     * paints the real background and, optionally, a blurred backdrop — shows through.
     */
    private static String buildMainSceneStyle(com.launcher.model.LauncherSettings settings) {
        String base = buildThemeStyle(settings);
        if (!settings.enableBlurEffect) return base;

        String panel = safeColor(settings.panelBgColor, "#13131a");
        double alpha = blurAlpha(settings);
        String translucentPanel = hexToRgba(panel, alpha);

        StringBuilder sb = new StringBuilder(base);
        sb.append("-fx-panel-background:").append(translucentPanel).append(";");
        sb.append("-fx-surface:").append(translucentPanel).append(";");
        sb.append("-fx-app-background:transparent;");
        sb.append("-fx-background-color:transparent;");
        sb.append("-fx-background-image:none;");
        return sb.toString();
    }

    /** Clamps blurStrength to a sane 1–40 range. */
    private static int clampBlurStrength(com.launcher.model.LauncherSettings settings) {
        int s = settings.blurStrength <= 0 ? 10 : settings.blurStrength;
        return Math.max(1, Math.min(40, s));
    }

    /** Maps blur strength to a panel opacity: higher strength = more transparent. */
    private static double blurAlpha(com.launcher.model.LauncherSettings settings) {
        int strength = clampBlurStrength(settings);
        return Math.max(0.30, 1.0 - (strength / 40.0) * 0.6);
    }

    /**
     * (Re)builds the backdrop layer that sits behind the whole UI: a flat fill
     * (or the user's chosen background image), plus — when the transparent/blur
     * effect is on — either a blurred version of that image, or a few soft,
     * blurred accent-colored shapes so the glass effect still has something
     * to blur even without a custom background.
     */
    private void refreshBackdrop(com.launcher.model.LauncherSettings settings) {
        if (backdropLayer == null) return;
        backdropLayer.getChildren().clear();

        String bg = safeColor(settings.bgColor, "#0a0a0f");
        boolean blur = settings.enableBlurEffect;
        int strength = clampBlurStrength(settings);

        Region base = new Region();
        base.setStyle("-fx-background-color:" + bg + ";");
        backdropLayer.getChildren().add(base);

        java.io.File imgFile = (settings.backgroundImagePath != null && !settings.backgroundImagePath.isBlank())
                ? new java.io.File(settings.backgroundImagePath) : null;

        if (settings.useBackgroundImage && imgFile != null && imgFile.isFile()) {
            Region imgRegion = new Region();
            imgRegion.setStyle(
                    "-fx-background-image:url('" + imgFile.toURI() + "');" +
                    "-fx-background-size:cover;" +
                    "-fx-background-position:center center;" +
                    "-fx-background-repeat:no-repeat;");
            if (blur) imgRegion.setEffect(new javafx.scene.effect.GaussianBlur(strength));
            backdropLayer.getChildren().add(imgRegion);
        } else if (blur) {
            backdropLayer.getChildren().add(buildBlurBlobs(settings, strength));
        }
    }

    /** Soft blurred accent-colored shapes used as a backdrop when no background image is set. */
    private Pane buildBlurBlobs(com.launcher.model.LauncherSettings settings, int strength) {
        Pane blobs = new Pane();
        blobs.setMouseTransparent(true);
        String accent = safeColor(settings.accentColor, "#10b981");

        Circle c1 = new Circle(220);
        Circle c2 = new Circle(260);
        Circle c3 = new Circle(160);
        try { c1.setFill(Color.web(accent, 0.35)); } catch (Exception ignored) { c1.setFill(Color.web("#10b981", 0.35)); }
        try { c2.setFill(Color.web(accent, 0.18)); } catch (Exception ignored) { c2.setFill(Color.web("#10b981", 0.18)); }
        c3.setFill(Color.web("#ffffff", 0.05));

        c1.centerXProperty().bind(blobs.widthProperty().multiply(0.15));
        c1.centerYProperty().bind(blobs.heightProperty().multiply(0.18));
        c2.centerXProperty().bind(blobs.widthProperty().multiply(0.85));
        c2.centerYProperty().bind(blobs.heightProperty().multiply(0.82));
        c3.centerXProperty().bind(blobs.widthProperty().multiply(0.55));
        c3.centerYProperty().bind(blobs.heightProperty().multiply(0.35));

        blobs.getChildren().addAll(c1, c2, c3);
        blobs.setEffect(new javafx.scene.effect.GaussianBlur(Math.min(63, strength * 2.0)));
        return blobs;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════════════
    private void refreshAccounts() {
        accountBox.getItems().setAll(accountManager.getAccounts());
        accountManager.getActiveAccount().ifPresent(accountBox::setValue);
    }

    private void refreshInstances() {
        boolean showHidden = com.launcher.manager.SettingsManager.getInstance().getSettings().showHiddenInstances;
        var filtered = instanceManager.getInstances().stream()
                .filter(i -> showHidden || !i.hidden).toList();
        instanceList.getItems().setAll(filtered);
    }

    private void log(String msg) {
        logQueue.add(sanitizePrivacy(msg));
        scheduleLogFlush();
    }

    private void setStatus(String msg) {
        Platform.runLater(() -> { if (statusLabel != null) statusLabel.setText(msg); });
    }

    private void scheduleLogFlush() {
        if (logFlushScheduled) return;
        if (isMinimized) {
            long now = System.currentTimeMillis();
            if (now - lastLogFlushWhenMinimized < 2000) return;
            lastLogFlushWhenMinimized = now;
        }
        logFlushScheduled = true;
        Platform.runLater(() -> {
            logFlushScheduled = false;
            StringBuilder sb = new StringBuilder();
            String line; int count = 0;
            while ((line = logQueue.poll()) != null) {
                sb.append(line).append("\n");
                if (++count > 400) break;
            }
            if (sb.length() > 0) {
                logArea.appendText(sb.toString());
                String text = logArea.getText();
                if (text.length() > 30000) logArea.setText(text.substring(text.length() - 20000));
                logArea.selectPositionCaret(logArea.getText().length());
            }
            if (!logQueue.isEmpty()) scheduleLogFlush();
        });
    }

    private String sanitizePrivacy(String message) {
        if (message == null) return "";
        com.launcher.model.LauncherSettings s = com.launcher.manager.SettingsManager.getInstance().getSettings();
        return s.redactPaths ? message.replace(System.getProperty("user.name"), "unnamed_user") : message;
    }

    // ── Static CSS helpers ────────────────────────────────────────────────────
    private static String safeColor(String v, String fallback) {
        if (v == null || v.isBlank()) return fallback;
        try { Color.web(v); return v; } catch (Exception e) { return fallback; }
    }

    private static String lighten(String hex) {
        try {
            Color c = Color.web(hex).brighter();
            return String.format("#%02X%02X%02X",(int)(c.getRed()*255),(int)(c.getGreen()*255),(int)(c.getBlue()*255));
        } catch (Exception e) { return hex; }
    }

    private static String hexToRgba(String hex, double alpha) {
        try {
            Color c = Color.web(hex);
            return String.format("rgba(%d,%d,%d,%.2f)",(int)(c.getRed()*255),(int)(c.getGreen()*255),(int)(c.getBlue()*255),alpha);
        } catch (Exception e) { return "rgba(16,185,129,0.15)"; }
    }

    private String toHex(Color c) {
        return String.format("#%02X%02X%02X",(int)(c.getRed()*255),(int)(c.getGreen()*255),(int)(c.getBlue()*255));
    }

    // ── Settings UI factory helpers ──────────────────────────────────────────
    private static VBox card() {
        VBox v = new VBox(10);
        v.getStyleClass().add("settings-card");
        return v;
    }

    private static Label sectionTitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-title");
        return l;
    }

    private static Label settingLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill:-fx-text-color; -fx-font-size:12px; -fx-min-width:170px;");
        return l;
    }

    private static GridPane settingsGrid() {
        GridPane g = new GridPane();
        g.setHgap(14); g.setVgap(10);
        ColumnConstraints c0 = new ColumnConstraints(); c0.setMinWidth(170);
        ColumnConstraints c1 = new ColumnConstraints(); c1.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(c0, c1);
        return g;
    }

    private static CheckBox styledCheckbox(String label, boolean selected) {
        CheckBox cb = new CheckBox(label);
        cb.setSelected(selected);
        cb.setWrapText(true);
        return cb;
    }

    private static ColorPicker colorPicker(String hex, String fallback) {
        ColorPicker cp = new ColorPicker();
        try { cp.setValue(Color.web(hex)); } catch (Exception e) { cp.setValue(Color.web(fallback)); }
        return cp;
    }

    public static void main(String[] args) { launch(args); }
}
