package com.launcher.ui;

import com.launcher.minecraft.FabricInstaller;
import com.launcher.minecraft.VersionManifestService;
import com.launcher.model.Instance;
import com.launcher.model.ModLoaderType;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.util.List;
import java.util.Optional;

public class CreateInstanceDialog {

    public static Optional<Instance> show(Stage owner) {
        Dialog<Instance> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("New Instance");
        dialog.setHeaderText("Create a new Minecraft instance");

        ButtonType createButtonType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createButtonType, ButtonType.CANCEL);

        TextField nameField = new TextField("New Instance");

        ComboBox<String> versionBox = new ComboBox<>();
        versionBox.setEditable(false);
        versionBox.setPromptText("Loading versions...");

        ComboBox<ModLoaderType> loaderBox = new ComboBox<>();
        loaderBox.getItems().addAll(ModLoaderType.VANILLA, ModLoaderType.FABRIC, ModLoaderType.FORGE);
        loaderBox.setValue(ModLoaderType.VANILLA);

        ComboBox<String> loaderVersionBox = new ComboBox<>();
        loaderVersionBox.setPromptText("(vanilla - no loader needed)");
        loaderVersionBox.setDisable(true);

        RadioButton defaultDirRadio = new RadioButton("Use default launcher directory");
        RadioButton customDirRadio = new RadioButton("Use a custom directory (e.g. an existing .minecraft folder)");
        ToggleGroup dirGroup = new ToggleGroup();
        defaultDirRadio.setToggleGroup(dirGroup);
        customDirRadio.setToggleGroup(dirGroup);
        defaultDirRadio.setSelected(true);

        TextField customDirField = new TextField();
        customDirField.setDisable(true);
        Button browseButton = new Button("Browse...");
        browseButton.setDisable(true);
        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            var dir = chooser.showDialog(owner);
            if (dir != null) customDirField.setText(dir.getAbsolutePath());
        });
        customDirRadio.selectedProperty().addListener((obs, was, isNow) -> {
            customDirField.setDisable(!isNow);
            browseButton.setDisable(!isNow);
        });

        // Load MC versions in background so the dialog opens instantly.
        new Thread(() -> {
            try {
                List<String> ids = new VersionManifestService().fetchVersionUrls().keySet().stream().toList();
                Platform.runLater(() -> {
                    versionBox.getItems().addAll(ids);
                    if (!ids.isEmpty()) versionBox.setValue(ids.get(0));
                    versionBox.setPromptText(null);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> versionBox.setPromptText("Failed to load (check connection)"));
            }
        }, "fetch-versions").start();

        Runnable refreshLoaderVersions = () -> {
            loaderVersionBox.getItems().clear();
            ModLoaderType loader = loaderBox.getValue();
            String mcVersion = versionBox.getValue();
            if (loader == ModLoaderType.VANILLA || mcVersion == null) {
                loaderVersionBox.setDisable(true);
                loaderVersionBox.setPromptText("(no loader needed)");
                return;
            }
            if (loader == ModLoaderType.FORGE) {
                loaderVersionBox.setDisable(false);
                loaderVersionBox.getItems().addAll("Recommended", "Latest");
                loaderVersionBox.setValue("Recommended");
                return;
            }
            // FABRIC
            loaderVersionBox.setDisable(true);
            loaderVersionBox.setPromptText("Loading loader versions...");
            new Thread(() -> {
                try {
                    List<String> versions = new FabricInstaller().fetchLoaderVersions(mcVersion);
                    Platform.runLater(() -> {
                        loaderVersionBox.getItems().setAll(versions);
                        if (!versions.isEmpty()) loaderVersionBox.setValue(versions.get(0));
                        loaderVersionBox.setDisable(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> loaderVersionBox.setPromptText("Failed to load"));
                }
            }, "fetch-fabric-loaders").start();
        };
        loaderBox.valueProperty().addListener((o, a, b) -> refreshLoaderVersions.run());
        versionBox.valueProperty().addListener((o, a, b) -> refreshLoaderVersions.run());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        int r = 0;
        grid.addRow(r++, new Label("Instance name:"), nameField);
        grid.addRow(r++, new Label("Minecraft version:"), versionBox);
        grid.addRow(r++, new Label("Mod loader:"), loaderBox);
        grid.addRow(r++, new Label("Loader version:"), loaderVersionBox);
        grid.add(new Label("Directory:"), 0, r);
        grid.add(defaultDirRadio, 1, r++);
        grid.add(customDirRadio, 1, r++);
        javafx.scene.layout.HBox dirRow = new javafx.scene.layout.HBox(8, customDirField, browseButton);
        customDirField.setPrefWidth(280);
        grid.add(dirRow, 1, r++);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(buttonType -> {
            if (buttonType != createButtonType) return null;
            if (nameField.getText().isBlank() || versionBox.getValue() == null) return null;
            String loaderVersion = loaderBox.getValue() == ModLoaderType.VANILLA ? null : loaderVersionBox.getValue();
            Instance instance = new Instance(nameField.getText().trim(), versionBox.getValue(), loaderBox.getValue(), loaderVersion);
            instance.useCustomDirectory = customDirRadio.isSelected();
            instance.customDirectoryPath = customDirField.getText();
            return instance;
        });

        return dialog.showAndWait();
    }
}
