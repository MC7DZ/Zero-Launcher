package com.launcher.ui;

import com.launcher.auth.DeviceCodeInfo;
import com.launcher.auth.MicrosoftAuthService;
import com.launcher.auth.OfflineAuthService;
import com.launcher.model.Account;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class LoginDialog {

    public static void show(Stage owner, Consumer<Account> onSuccess) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Add Account");

        TabPane tabs = new TabPane();

        // ---- Offline tab ----
        VBox offlineBox = new VBox(10);
        offlineBox.setPadding(new Insets(20));
        TextField usernameField = new TextField();
        usernameField.setPromptText("Username");
        Button offlineButton = new Button("Add Offline Account");
        Label offlineNote = new Label("Offline accounts can't join servers that require Microsoft authentication,\nbut work fine for singleplayer and offline-mode servers.");
        offlineNote.setWrapText(true);
        offlineButton.setOnAction(e -> {
            try {
                Account acc = new OfflineAuthService().login(usernameField.getText());
                onSuccess.accept(acc);
                stage.close();
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
            }
        });
        offlineBox.getChildren().addAll(new Label("Play offline with any username:"), usernameField, offlineButton, offlineNote);
        Tab offlineTab = new Tab("Offline Account", offlineBox);
        offlineTab.setClosable(false);

        // ---- Microsoft tab ----
        VBox msBox = new VBox(12);
        msBox.setPadding(new Insets(20));
        Label instructions = new Label("Sign in with your Microsoft account (the one that owns Minecraft).");
        Button startButton = new Button("Sign in with Microsoft");
        Label codeLabel = new Label();
        codeLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        Label urlLabel = new Label();
        Button copyButton = new Button("Copy code");
        copyButton.setVisible(false);
        ProgressIndicator progress = new ProgressIndicator();
        progress.setVisible(false);
        Label statusLabel = new Label();
        AtomicBoolean cancelled = new AtomicBoolean(false);

        startButton.setOnAction(e -> {
            startButton.setDisable(true);
            progress.setVisible(true);
            statusLabel.setText("Requesting device code...");
            cancelled.set(false);

            new Thread(() -> {
                MicrosoftAuthService auth = new MicrosoftAuthService();
                try {
                    DeviceCodeInfo info = auth.requestDeviceCode();
                    Platform.runLater(() -> {
                        codeLabel.setText(info.userCode);
                        urlLabel.setText("Go to " + info.verificationUri + " and enter the code above.");
                        copyButton.setVisible(true);
                        statusLabel.setText("Waiting for you to sign in in your browser...");
                    });

                    var msTokens = auth.pollForToken(info, cancelled::get);
                    Platform.runLater(() -> statusLabel.setText("Verifying with Xbox Live and Minecraft services..."));
                    Account account = auth.completeLogin(msTokens);

                    Platform.runLater(() -> {
                        onSuccess.accept(account);
                        stage.close();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        progress.setVisible(false);
                        startButton.setDisable(false);
                        statusLabel.setText("Failed: " + ex.getMessage());
                    });
                }
            }, "ms-auth").start();
        });

        copyButton.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(codeLabel.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });

        msBox.getChildren().addAll(instructions, startButton, codeLabel, urlLabel, copyButton, progress, statusLabel);
        Tab msTab = new Tab("Microsoft Account", msBox);
        msTab.setClosable(false);

        tabs.getTabs().addAll(msTab, offlineTab);

        stage.setOnCloseRequest(e -> cancelled.set(true));
        stage.setScene(new Scene(tabs, 460, 360));
        stage.showAndWait();
    }
}
