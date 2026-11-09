package com.insophinia.pos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.*;

/** Editable application settings plus SMTP test; SMTP credentials remain environment-only. */
public final class SettingsView {
    private final BorderPane root=new BorderPane();
    private final TableView<JsonNode> table=new TableView<>();
    private final TextField value=new TextField();
    private final Label status=new Label("Ready");
    public SettingsView(){
        Label title=new Label("Application settings");title.setStyle("-fx-font-size:20px;-fx-font-weight:bold;");
        Button refresh=new Button("Refresh"),save=new Button("Save selected value"),test=new Button("Send test email");refresh.setOnAction(e->load());save.setOnAction(e->save());test.setOnAction(e->testEmail());
        HBox actions=new HBox(8,title,new javafx.scene.layout.Region(),refresh);HBox.setHgrow(actions.getChildren().get(1),javafx.scene.layout.Priority.ALWAYS);actions.setPadding(new Insets(12));
        table.getColumns().setAll(col("Key","key"),col("Value","value"),col("Type","type"),col("Description","description"));table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().selectedItemProperty().addListener((o,a,n)->{if(n!=null)value.setText(n.path("value").asText(""));});
        value.setPromptText("New setting value");save.setDisable(!Session.has("manage:settings"));test.setDisable(!Session.has("manage:settings"));
        VBox bottom=new VBox(8,new HBox(8,new Label("Selected value:"),value,save),status);bottom.setPadding(new Insets(12));root.setTop(new VBox(actions,new Label("SMTP credentials are configured through the local setup environment file and are never shown here.")));root.setCenter(table);root.setBottom(new VBox(bottom,test));load();
    }
    public BorderPane getRoot(){return root;}
    private TableColumn<JsonNode,String> col(String title,String field){TableColumn<JsonNode,String> c=new TableColumn<>(title);c.setCellValueFactory(v->new SimpleStringProperty(v.getValue().path(field).asText("")));return c;}
    private void load(){new Thread(()->{try{JsonNode d=ApiClient.get("/api/settings").path("data");List<JsonNode> rows=new ArrayList<>();if(d.isArray())d.forEach(rows::add);Platform.runLater(()->{table.getItems().setAll(rows);status.setText(rows.size()+" settings loaded");});}catch(Exception e){Platform.runLater(()->status.setText("Load failed: "+e.getMessage()));}},"settings-load").start();}
    private void save(){JsonNode selected=table.getSelectionModel().getSelectedItem();if(selected==null){new Alert(Alert.AlertType.INFORMATION,"Select a setting first.").showAndWait();return;}String key=selected.path("key").asText();new Thread(()->{try{ApiClient.put("/api/settings",Map.of("settings",Map.of(key,value.getText())));Platform.runLater(()->{status.setText("Saved "+key);load();});}catch(Exception e){Platform.runLater(()->new Alert(Alert.AlertType.ERROR,"Save failed: "+e.getMessage()).showAndWait());}},"settings-save").start();}
    private void testEmail(){TextInputDialog d=new TextInputDialog();d.setTitle("Test SMTP");d.setHeaderText("Send a test email using the configured SMTP server");d.setContentText("Recipient email:");d.showAndWait().ifPresent(to->new Thread(()->{try{ApiClient.post("/api/settings/test-email",Map.of("to",to.trim()));Platform.runLater(()->new Alert(Alert.AlertType.INFORMATION,"Test email sent.").showAndWait());}catch(Exception e){Platform.runLater(()->new Alert(Alert.AlertType.ERROR,"Email test failed: "+e.getMessage()).showAndWait());}},"smtp-test").start());}
}
