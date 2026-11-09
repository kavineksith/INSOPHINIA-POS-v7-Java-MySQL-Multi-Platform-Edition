package com.insophinia.pos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.*;

/** Inventory overview with server-authorized stock movement actions. */
public class InventoryView {
    private final VBox root = new VBox(10);
    private final TableView<JsonNode> table = new TableView<>();
    private final Label lowStockLabel = new Label();
    public InventoryView() {
        table.getColumns().setAll(col("PLU","pluCode"),col("Item","name"),col("Stock","stockQuantity"),col("Low-stock at","lowStockThreshold"),col("Category","categoryName"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        Button refresh=new Button("Refresh"), in=new Button("Stock in"), out=new Button("Stock out"), damage=new Button("Record damage"), lost=new Button("Record lost");
        refresh.setOnAction(e->refresh());in.setOnAction(e->adjust("/api/inventory/stock-in","Stock in"));out.setOnAction(e->adjust("/api/inventory/stock-out","Stock out"));damage.setOnAction(e->adjust("/api/inventory/damage","Record damage"));lost.setOnAction(e->adjust("/api/inventory/lost","Record lost"));
        in.setDisable(!Session.has("manage:inventory"));out.setDisable(!Session.has("manage:inventory"));damage.setDisable(!Session.has("manage:inventory"));lost.setDisable(!Session.has("manage:inventory"));
        HBox top=new HBox(8,refresh,in,out,damage,lost,lowStockLabel);root.setPadding(new Insets(10));root.getChildren().addAll(top,table);VBox.setVgrow(table,javafx.scene.layout.Priority.ALWAYS);refresh();
    }
    private TableColumn<JsonNode,String> col(String title,String field){TableColumn<JsonNode,String> c=new TableColumn<>(title);c.setCellValueFactory(v->new SimpleStringProperty(v.getValue().path(field).asText("")));return c;}
    public void refresh(){new Thread(()->{try{JsonNode d=ApiClient.get("/api/items?page=1&limit=100").path("data");List<JsonNode> rows=new ArrayList<>();if(d.isArray())d.forEach(rows::add);JsonNode low=ApiClient.get("/api/inventory/low-stock").path("data");Platform.runLater(()->{table.getItems().setAll(rows);lowStockLabel.setText("Low stock: "+(low.isArray()?low.size():0));});}catch(Exception e){Platform.runLater(()->new Alert(Alert.AlertType.ERROR,e.getMessage()).showAndWait());}},"inventory-refresh").start();}
    private void adjust(String endpoint,String title){JsonNode item=table.getSelectionModel().getSelectedItem();if(item==null){new Alert(Alert.AlertType.INFORMATION,"Select an item first.").showAndWait();return;}TextInputDialog qty=new TextInputDialog("1");qty.setTitle(title);qty.setHeaderText(item.path("name").asText());qty.setContentText("Quantity:");Optional<String> q=qty.showAndWait();if(q.isEmpty())return;TextInputDialog notes=new TextInputDialog();notes.setTitle(title);notes.setHeaderText("Reason / reference note");notes.setContentText("Notes (optional):");String note=notes.showAndWait().orElse("");try{BigDecimal amount=new BigDecimal(q.get().trim());if(amount.signum()<=0)throw new NumberFormatException();Map<String,Object> body=new HashMap<>();body.put("itemId",item.path("id").asText());body.put("quantity",amount);body.put("notes",note);new Thread(()->{try{ApiClient.post(endpoint,body);Platform.runLater(()->{new Alert(Alert.AlertType.INFORMATION,title+" saved.").showAndWait();refresh();});}catch(Exception e){Platform.runLater(()->new Alert(Alert.AlertType.ERROR,title+" failed: "+e.getMessage()).showAndWait());}},"inventory-adjust").start();}catch(Exception e){new Alert(Alert.AlertType.WARNING,"Quantity must be a positive number.").showAndWait();}}
    public VBox getRoot(){return root;}
}
