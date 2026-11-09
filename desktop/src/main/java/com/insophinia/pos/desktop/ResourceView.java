package com.insophinia.pos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Reusable read-oriented management screen. Mutations remain permission-gated by the REST API. */
public final class ResourceView {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final BorderPane root = new BorderPane();
    private final String title;
    private final String endpoint;
    private final TextArea output = new TextArea();

    public ResourceView(String title, String endpoint) {
        this.title = title; this.endpoint = endpoint;
        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        Label note = new Label("Live data from the Spring Boot API. Server-side RBAC remains authoritative.");
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refresh());
        HBox actions = new HBox(12, heading, refresh);
        actions.setPadding(new Insets(12));
        VBox top = new VBox(6, actions, note);
        top.setPadding(new Insets(8, 12, 12, 12));
        output.setEditable(false); output.setWrapText(false);
        output.setStyle("-fx-font-family: monospace;");
        root.setTop(top); root.setCenter(output); BorderPane.setMargin(output, new Insets(0, 12, 12, 12));
        refresh();
    }

    public BorderPane getRoot() { return root; }
    public void refresh() {
        output.setText("Loading " + title + "…");
        Thread t = new Thread(() -> {
            try {
                JsonNode response = ApiClient.get(endpoint);
                String pretty = JSON.writeValueAsString(response);
                Platform.runLater(() -> output.setText(pretty));
            } catch (Exception ex) {
                Platform.runLater(() -> output.setText("Unable to load " + title + ": " + ex.getMessage()
                        + "\n\nCheck your permission and backend connection, then select Refresh."));
            }
        }, "pos-resource-" + title.toLowerCase().replace(' ', '-'));
        t.setDaemon(true); t.start();
    }
}
