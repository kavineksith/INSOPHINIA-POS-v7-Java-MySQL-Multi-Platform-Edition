package com.insophinia.pos.desktop;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

/** Dedicated customer-facing window; updated immediately from the active checkout cart. */
public final class CustomerDisplayView {
    private final BorderPane root = new BorderPane();
    private final Label shop = new Label("INSOPHINIA POS");
    private final Label status = new Label("Welcome! Your items will appear here.");
    private final Label lines = new Label("");
    private final Label total = new Label("Total: LKR 0.00");
    public CustomerDisplayView() {
        shop.setStyle("-fx-font-size: 30px; -fx-font-weight: bold;"); status.setStyle("-fx-font-size: 18px;");
        lines.setStyle("-fx-font-size: 17px;"); total.setStyle("-fx-font-size: 30px; -fx-font-weight: bold;");
        VBox content = new VBox(24, shop, status, lines, total); content.setAlignment(Pos.CENTER); content.setMaxWidth(900);
        root.setCenter(content); root.setStyle("-fx-background-color: #f8fafc;");
    }
    public BorderPane getRoot() { return root; }
    public void showTotal(String currency, String amount) { total.setText("Total: " + currency + " " + amount); }
    public void showMessage(String message) { status.setText(message); }
    public void showRemoteCart(com.fasterxml.jackson.databind.JsonNode payload) {
        StringBuilder text = new StringBuilder(); int count = 0;
        for (var item : payload.path("items")) {
            if (count++ >= 8) { text.append("\n…"); break; }
            text.append(item.path("name").asText("Item")).append("  ×  ").append(item.path("qty").asText("1")).append('\n');
        }
        lines.setText(text.toString()); total.setText("Total: " + payload.path("currency").asText("LKR") + " " + payload.path("total").asText("0.00"));
        status.setText(text.isEmpty() ? "Welcome! Waiting for a checkout terminal…" : "Please review your items");
    }
    public void showCart(List<Map<String,Object>> cart, String currency, String amount) {
        StringBuilder s = new StringBuilder(); int count=0;
        for (Map<String,Object> item : cart) { if(count++ >= 8) { s.append("\n…"); break; } s.append(item.get("name")).append("  ×  ").append(item.get("qty")).append('\n'); }
        lines.setText(s.toString()); showTotal(currency, amount); status.setText(cart.isEmpty() ? "Welcome! Your items will appear here." : "Please review your items");
    }
}
