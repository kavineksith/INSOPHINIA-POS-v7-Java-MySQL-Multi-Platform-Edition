package com.insophinia.pos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Map;

public class LoginView {

    private final VBox root;

    public LoginView(Runnable onSuccess) {
        Label title = new Label("INSOPHINIA POS");
        title.setStyle("-fx-font-size: 26px; -fx-font-weight: bold;");
        Label subtitle = new Label("Sign in to continue");

        TextField username = new TextField();
        username.setPromptText("Username");
        PasswordField password = new PasswordField();
        password.setPromptText("Password");
        TextField server = new TextField(Session.baseUrl);
        server.setPromptText("Server URL");

        Label error = new Label();
        error.setStyle("-fx-text-fill: #c0392b;");

        Button setup = new Button("Local database setup…");
        setup.setMaxWidth(Double.MAX_VALUE);
        setup.setOnAction(e -> { new LocalSetupWizard().show(); server.setText(Session.baseUrl); });

        Button login = new Button("Sign in");
        login.setDefaultButton(true);
        login.setMaxWidth(Double.MAX_VALUE);

        login.setOnAction(e -> {
            error.setText("");
            login.setDisable(true);
            String previousBaseUrl = Session.baseUrl;
            Session.baseUrl = server.getText().trim();
            try { Session.baseUrl = Session.validatedBaseUrl(); }
            catch (Exception ex) { Session.baseUrl = previousBaseUrl; error.setText(ex.getMessage()); login.setDisable(false); return; }
            new Thread(() -> {
                try {
                    JsonNode res = ApiClient.post("/api/auth/login",
                            Map.of("username", username.getText().trim(), "password", password.getText()));
                    JsonNode data = res.get("data");
                    if (data.has("requires2fa") && data.get("requires2fa").asBoolean()) {
                        Platform.runLater(() -> prompt2fa(data.get("pendingToken").asText(), onSuccess, error));
                        return;
                    }
                    handleSuccessfulLogin(data, onSuccess, error);
                } catch (ApiClient.ApiException ex) {
                    Platform.runLater(() -> error.setText(ex.getMessage()));
                } finally {
                    Platform.runLater(() -> login.setDisable(false));
                }
            }).start();
        });

        root = new VBox(14, title, subtitle, server, username, password, login, setup, error);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(40));
        root.setMaxWidth(360);
        root.setStyle("-fx-background-color: white;");
    }

    private void prompt2fa(String pendingToken, Runnable onSuccess, Label error) {
        var dialog = new javafx.scene.control.TextInputDialog();
        dialog.setTitle("Two-Factor Authentication");
        dialog.setHeaderText("Enter the 6-digit code from your authenticator app");
        dialog.showAndWait().ifPresent(code -> new Thread(() -> {
            try {
                JsonNode res = ApiClient.post("/api/auth/2fa/verify",
                        Map.of("pendingToken", pendingToken, "code", code.trim()));
                handleSuccessfulLogin(res.get("data"), onSuccess, error);
            } catch (ApiClient.ApiException ex) {
                Platform.runLater(() -> error.setText(ex.getMessage()));
            }
        }).start());
    }

    private void handleSuccessfulLogin(JsonNode data, Runnable onSuccess, Label error) {
        applySession(data);
        if (data.path("mustChangePassword").asBoolean(false)) {
            Platform.runLater(() -> promptForcedPasswordChange(error));
        } else {
            Platform.runLater(onSuccess);
        }
    }

    private void promptForcedPasswordChange(Label error) {
        var dialog = new javafx.scene.control.Dialog<Map<String, String>>();
        dialog.setTitle("Password change required");
        dialog.setHeaderText("Change your temporary password before using the POS");
        var current = new PasswordField(); current.setPromptText("Current password");
        var next = new PasswordField(); next.setPromptText("New password (12–30 characters)");
        var confirm = new PasswordField(); confirm.setPromptText("Confirm new password");
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10); grid.setVgap(10); grid.addRow(0, new Label("Current"), current);
        grid.addRow(1, new Label("New"), next); grid.addRow(2, new Label("Confirm"), confirm);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == javafx.scene.control.ButtonType.OK
                ? Map.of("current", current.getText(), "next", next.getText(), "confirm", confirm.getText()) : null);
        var result = dialog.showAndWait();
        if (result.isEmpty()) { Session.clear(); return; }
        result.ifPresent(values -> {
            if (!values.get("next").equals(values.get("confirm"))) { error.setText("New passwords do not match. Sign in again to retry."); Session.clear(); return; }
            new Thread(() -> {
                try {
                    ApiClient.post("/api/auth/change-password", Map.of("currentPassword", values.get("current"), "newPassword", values.get("next")));
                    Session.clear();
                    Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION,
                            "Password changed successfully. Please sign in again with your new password.").showAndWait());
                } catch (ApiClient.ApiException ex) {
                    Session.clear(); Platform.runLater(() -> error.setText("Password change failed: " + ex.getMessage() + ". Sign in again to retry."));
                }
            }, "forced-password-change").start();
        });
    }

    static void applySession(JsonNode data) {
        Session.token = data.get("token").asText();
        Session.username = data.get("username").asText();
        Session.role = data.get("role").asText();
        Session.fullName = data.get("fullName").asText();
        var perms = new ArrayList<String>();
        data.withArray("permissions").forEach(p -> perms.add(p.asText()));
        Session.permissions = perms;
    }

    public VBox getRoot() { return root; }
}
