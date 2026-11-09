package com.insophinia.pos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import java.io.File;
import java.nio.file.Path;
import java.util.*;

/** System logs with aggregate summary, CSV export and permission-gated retention cleanup. */
public final class LogsView {
    private final BorderPane root=new BorderPane();private final TableView<JsonNode> table=new TableView<>();private final Label status=new Label("Ready");
    public LogsView(){Label title=new Label("System logs & events");title.setStyle("-fx-font-size:20px;-fx-font-weight:bold;");Button refresh=new Button("Refresh"),aggregate=new Button("Aggregate"),export=new Button("Export CSV"),cleanup=new Button("Cleanup retention");refresh.setOnAction(e->load());aggregate.setOnAction(e->aggregate());export.setOnAction(e->export());cleanup.setOnAction(e->cleanup());cleanup.setDisable(!Session.has("manage:settings"));HBox actions=new HBox(8,title,new javafx.scene.layout.Region(),refresh,aggregate,export,cleanup);HBox.setHgrow(actions.getChildren().get(1),javafx.scene.layout.Priority.ALWAYS);actions.setPadding(new Insets(12));table.getColumns().setAll(col("Time","createdAt"),col("Level","level"),col("Category","category"),col("Action","action"),col("Message","message"),col("User","userId"));table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);root.setTop(actions);root.setCenter(table);root.setBottom(status);BorderPane.setMargin(status,new Insets(8));load();}
    public BorderPane getRoot(){return root;}private TableColumn<JsonNode,String> col(String title,String key){TableColumn<JsonNode,String> c=new TableColumn<>(title);c.setCellValueFactory(v->new SimpleStringProperty(v.getValue().path(key).asText("")));return c;}
    private void load(){new Thread(()->{try{JsonNode d=ApiClient.get("/api/logs?page=1&limit=100").path("data");List<JsonNode> rows=new ArrayList<>();if(d.isArray())d.forEach(rows::add);Platform.runLater(()->{table.getItems().setAll(rows);status.setText(rows.size()+" recent log entries");});}catch(Exception e){Platform.runLater(()->status.setText("Log load failed: "+e.getMessage()));}},"logs-load").start();}
    private void aggregate(){new Thread(()->{try{JsonNode d=ApiClient.get("/api/logs/aggregate").path("data");Platform.runLater(()->new Alert(Alert.AlertType.INFORMATION,"Log aggregation\n\n"+d.toPrettyString()).showAndWait());}catch(Exception e){Platform.runLater(()->new Alert(Alert.AlertType.ERROR,e.getMessage()).showAndWait());}},"logs-aggregate").start();}
    private void export(){FileChooser c=new FileChooser();c.setTitle("Export logs CSV");c.setInitialFileName("insophinia-logs.csv");c.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV","*.csv"));File f=c.showSaveDialog(root.getScene().getWindow());if(f==null)return;new Thread(()->{try{ApiClient.download("/api/logs/export",Path.of(f.toURI()));Platform.runLater(()->new Alert(Alert.AlertType.INFORMATION,"Log export saved.").showAndWait());}catch(Exception e){Platform.runLater(()->new Alert(Alert.AlertType.ERROR,"Export failed: "+e.getMessage()).showAndWait());}},"logs-export").start();}
    private void cleanup(){TextInputDialog d=new TextInputDialog("24");d.setTitle("Log retention cleanup");d.setHeaderText("Permanently delete logs older than the selected retention period");d.setContentText("Retention hours (minimum 24):");d.showAndWait().ifPresent(hours->{try{int n=Integer.parseInt(hours);if(n<24)throw new NumberFormatException();if(new Alert(Alert.AlertType.CONFIRMATION,"Delete logs older than "+n+" hours?",ButtonType.CANCEL,ButtonType.OK).showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;new Thread(()->{try{JsonNode r=ApiClient.post("/api/logs/cleanup?retentionHours="+n,null);Platform.runLater(()->{new Alert(Alert.AlertType.INFORMATION,r.path("data").toPrettyString()).showAndWait();load();});}catch(Exception e){Platform.runLater(()->new Alert(Alert.AlertType.ERROR,e.getMessage()).showAndWait());}},"logs-cleanup").start();}catch(Exception e){new Alert(Alert.AlertType.WARNING,"Enter a valid integer of at least 24 hours.").showAndWait();}});}
}
