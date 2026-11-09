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

/** Backup management: create, verify/preview, download, restore with re-auth, and delete. */
public final class BackupsView {
    private final BorderPane root=new BorderPane();private final TableView<JsonNode> table=new TableView<>();private final Label status=new Label("Ready");
    public BackupsView(){Label title=new Label("Encrypted backups & recovery");title.setStyle("-fx-font-size:20px;-fx-font-weight:bold;");Button refresh=new Button("Refresh"),create=new Button("Create backup"),preview=new Button("Verify / preview"),download=new Button("Download"),restore=new Button("Restore"),delete=new Button("Delete");refresh.setOnAction(e->load());create.setOnAction(e->create());preview.setOnAction(e->selected(this::preview));download.setOnAction(e->selected(this::download));restore.setOnAction(e->selected(this::restore));delete.setOnAction(e->selected(this::delete));
        create.setDisable(!Session.has("manage:settings"));preview.setDisable(!Session.has("manage:settings"));download.setDisable(!Session.has("manage:settings"));restore.setDisable(!Session.has("manage:settings"));delete.setDisable(!Session.has("manage:settings"));
        HBox actions=new HBox(8,title,new javafx.scene.layout.Region(),refresh,create,preview,download,restore,delete);HBox.setHgrow(actions.getChildren().get(1),javafx.scene.layout.Priority.ALWAYS);actions.setPadding(new Insets(12));table.getColumns().setAll(col("ID","id"),col("File","filename"),col("Type","backupType"),col("Created","createdAt"),col("Size","fileSize"));table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);root.setTop(new VBox(actions,new Label("Restore replaces allowlisted business data. Re-authentication and typed RESTORE confirmation are required.")));root.setCenter(table);root.setBottom(status);BorderPane.setMargin(status,new Insets(8));load();}
    public BorderPane getRoot(){return root;}private TableColumn<JsonNode,String> col(String n,String k){TableColumn<JsonNode,String> c=new TableColumn<>(n);c.setCellValueFactory(v->new SimpleStringProperty(v.getValue().path(k).asText("")));return c;}
    private void selected(java.util.function.Consumer<JsonNode> c){JsonNode n=table.getSelectionModel().getSelectedItem();if(n==null){alert(Alert.AlertType.INFORMATION,"Select a backup first.");return;}c.accept(n);}
    private void load(){new Thread(()->{try{JsonNode d=ApiClient.get("/api/backups").path("data");List<JsonNode> rows=new ArrayList<>();if(d.isArray())d.forEach(rows::add);Platform.runLater(()->{table.getItems().setAll(rows);status.setText(rows.size()+" backups registered");});}catch(Exception e){Platform.runLater(()->status.setText("Backup list failed: "+e.getMessage()));}},"backup-load").start();}
    private void create(){new Thread(()->{try{ApiClient.post("/api/backups",null);Platform.runLater(()->{alert(Alert.AlertType.INFORMATION,"Encrypted backup created.");load();});}catch(Exception e){Platform.runLater(()->alert(Alert.AlertType.ERROR,"Backup failed: "+e.getMessage()));}},"backup-create").start();}
    private void preview(JsonNode b){new Thread(()->{try{JsonNode r=ApiClient.post("/api/backups/restore/"+b.path("id").asText(),null);Platform.runLater(()->alert(Alert.AlertType.INFORMATION,"Backup verification result:\n"+r.path("data").toPrettyString()));}catch(Exception e){Platform.runLater(()->alert(Alert.AlertType.ERROR,"Verification failed: "+e.getMessage()));}},"backup-preview").start();}
    private void download(JsonNode b){FileChooser c=new FileChooser();c.setTitle("Download encrypted backup");c.setInitialFileName(b.path("filename").asText("insophinia.posbak"));File f=c.showSaveDialog(root.getScene().getWindow());if(f==null)return;new Thread(()->{try{ApiClient.download("/api/backups/"+b.path("id").asText()+"/download",Path.of(f.toURI()));Platform.runLater(()->alert(Alert.AlertType.INFORMATION,"Backup downloaded."));}catch(Exception e){Platform.runLater(()->alert(Alert.AlertType.ERROR,"Download failed: "+e.getMessage()));}},"backup-download").start();}
    private void restore(JsonNode b){PasswordField pass=new PasswordField();pass.setPromptText("Your current password");TextField confirm=new TextField();confirm.setPromptText("Type RESTORE exactly");VBox box=new VBox(8,new Label("This replaces allowlisted business data. A safety backup will be created first."),pass,confirm);box.setPadding(new Insets(12));Alert a=new Alert(Alert.AlertType.CONFIRMATION);a.setTitle("Restore backup");a.setHeaderText("Destructive operation — verify the backup first");a.getDialogPane().setContent(box);a.getButtonTypes().setAll(ButtonType.CANCEL,ButtonType.OK);if(a.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;if(!confirm.getText().equals("RESTORE")){alert(Alert.AlertType.WARNING,"Confirmation must be exactly RESTORE.");return;}new Thread(()->{try{ApiClient.post("/api/backups/restore",Map.of("backupId",b.path("id").asText(),"currentPassword",pass.getText(),"confirmation",confirm.getText()));Platform.runLater(()->{alert(Alert.AlertType.INFORMATION,"Restore completed. Re-login may be required.");load();});}catch(Exception e){Platform.runLater(()->alert(Alert.AlertType.ERROR,"Restore failed: "+e.getMessage()));}},"backup-restore").start();}
    private void delete(JsonNode b){if(new Alert(Alert.AlertType.CONFIRMATION,"Delete selected backup permanently?",ButtonType.CANCEL,ButtonType.OK).showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;new Thread(()->{try{ApiClient.delete("/api/backups/"+b.path("id").asText());Platform.runLater(this::load);}catch(Exception e){Platform.runLater(()->alert(Alert.AlertType.ERROR,"Delete failed: "+e.getMessage()));}},"backup-delete").start();}
    private void alert(Alert.AlertType t,String s){new Alert(t,s).showAndWait();}
}
