package com.launcher.ui;

import com.launcher.auth.OfflineAuthService;
import com.launcher.model.Account;
import com.launcher.Main;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.function.Consumer;

public class LoginDialog {

    public static void show(Stage owner, Consumer<Account> onSuccess) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initStyle(StageStyle.DECORATED);   // decorated = has title bar → moveable
        stage.setTitle("Add Account");
        stage.setResizable(true);

        // ── Header ────────────────────────────────────────────────────────────
        Label heading = new Label("Add Account");
        heading.setStyle("-fx-font-size:18px; -fx-font-weight:bold; -fx-text-fill:#ffffff;");
        Label sub = new Label("Offline accounts work for singleplayer and offline-mode servers.");
        sub.setWrapText(true);
        sub.setStyle("-fx-font-size:12px; -fx-text-fill:-fx-text-muted;");
        VBox header = new VBox(4, heading, sub);
        header.setPadding(new Insets(20, 20, 16, 20));
        header.setStyle("-fx-background-color:-fx-surface; -fx-border-color:transparent transparent -fx-border-subtle transparent; -fx-border-width:0 0 1px 0;");

        // ── Form ──────────────────────────────────────────────────────────────
        Label userLabel = new Label("Username");
        userLabel.setStyle("-fx-font-size:12px; -fx-font-weight:bold; -fx-text-fill:-fx-text-color;");

        TextField usernameField = new TextField();
        usernameField.setPromptText("Enter a username…");
        usernameField.setPrefWidth(340);

        Label note = new Label("⚠  Microsoft authentication is not supported. Use the in-game account switcher if you need it.");
        note.setWrapText(true);
        note.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-text-muted; -fx-padding:4px 0 0 0;");

        // ── Buttons ───────────────────────────────────────────────────────────
        Button addBtn    = new Button("Add Offline Account");
        addBtn.setId("playButton");
        addBtn.setPrefWidth(180);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setPrefWidth(90);
        cancelBtn.setOnAction(e -> stage.close());

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill:#ef4444; -fx-font-size:11px;");

        addBtn.setOnAction(e -> {
            String u = usernameField.getText().trim();
            if (u.isBlank()) { errorLabel.setText("Username cannot be empty."); return; }
            try {
                Account acc = new OfflineAuthService().login(u);
                onSuccess.accept(acc);
                stage.close();
            } catch (Exception ex) {
                errorLabel.setText(ex.getMessage());
            }
        });

        usernameField.setOnAction(e -> addBtn.fire());

        HBox btnRow = new HBox(8, cancelBtn, addBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox form = new VBox(10, userLabel, usernameField, note, errorLabel, btnRow);
        form.setPadding(new Insets(20));

        // ── Root — solid panel background, no background image ────────────────
        VBox root = new VBox(header, form);

        Scene scene = new Scene(root, 440, 310);
        var css = LoginDialog.class.getResource("/com/launcher/styles.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        com.launcher.model.LauncherSettings settings =
                com.launcher.manager.SettingsManager.getInstance().getSettings();
        // Apply theme colors but suppress background image
        Main.applyThemeToPane(root, settings);
        root.setStyle(root.getStyle() + "; -fx-background-image: none;"
                + " -fx-background-color: -fx-panel-background;");

        stage.setScene(scene);
        stage.showAndWait();
    }
}
