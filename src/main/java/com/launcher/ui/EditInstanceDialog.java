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
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import com.launcher.manager.LauncherPaths;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class EditInstanceDialog {

    public static Optional<Instance> show(Stage owner, Instance inst) {
        Dialog<Instance> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Edit Instance — " + inst.name);
        dialog.setHeaderText("Editing: " + inst.name);
        dialog.getDialogPane().setPrefWidth(520);

        var css = EditInstanceDialog.class.getResource("/com/launcher/styles.css");
        if (css != null) dialog.getDialogPane().getStylesheets().add(css.toExternalForm());
        com.launcher.model.LauncherSettings settings = com.launcher.manager.SettingsManager.getInstance().getSettings();
        Main.applyThemeToPane(dialog.getDialogPane(), settings);

        ButtonType saveBtn = new ButtonType("Save Changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        // ─────────────────────────────────────────────────────────────────────
        //  Fields (pre-populated)
        // ─────────────────────────────────────────────────────────────────────
        TextField nameField = new TextField(inst.name);

        ComboBox<String> versionBox = new ComboBox<>();
        versionBox.setMaxWidth(Double.MAX_VALUE);
        versionBox.setPromptText("Loading…");
        versionBox.setDisable(true);

        CheckBox snapshotsCb = new CheckBox("Include snapshots");

        ComboBox<ModLoaderType> loaderBox = new ComboBox<>();
        loaderBox.setMaxWidth(Double.MAX_VALUE);
        loaderBox.getItems().addAll(ModLoaderType.VANILLA, ModLoaderType.FABRIC, ModLoaderType.FORGE);
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
            loaderVerBox.setDisable(true); loaderVerBox.setPromptText("Loading…");
            new Thread(() -> {
                try {
                    List<String> vers = new FabricInstaller().fetchLoaderVersions(mcVer);
                    Platform.runLater(() -> {
                        loaderVerBox.getItems().setAll(vers);
                        loaderVerBox.setValue(inst.modLoaderVersion);
                        loaderVerBox.setDisable(false);
                    });
                } catch (Exception ex) { Platform.runLater(() -> loaderVerBox.setPromptText("Failed to load")); }
            }, "fetch-loader-vers").start();
        };
        loaderBox.valueProperty().addListener((o, a, b) -> loadLoaderVers.run());
        versionBox.valueProperty().addListener((o, a, b) -> loadLoaderVers.run());
        loadLoaderVers.run();

        // ─────────────────────────────────────────────────────────────────────
        //  Layout
        // ─────────────────────────────────────────────────────────────────────
        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(12);
        grid.setPadding(new Insets(20));
        ColumnConstraints lCol = new ColumnConstraints(); lCol.setMinWidth(140); lCol.setHgrow(Priority.NEVER);
        ColumnConstraints rCol = new ColumnConstraints(); rCol.setHgrow(Priority.ALWAYS);
        ColumnConstraints xCol = new ColumnConstraints(); xCol.setMinWidth(130);
        grid.getColumnConstraints().addAll(lCol, rCol, xCol);

        int r = 0;
        addSectionHeader(grid, "General", r++);
        grid.addRow(r++, fl("Name"),           nameField,    new Label());
        grid.add(fl("MC Version"), 0, r);
        grid.add(versionBox, 1, r);
        grid.add(snapshotsCb, 2, r++);
        grid.addRow(r++, fl("Mod loader"),     loaderBox,    new Label());
        grid.addRow(r++, fl("Loader version"), loaderVerBox, new Label());

        addSectionHeader(grid, "Memory", r++);
        VBox ramBox = new VBox(4, ramSlider, ramLbl);
        grid.addRow(r++, fl("Allocated RAM"),  ramBox,       new Label());

        addSectionHeader(grid, "Directory", r++);
        grid.add(fl("Game directory"), 0, r);
        grid.add(defaultDirRadio, 1, r++, 2, 1);
        grid.add(customDirRadio, 1, r++, 2, 1);
        HBox dirRow = new HBox(8, customDirField, browseBtn);
        HBox.setHgrow(customDirField, Priority.ALWAYS);
        grid.add(dirRow, 1, r++, 2, 1);

        addSectionHeader(grid, "Visibility", r++);
        grid.add(hiddenCb, 1, r++, 2, 1);

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        dialog.getDialogPane().setContent(scroll);

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
            return inst;
        });

        return dialog.showAndWait();
    }

    private static void addSectionHeader(GridPane grid, String text, int row) {
        Label l = new Label(text.toUpperCase());
        l.setStyle("-fx-font-size:10px; -fx-font-weight:bold; -fx-text-fill:-fx-text-muted; -fx-letter-spacing:1.5px; -fx-padding:8px 0 2px 0;");
        Separator sep = new Separator();
        HBox h = new HBox(8, l, sep);
        HBox.setHgrow(sep, Priority.ALWAYS);
        h.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        grid.add(h, 0, row, 3, 1);
    }

    private static Label fl(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:12px; -fx-text-fill:-fx-text-color;");
        return l;
    }
}
