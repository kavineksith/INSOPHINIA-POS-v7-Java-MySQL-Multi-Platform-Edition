package com.insophinia.pos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** POS checkout screen: scan/search item, build cart, checkout against /api/billing. */
public class PosView {

    private static final java.util.concurrent.ExecutorService CART_PUBLISHER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "customer-cart-publisher"); t.setDaemon(true); return t; });

    private final BorderPane root = new BorderPane();
    private final TableView<JsonNode> itemsTable = new TableView<>();
    private final TableView<Map<String, Object>> cartTable = new TableView<>();
    private final Map<String, Map<String, Object>> cart = new LinkedHashMap<>();
    private final Label totalLabel = new Label("Total: Rs. 0.00");
    private CustomerDisplayView customerDisplay;

    public void setCustomerDisplay(CustomerDisplayView display) { this.customerDisplay = display; syncCustomerDisplay(); }

    public PosView() {
        TextField scan = new TextField();
        scan.setPromptText("Scan barcode / enter PLU and press Enter");
        scan.setOnAction(e -> {
            String code = scan.getText().trim();
            if (!code.isEmpty()) addByCode(code);
            scan.clear();
        });

        Button refresh = new Button("Refresh items");
        refresh.setOnAction(e -> refresh());
        HBox top = new HBox(10, scan, refresh);
        top.setPadding(new Insets(10));
        HBox.setHgrow(scan, Priority.ALWAYS);

        setupItemsTable();
        setupCartTable();
        if (Session.customerDisplayOnly) { scan.setDisable(true); itemsTable.setDisable(true); cartTable.setDisable(true); }

        Button checkout = new Button("Checkout");
        checkout.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        checkout.setDisable(Session.customerDisplayOnly || !Session.has("manage:billing"));
        checkout.setMaxWidth(Double.MAX_VALUE);
        checkout.setOnAction(e -> checkout());
        Button clearCart = new Button("Clear");
        clearCart.setMaxWidth(Double.MAX_VALUE);
        clearCart.setDisable(Session.customerDisplayOnly);
        clearCart.setOnAction(e -> { cart.clear(); cartTable.getItems().clear(); updateTotal(); });

        VBox right = new VBox(10, new Label("Cart"), cartTable, totalLabel, checkout, clearCart);
        right.setPadding(new Insets(10));
        right.setPrefWidth(420);
        VBox.setVgrow(cartTable, Priority.ALWAYS);

        VBox left = new VBox(8, top, itemsTable);
        left.setPadding(new Insets(10));
        VBox.setVgrow(itemsTable, Priority.ALWAYS);

        root.setLeft(left);
        root.setCenter(right);
        refresh();
    }

    private void setupItemsTable() {
        TableColumn<JsonNode, String> plu = new TableColumn<>("PLU");
        plu.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().get("pluCode").asText()));
        TableColumn<JsonNode, String> name = new TableColumn<>("Item");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().get("name").asText()));
        TableColumn<JsonNode, String> price = new TableColumn<>("Price");
        price.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().get("price").asText()));
        TableColumn<JsonNode, String> stock = new TableColumn<>("Stock");
        stock.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().get("stockQuantity").asText()));
        TableColumn<JsonNode, String> add = new TableColumn<>("");
        add.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            private final Button btn = new Button("+");
            { btn.setOnAction(e -> addToCart(getTableView().getItems().get(getIndex()))); }
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                setGraphic(empty ? null : btn);
            }
        });
        itemsTable.getColumns().setAll(plu, name, price, stock, add);
        itemsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    @SuppressWarnings("unchecked")
    private void setupCartTable() {
        TableColumn<Map<String, Object>, String> name = new TableColumn<>("Item");
        name.setCellValueFactory(c -> new SimpleStringProperty((String) c.getValue().get("name")));
        TableColumn<Map<String, Object>, String> qty = new TableColumn<>("Qty");
        qty.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().get("qty"))));
        TableColumn<Map<String, Object>, String> price = new TableColumn<>("Price");
        price.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().get("price"))));
        TableColumn<Map<String, Object>, String> remove = new TableColumn<>("Remove");
        remove.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            private final Button btn = new Button("×");
            { btn.setOnAction(e -> { Map<String,Object> line = getTableView().getItems().get(getIndex()); cart.remove(String.valueOf(line.get("itemId"))); refreshCartTable(); updateTotal(); }); }
            @Override protected void updateItem(String v, boolean empty) { super.updateItem(v, empty); setGraphic(empty ? null : btn); }
        });
        cartTable.getColumns().setAll(name, qty, price, remove);
        cartTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private void addByCode(String code) {
        new Thread(() -> {
            try {
                JsonNode item = ApiClient.get("/api/items/lookup/" + code).get("data");
                Platform.runLater(() -> addToCart(item));
            } catch (ApiClient.ApiException e) {
                Platform.runLater(() -> alert(Alert.AlertType.WARNING, e.getMessage()));
            }
        }).start();
    }

    private void addToCart(JsonNode item) {
        String id = item.get("id").asText();
        Map<String, Object> line = cart.computeIfAbsent(id, k -> {
            Map<String, Object> m = new HashMap<>();
            m.put("itemId", id);
            m.put("name", item.get("name").asText());
            m.put("price", new BigDecimal(item.get("price").asText()));
            m.put("qty", BigDecimal.ZERO);
            return m;
        });
        line.put("qty", ((BigDecimal) line.get("qty")).add(BigDecimal.ONE));
        refreshCartTable();
        updateTotal();
    }

    private void refreshCartTable() {
        cartTable.getItems().setAll(new ArrayList<>(cart.values()));
    }

    private void updateTotal() {
        BigDecimal total = cart.values().stream()
                .map(l -> ((BigDecimal) l.get("price")).multiply((BigDecimal) l.get("qty")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        totalLabel.setText("Total: LKR " + total.setScale(2));
        syncCustomerDisplay();
        publishCartSnapshot();
    }

    private void syncCustomerDisplay() {
        if (customerDisplay != null) customerDisplay.showCart(new ArrayList<>(cart.values()), "LKR", cart.values().stream()
                .map(l -> ((BigDecimal) l.get("price")).multiply((BigDecimal) l.get("qty")))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2).toPlainString());
    }

    private void publishCartSnapshot() {
        if (Session.customerDisplayOnly || !Session.has("manage:billing") || Session.displayChannel.length() < 16) return;
        List<Map<String,Object>> items = cart.values().stream().map(line -> {
            Map<String,Object> m = new LinkedHashMap<>(); m.put("name", String.valueOf(line.get("name")));
            m.put("qty", line.get("qty")); m.put("price", line.get("price")); return m;
        }).toList();
        BigDecimal total = cart.values().stream().map(l -> ((BigDecimal) l.get("price")).multiply((BigDecimal) l.get("qty")))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, java.math.RoundingMode.HALF_UP);
        Map<String,Object> body = new LinkedHashMap<>(); body.put("channel", Session.displayChannel); body.put("terminalId", Session.terminalId);
        body.put("items", items); body.put("total", total); body.put("currency", "LKR");
        CART_PUBLISHER.submit(() -> { try { ApiClient.post("/api/sync/customer-display/cart", body); } catch (Exception ignored) {} });
    }

    private void checkout() {
        if (cart.isEmpty()) return;
        BigDecimal total = cart.values().stream()
                .map(l -> ((BigDecimal) l.get("price")).multiply((BigDecimal) l.get("qty")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        TextField paid = new TextField(total.setScale(2).toPlainString());
        var dialog = new javafx.scene.control.Dialog<BigDecimal>();
        dialog.setTitle("Checkout");
        dialog.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        dialog.getDialogPane().setContent(new VBox(8, new Label("Paid amount (Rs.)"), paid));
        dialog.setResultConverter(b -> b == javafx.scene.control.ButtonType.OK
                ? new BigDecimal(paid.getText()) : null);

        dialog.showAndWait().ifPresent(paidAmount -> {
            List<Map<String, Object>> lines = cart.values().stream()
                    .map(l -> Map.<String, Object>of("itemId", l.get("itemId"), "quantity", l.get("qty")))
                    .toList();
            Map<String, Object> body = new HashMap<>();
            body.put("items", lines);
            body.put("paidAmount", paidAmount);
            new Thread(() -> {
                try {
                    JsonNode bill = ApiClient.post("/api/billing", body).get("data");
                    String printMessage = "";
                    try { HardwareService.printReceipt(bill); ApiClient.post("/api/billing/" + bill.get("id").asText() + "/print", null); printMessage = " Receipt sent to thermal printer."; }
                    catch (Exception printError) { printMessage = " Receipt not printed: " + printError.getMessage(); }
                    final String receiptStatus = printMessage;
                    Platform.runLater(() -> {
                        alert(Alert.AlertType.INFORMATION, "Bill " + bill.get("billNumber").asText()
                                + " created. Balance: Rs. " + bill.get("balance").asText() + receiptStatus);
                        cart.clear();
                        refreshCartTable();
                        updateTotal();
                        if (customerDisplay != null) customerDisplay.showMessage("Thank you! Your purchase is complete.");
                        refresh();
                    });
                } catch (ApiClient.ApiException e) {
                    Platform.runLater(() -> alert(Alert.AlertType.ERROR,
                            e.status + " " + e.code + ": " + e.getMessage()));
                }
            }).start();
        });
    }

    public void refresh() {
        new Thread(() -> {
            try {
                JsonNode data = ApiClient.get("/api/items?page=1&limit=500").get("data");
                List<JsonNode> list = new ArrayList<>();
                data.forEach(list::add);
                Platform.runLater(() -> itemsTable.getItems().setAll(list));
            } catch (ApiClient.ApiException ignored) {}
        }).start();
    }

    private void alert(Alert.AlertType type, String msg) {
        new Alert(type, msg).showAndWait();
    }

    public BorderPane getRoot() { return root; }
}
