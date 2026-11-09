package com.insophinia.pos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.util.*;

/** Date-filtered sales and item performance reports with local CSV export. */
public final class ReportView {
    private final BorderPane root = new BorderPane();
    private final DatePicker from = new DatePicker(LocalDate.now().minusDays(30));
    private final DatePicker to = new DatePicker(LocalDate.now());
    private final TableView<JsonNode> salesTable = new TableView<>();
    private final TableView<JsonNode> itemsTable = new TableView<>();
    private List<JsonNode> sales = new ArrayList<>(), itemRows = new ArrayList<>();
    private final Label summary = new Label("Choose a date range and refresh.");
    public ReportView() {
        Label title = new Label("Reports & analytics"); title.setStyle("-fx-font-size:20px;-fx-font-weight:bold;");
        Button refresh=new Button("Run report"), export=new Button("Export CSV"); refresh.setOnAction(e->load()); export.setOnAction(e->export());
        HBox filter=new HBox(8,title,new javafx.scene.layout.Region(),new Label("From"),from,new Label("To"),to,refresh,export); HBox.setHgrow(filter.getChildren().get(1),javafx.scene.layout.Priority.ALWAYS);filter.setPadding(new Insets(12));
        salesTable.getColumns().setAll(col("Date","date"),col("Bills","bills"),col("Sales LKR","revenue"),col("Discounts LKR","discount"));
        itemsTable.getColumns().setAll(col("Item","itemName"),col("PLU","pluCode"),col("Quantity","totalQty"),col("Revenue LKR","totalRevenue"));
        salesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN); itemsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TabPane tabs=new TabPane(); tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); tabs.getTabs().addAll(new Tab("Sales by day",salesTable),new Tab("Item performance",itemsTable));
        root.setTop(new VBox(filter,summary)); root.setCenter(tabs); load();
    }
    public BorderPane getRoot(){return root;}
    private TableColumn<JsonNode,String> col(String label,String key){TableColumn<JsonNode,String> c=new TableColumn<>(label);c.setCellValueFactory(v->new javafx.beans.property.SimpleStringProperty(v.getValue().path(key).asText("")));return c;}
    private void load(){ if(from.getValue()==null||to.getValue()==null||from.getValue().isAfter(to.getValue())){new Alert(Alert.AlertType.WARNING,"Choose a valid date range.").showAndWait();return;}
        String f=from.getValue().atStartOfDay(ZoneOffset.UTC).toInstant().toString(), t=to.getValue().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString(); summary.setText("Loading report…");
        new Thread(()->{try{JsonNode s=ApiClient.get("/api/reports/sales?from="+f+"&to="+t).path("data");JsonNode i=ApiClient.get("/api/reports/items?from="+f+"&to="+t).path("data");List<JsonNode> sr=new ArrayList<>(),ir=new ArrayList<>();if(s.isArray())s.forEach(sr::add);if(i.isArray())i.forEach(ir::add);Platform.runLater(()->{sales=sr;itemRows=ir;salesTable.getItems().setAll(sr);itemsTable.getItems().setAll(ir);summary.setText("Sales days: "+sr.size()+"  |  Item rows: "+ir.size()+"  |  Range: "+from.getValue()+" to "+to.getValue());});}catch(Exception e){Platform.runLater(()->summary.setText("Report failed: "+e.getMessage()));}},"report-load").start(); }
    private void export(){FileChooser c=new FileChooser();c.setTitle("Export report CSV");c.setInitialFileName("insophinia-report.csv");c.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV","*.csv"));File f=c.showSaveDialog(root.getScene().getWindow());if(f==null)return;try{StringBuilder b=new StringBuilder("report_type,date_or_item,count_or_quantity,total_sales_or_revenue,total_discount\n");for(JsonNode n:sales)b.append(csv("sales")).append(',').append(csv(n.path("date").asText())).append(',').append(csv(n.path("bills").asText())).append(',').append(csv(n.path("revenue").asText())).append(',').append(csv(n.path("discount").asText())).append('\n');for(JsonNode n:itemRows)b.append(csv("item")).append(',').append(csv(n.path("itemName").asText())).append(',').append(csv(n.path("totalQty").asText())).append(',').append(csv(n.path("totalRevenue").asText())).append(",\n");Files.writeString(f.toPath(),b.toString(),StandardCharsets.UTF_8);new Alert(Alert.AlertType.INFORMATION,"CSV exported to "+f.getAbsolutePath()).showAndWait();}catch(Exception e){new Alert(Alert.AlertType.ERROR,"Export failed: "+e.getMessage()).showAndWait();}}
    private String csv(String s){return "\""+s.replace("\"","\"\"")+"\"";}
}
