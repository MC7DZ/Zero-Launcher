package com.launcher.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Small toast-notification system that lives in the top-right corner of the window.
 * Used so background work (mod scans, update checks, dependency installs, discover
 * downloads, ...) can tell the user what's happening without touching — and never
 * blanking — whatever list/grid is currently on screen.
 */
public final class NotificationCenter {

    public enum Type { INFO, SUCCESS, WARNING, ERROR }

    private final VBox stack;

    /**
     * @param overlayHost a StackPane that sits above the rest of the UI (mouse-transparent
     *                    except for the toasts themselves) — typically the outermost
     *                    StackPane wrapping the app's root layout.
     */
    public NotificationCenter(StackPane overlayHost) {
        stack = new VBox(8);
        stack.setPickOnBounds(false);
        stack.setMaxWidth(340);
        stack.setMinWidth(300);
        stack.setPadding(new Insets(16));
        StackPane.setAlignment(stack, Pos.TOP_RIGHT);

        overlayHost.getChildren().add(stack);
    }

    public void info(String title, String message)    { show(Type.INFO, title, message); }
    public void success(String title, String message)  { show(Type.SUCCESS, title, message); }
    public void warning(String title, String message)  { show(Type.WARNING, title, message); }
    public void error(String title, String message)    { show(Type.ERROR, title, message); }

    /** Shows a toast. Safe to call from any thread. */
    public void show(Type type, String title, String message) {
        if (Platform.isFxApplicationThread()) {
            display(type, title, message);
        } else {
            Platform.runLater(() -> display(type, title, message));
        }
    }

    private void display(Type type, String title, String message) {
        String accentVar = switch (type) {
            case SUCCESS -> "-fx-accent-color";
            case WARNING -> "-fx-warning";
            case ERROR   -> "-fx-danger";
            case INFO    -> "-fx-text-muted";
        };
        String icon = switch (type) {
            case SUCCESS -> "✔";
            case WARNING -> "⚠";
            case ERROR   -> "✕";
            case INFO    -> "ℹ";
        };

        Region stripe = new Region();
        stripe.setPrefWidth(3);
        stripe.setMaxHeight(Double.MAX_VALUE);
        stripe.setStyle("-fx-background-color:" + accentVar + "; -fx-background-radius:3px;");

        Label iconLabel = new Label(icon);
        iconLabel.setStyle("-fx-font-size:14px; -fx-text-fill:" + accentVar + "; -fx-font-weight:bold;");

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size:12px; -fx-font-weight:bold; -fx-text-fill:-fx-text-color;");
        titleLabel.setWrapText(true);

        VBox textCol = new VBox(3, titleLabel);
        textCol.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(textCol, Priority.ALWAYS);

        if (message != null && !message.isBlank()) {
            Label msgLabel = new Label(message);
            msgLabel.setStyle("-fx-font-size:11px; -fx-text-fill:-fx-text-muted;");
            msgLabel.setWrapText(true);
            textCol.getChildren().add(msgLabel);
        }

        HBox card = new HBox(10, stripe, iconLabel, textCol);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(10, 12, 10, 10));
        card.setMaxWidth(320);
        card.setPrefWidth(320);
        card.setStyle(
                "-fx-background-color:-fx-surface;" +
                "-fx-background-radius:10px;" +
                "-fx-border-color:-fx-border-subtle;" +
                "-fx-border-radius:10px;" +
                "-fx-border-width:1px;" +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 14, 0.15, 0, 4);"
        );
        card.setOpacity(0);
        card.setTranslateX(60);

        // Newest toast on top.
        stack.getChildren().add(0, card);

        TranslateTransition slideIn = new TranslateTransition(Duration.millis(220), card);
        slideIn.setFromX(60);
        slideIn.setToX(0);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(220), card);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);

        PauseTransition hold = new PauseTransition(Duration.seconds(type == Type.ERROR ? 7 : 5));

        FadeTransition fadeOut = new FadeTransition(Duration.millis(260), card);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);
        TranslateTransition slideOut = new TranslateTransition(Duration.millis(260), card);
        slideOut.setFromX(0);
        slideOut.setToX(60);

        // Click to dismiss early.
        card.setOnMouseClicked(e -> { hold.stop(); fadeOut.play(); slideOut.play(); });

        fadeOut.setOnFinished(e -> stack.getChildren().remove(card));

        slideIn.play();
        fadeIn.play();
        hold.setOnFinished(e -> { fadeOut.play(); slideOut.play(); });
        hold.play();
    }
}
