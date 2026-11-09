package com.insophinia.pos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.chart.*;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/** Operational dashboard with KPIs, 7-day revenue trend and best-selling items. */
public class DashboardView {
    private final VBox root = new VBox(14);
    private final Label todaySales=kpi(), todayBills=kpi(), monthSales=kpi(), lowStock=kpi(), customers=kpi(), items=kpi(), loyaltyPoints=kpi();
    private final CategoryAxis salesAxis=new CategoryAxis(); private final NumberAxis salesValues=new NumberAxis();
    private final LineChart<String,Number> salesChart=new LineChart<>(salesAxis,salesValues);
    private final CategoryAxis itemAxis=new CategoryAxis(); private final NumberAxis itemValues=new NumberAxis();
    private final BarChart<String,Number> itemChart=new BarChart<>(itemAxis,itemValues);
    public DashboardView(){
        GridPane grid=new GridPane();grid.setHgap(24);grid.setVgap(12);grid.setPadding(new Insets(14));
        add(grid,0,0,"Today's sales",todaySales);add(grid,1,0,"Today's bills",todayBills);add(grid,2,0,"Month sales",monthSales);add(grid,0,1,"Low-stock items",lowStock);add(grid,1,1,"Customers",customers);add(grid,2,1,"Active items",items);add(grid,0,2,"Total loyalty points",loyaltyPoints);
        salesChart.setTitle("Sales over the last 7 days");salesChart.setLegendVisible(false);salesChart.setAnimated(false);salesChart.setPrefHeight(300);
        itemChart.setTitle("Top-selling items (last 30 days)");itemChart.setLegendVisible(false);itemChart.setAnimated(false);itemChart.setPrefHeight(300);
        root.setPadding(new Insets(12));root.getChildren().addAll(new Label("Dashboard"),grid,salesChart,itemChart);refresh();
    }
    private static Label kpi(){Label l=new Label("—");l.setStyle("-fx-font-size:22px;-fx-font-weight:bold;");return l;}
    private static void add(GridPane g,int x,int y,String title,Label value){VBox card=new VBox(6,new Label(title),value);card.setPadding(new Insets(12));card.setStyle("-fx-background-color:#f1f5f9;-fx-background-radius:8;");g.add(card,x,y);}
    public void refresh(){new Thread(()->{try{JsonNode d=ApiClient.get("/api/dashboard").path("data");Platform.runLater(()->{
        todaySales.setText("LKR "+d.path("todaySales").asText("0"));todayBills.setText(d.path("todayBills").asText("0"));monthSales.setText("LKR "+d.path("monthSales").asText("0"));lowStock.setText(d.path("lowStockCount").asText("0"));customers.setText(d.path("totalCustomers").asText("0"));items.setText(d.path("totalItems").asText("0"));loyaltyPoints.setText(d.path("totalLoyaltyPoints").asText("0"));
        XYChart.Series<String,Number> s=new XYChart.Series<>();d.path("salesLast7Days").forEach(r->s.getData().add(new XYChart.Data<>(r.path("date").asText(),r.path("revenue").decimalValue())));salesChart.getData().setAll(s);
        XYChart.Series<String,Number> top=new XYChart.Series<>();d.path("topItems").forEach(r->top.getData().add(new XYChart.Data<>(r.path("itemName").asText(),r.path("totalQty").decimalValue())));itemChart.getData().setAll(top);
    });}catch(Exception ignored){}},"dashboard-refresh").start();}
    public VBox getRoot(){return root;}
}
