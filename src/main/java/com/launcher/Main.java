package com.launcher;

import com.google.gson.JsonObject;
import com.launcher.auth.MicrosoftAuthService;
import com.launcher.manager.AccountManager;
import com.launcher.manager.InstanceManager;
import com.launcher.minecraft.*;
import com.launcher.model.Account;
import com.launcher.model.AccountType;
import com.launcher.model.Instance;
import com.launcher.model.ModLoaderType;
import com.launcher.ui.CreateInstanceDialog;
import com.launcher.ui.LoginDialog;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Path;

public class Main extends Application {

    private final AccountManager accountManager = new AccountManager();
    private final InstanceManager instanceManager = new InstanceManager();

    private ComboBox<Account> accountBox;
    private ListView<Instance> instanceList;
    private TextArea logArea;
    private Button playButton;

    @Override
    public void start(Stage stage) {
        stage.setTitle("MCLauncher");

        BorderPane root = new BorderPane();
        root.setTop(buildTopBar(stage));
        root.setCenter(buildInstanceArea(stage));
        root.setBottom(buildLogArea());

        stage.setScene(new Scene(root, 880, 600));
        stage.show();

        refreshAccounts();
        refreshInstances();
    }

    private HBox buildTopBar(Stage stage) {
        HBox bar = new HBox(10);
        bar.setPadding(new Insets(10));

        accountBox = new ComboBox<>();
        accountBox.setPrefWidth(220);
        accountBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Account a) {
                if (a == null) return "";
                return a.username + (a.type == AccountType.MICROSOFT ? "  (Microsoft)" : "  (Offline)");
            }
            @Override public Account fromString(String s) { return null; }
        });
        accountBox.setOnAction(e -> {
            if (accountBox.getValue() != null) accountManager.setActiveAccount(accountBox.getValue());
        });

        Button addAccountButton = new Button("Add Account");
        addAccountButton.setOnAction(e -> LoginDialog.show(stage, account -> {
            accountManager.addOrUpdate(account);
            refreshAccounts();
        }));

        Button removeAccountButton = new Button("Remove");
        removeAccountButton.setOnAction(e -> {
            Account selected = accountBox.getValue();
            if (selected != null) {
                accountManager.remove(selected);
                refreshAccounts();
            }
        });

        bar.getChildren().addAll(new Label("Active account:"), accountBox, addAccountButton, removeAccountButton);
        return bar;
    }

    private VBox buildInstanceArea(Stage stage) {
        VBox box = new VBox(10);
        box.setPadding(new Insets(10));

        HBox toolbar = new HBox(10);
        Button newInstanceButton = new Button("New Instance");
        playButton = new Button("Play");
        Button deleteButton = new Button("Delete");
        toolbar.getChildren().addAll(newInstanceButton, playButton, deleteButton);

        instanceList = new ListView<>();
        instanceList.setPrefHeight(300);
        instanceList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Instance i, boolean empty) {
                super.updateItem(i, empty);
                if (empty || i == null) {
                    setText(null);
                } else {
                    String loader = i.modLoader == ModLoaderType.VANILLA ? "Vanilla" : i.modLoader + " " + i.modLoaderVersion;
                    String dir = i.useCustomDirectory ? i.customDirectoryPath : "(default launcher directory)";
                    setText(i.name + "  -  " + i.mcVersion + "  -  " + loader + (i.installed ? "  [installed]" : "  [not installed]") + "\n" + dir);
                }
            }
        });

        newInstanceButton.setOnAction(e -> CreateInstanceDialog.show(stage).ifPresent(instance -> {
            instanceManager.add(instance);
            refreshInstances();
        }));

        deleteButton.setOnAction(e -> {
            Instance selected = instanceList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                instanceManager.remove(selected);
                refreshInstances();
            }
        });

        playButton.setOnAction(e -> {
            Instance selected = instanceList.getSelectionModel().getSelectedItem();
            Account account = accountBox.getValue();
            if (selected == null) {
                log("Select an instance first.");
                return;
            }
            if (account == null) {
                log("Add and select an account first.");
                return;
            }
            launchInstance(selected, account);
        });

        box.getChildren().addAll(toolbar, instanceList);
        return box;
    }

    private VBox buildLogArea() {
        VBox box = new VBox(5);
        box.setPadding(new Insets(0, 10, 10, 10));
        box.getChildren().add(new Label("Log:"));
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(180);
        box.getChildren().add(logArea);
        return box;
    }

    private void refreshAccounts() {
        accountBox.getItems().setAll(accountManager.getAccounts());
        accountManager.getActiveAccount().ifPresent(accountBox::setValue);
    }

    private void refreshInstances() {
        instanceList.getItems().setAll(instanceManager.getInstances());
    }

    private void log(String message) {
        Platform.runLater(() -> logArea.appendText(message + "\n"));
    }

    private void launchInstance(Instance instance, Account account) {
        playButton.setDisable(true);
        new Thread(() -> {
            try {
                // Refresh Microsoft token if needed before doing anything else.
                if (account.type == AccountType.MICROSOFT && account.isMicrosoftTokenExpired()) {
                    log("Refreshing Microsoft sign-in...");
                    new MicrosoftAuthService().refreshAccount(account);
                    accountManager.addOrUpdate(account);
                }

                Path gameDir = instanceManager.resolveGameDir(instance);
                Path nativesDir = gameDir.resolve("natives");
                GameInstaller installer = new GameInstaller();
                VersionManifestService manifestService = new VersionManifestService();

                JsonObject versionJson;
                if (instance.modLoader == ModLoaderType.VANILLA) {
                    log("Fetching vanilla version " + instance.mcVersion + " ...");
                    var urls = manifestService.fetchVersionUrls();
                    String url = urls.get(instance.mcVersion);
                    if (url == null) throw new RuntimeException("Unknown Minecraft version: " + instance.mcVersion);
                    versionJson = manifestService.fetchVersionJson(url);

                } else if (instance.modLoader == ModLoaderType.FABRIC) {
                    log("Fetching Fabric profile " + instance.modLoaderVersion + " ...");
                    versionJson = new FabricInstaller().fetchProfileJson(instance.mcVersion, instance.modLoaderVersion);

                } else { // FORGE
                    ForgeInstaller forgeInstaller = new ForgeInstaller();
                    String forgeVersion = instance.modLoaderVersion;
                    if ("Recommended".equals(forgeVersion) || "Latest".equals(forgeVersion)) {
                        forgeVersion = forgeInstaller.fetchPromotedLatest(instance.mcVersion, "Recommended".equals(forgeVersion));
                    }
                    String versionId = forgeInstaller.installClient(instance.mcVersion, forgeVersion, gameDir, this::log);
                    versionJson = forgeInstaller.loadGeneratedVersionJson(gameDir, versionId);
                }

                JsonObject merged = installer.resolveInheritance(versionJson, this::log);
                log("Downloading/verifying files (this can take a while the first time)...");
                ResolvedVersion resolved = installer.installAndResolve(merged, nativesDir, this::log);

                instance.installed = true;
                instanceManager.save();

                log("Launching Minecraft...");
                GameLauncher launcher = new GameLauncher();
                Process process = launcher.launch(instance, gameDir, nativesDir, resolved, account, this::log);
                try (var reader = process.inputReader()) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log("[game] " + line);
                    }
                }
                int exit = process.waitFor();
                log("Minecraft exited with code " + exit);

            } catch (Exception ex) {
                log("ERROR: " + ex.getMessage());
            } finally {
                Platform.runLater(() -> playButton.setDisable(false));
            }
        }, "launch-thread").start();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
