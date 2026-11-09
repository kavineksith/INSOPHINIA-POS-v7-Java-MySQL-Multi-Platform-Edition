package com.insophinia.pos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.*;

/** Table-driven CRUD workspace. API permissions remain authoritative on the server.
 * v7: supports soft-delete (all roles), hard-delete (master_admin only), restore,
 * and a "Show deleted" filter toggle with visual styling for soft-deleted rows. */
public final class CrudResourceView {
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Field(String key, String label, String kind, boolean required) {}
    private final BorderPane root = new BorderPane();
    private final TableView<JsonNode> table = new TableView<>();
    private final String title, endpoint, permission;
    private final List<Field> fields;
    private final boolean userResource;
    private final Label status = new Label("Ready");
    private final TextField searchField = new TextField();
    private final TextField fromField = new TextField();
    private final TextField toField = new TextField();
    private final ComboBox<String> sortField = new ComboBox<>();
    private final ComboBox<String> directionField = new ComboBox<>();
    private final CheckBox showDeletedCheck = new CheckBox("Show deleted");
    private final CheckBox deletedOnlyCheck = new CheckBox("Deleted only");
    private final Button restoreBtn = new Button("Restore");
    private final Label pageLabel = new Label("Page 1");
    private int currentPage = 1;
    private int totalPages = 1;
    private static final int PAGE_SIZE = 50;

    public CrudResourceView(String title, String endpoint, String permission) {
        this.title = title; this.endpoint = endpoint; this.permission = permission;
        this.userResource = endpoint.equals("/api/users");
        this.fields = schema(endpoint);
        boolean isMasterAdmin = "master_admin".equalsIgnoreCase(Session.role);
        Label heading = new Label(title + " management"); heading.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        Button refresh = new Button("Apply filters"), clear = new Button("Clear filters"), previous = new Button("Previous"), next = new Button("Next"), add = new Button("New"), edit = new Button("Edit selected"), delete = new Button("Delete selected");
        // For master_admin, replace the plain delete button with a delete-mode ComboBox
        ComboBox<String> deleteMode = new ComboBox<>(); deleteMode.getItems().setAll("Soft delete", "Hard delete (permanent)"); deleteMode.setValue("Soft delete"); deleteMode.setPrefWidth(160); deleteMode.setVisible(isMasterAdmin); deleteMode.setManaged(isMasterAdmin);
        searchField.setPromptText("Search name, email, phone, PLU, username…"); searchField.setPrefWidth(230);
        fromField.setVisible(endpoint.equals("/api/customers")); fromField.setManaged(endpoint.equals("/api/customers")); toField.setVisible(endpoint.equals("/api/customers")); toField.setManaged(endpoint.equals("/api/customers"));
        fromField.setPromptText("From date YYYY-MM-DD"); fromField.setPrefWidth(180);
        toField.setPromptText("To ISO date (optional)"); toField.setPrefWidth(180);
        sortField.getItems().setAll(sortOptions(endpoint)); sortField.setValue(sortOptions(endpoint).get(0));
        directionField.getItems().setAll("asc", "desc"); directionField.setValue(endpoint.equals("/api/promotions") || endpoint.equals("/api/customers") ? "desc" : "asc");
        // Soft-delete filter toggle handlers
        showDeletedCheck.setOnAction(e -> { if (showDeletedCheck.isSelected()) deletedOnlyCheck.setSelected(false); currentPage = 1; refresh(); });
        deletedOnlyCheck.setOnAction(e -> { if (deletedOnlyCheck.isSelected()) showDeletedCheck.setSelected(false); currentPage = 1; refresh(); });
        restoreBtn.setOnAction(e -> restore());
        restoreBtn.setDisable(!Session.has(permission));
        refresh.setOnAction(e -> { currentPage = 1; refresh(); });
        clear.setOnAction(e -> { searchField.clear(); fromField.clear(); toField.clear(); sortField.setValue(sortOptions(endpoint).get(0)); directionField.setValue(endpoint.equals("/api/promotions") || endpoint.equals("/api/customers") ? "desc" : "asc"); showDeletedCheck.setSelected(false); deletedOnlyCheck.setSelected(false); currentPage = 1; refresh(); });
        previous.setOnAction(e -> { if (currentPage > 1) { currentPage--; refresh(); } });
        next.setOnAction(e -> { if (currentPage < totalPages) { currentPage++; refresh(); } });
        add.setOnAction(e -> create()); edit.setOnAction(e -> edit()); delete.setOnAction(e -> delete(deleteMode.getValue().contains("Hard") && isMasterAdmin));
        add.setDisable(!Session.has(permission)); edit.setDisable(!Session.has(permission)); delete.setDisable(!Session.has(permission));
        HBox actions = new HBox(8, heading, new javafx.scene.layout.Region(), add, edit, delete, deleteMode, restoreBtn);
        javafx.scene.layout.HBox.setHgrow(actions.getChildren().get(1), javafx.scene.layout.Priority.ALWAYS);
        actions.setPadding(new Insets(12));
        HBox filters = new HBox(8, searchField, fromField, toField, sortField, directionField, showDeletedCheck, deletedOnlyCheck, refresh, clear);
        filters.setPadding(new Insets(0, 12, 8, 12));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<JsonNode, String> name = col("Name", n -> first(n, "name", "billNumber", "username", "pluCode", "id") + (n.hasNonNull("deletedAt") ? "  [DELETED]" : ""));
        TableColumn<JsonNode, String> info = col("Details", n -> details(n));
        TableColumn<JsonNode, String> state = col("Status", n -> n.has("active") ? (n.path("active").asBoolean() ? "Active" : "Inactive") : n.path("status").asText(""));
        table.getColumns().setAll(name, info, state);
        Label hint = new Label("Select a row to edit or delete. Soft-deleted rows show [DELETED] and render at 50% opacity. Use Restore to un-delete.");
        HBox paging = new HBox(10, previous, pageLabel, next); paging.setPadding(new Insets(8, 12, 8, 12));
        VBox top = new VBox(6, actions, filters, hint); top.setPadding(new Insets(0, 12, 10, 12));
        root.setTop(top); root.setCenter(table); root.setBottom(new VBox(4, paging, status)); BorderPane.setMargin(status, new Insets(0, 12, 12, 12));
        // Row factory — soft-deleted rows render at 50% opacity with muted text + strikethrough
        table.setRowFactory(tv -> {
            TableRow<JsonNode> row = new TableRow<>() {
                @Override protected void updateItem(JsonNode item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) { setStyle(""); return; }
                    if (item.hasNonNull("deletedAt")) {
                        setStyle("-fx-opacity: 0.5; -fx-text-fill: #94a3b8;");
                    } else {
                        setStyle("");
                    }
                }
            };
            row.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && !row.isEmpty()) edit(); });
            return row;
        });
        refresh();
    }
    public BorderPane getRoot() { return root; }

    private static TableColumn<JsonNode,String> col(String name, java.util.function.Function<JsonNode,String> f) {
        TableColumn<JsonNode,String> c = new TableColumn<>(name); c.setCellValueFactory(x -> new SimpleStringProperty(f.apply(x.getValue()))); return c;
    }
    private static String first(JsonNode n, String... keys) { for (String k: keys) if (n.hasNonNull(k) && !n.get(k).asText().isBlank()) return n.get(k).asText(); return ""; }
    private static String details(JsonNode n) {
        List<String> bits = new ArrayList<>();
        for (String k : List.of("email","phone","pluCode","price","stockQuantity","role","discountType","minItemCount","loyaltyPoints","totalAmount","createdAt"))
            if (n.hasNonNull(k) && !n.get(k).asText().isBlank()) bits.add(k + ": " + n.get(k).asText());
        return String.join("  ·  ", bits);
    }
    public void refresh() {
        status.setText("Loading page " + currentPage + "…");
        StringBuilder url = new StringBuilder(endpoint).append("?page=").append(currentPage).append("&limit=").append(PAGE_SIZE);
        String search = searchField.getText().trim();
        if (!search.isEmpty()) url.append("&search=").append(java.net.URLEncoder.encode(search, java.nio.charset.StandardCharsets.UTF_8));
        if (!fromField.getText().trim().isEmpty()) url.append("&from=").append(java.net.URLEncoder.encode(normalizeDate(fromField.getText().trim(), false), java.nio.charset.StandardCharsets.UTF_8));
        if (!toField.getText().trim().isEmpty()) url.append("&to=").append(java.net.URLEncoder.encode(normalizeDate(toField.getText().trim(), true), java.nio.charset.StandardCharsets.UTF_8));
        url.append("&sort=").append(java.net.URLEncoder.encode(sortField.getValue() == null ? "name" : sortField.getValue(), java.nio.charset.StandardCharsets.UTF_8));
        url.append("&direction=").append(directionField.getValue() == null ? "asc" : directionField.getValue());
        // v7: soft-delete filter params
        if (showDeletedCheck.isSelected()) url.append("&includeDeleted=true");
        if (deletedOnlyCheck.isSelected()) url.append("&deletedOnly=true");
        new Thread(() -> { try {
            JsonNode response = ApiClient.get(url.toString());
            JsonNode data = response.path("data"); List<JsonNode> rows = new ArrayList<>(); if (data.isArray()) data.forEach(rows::add);
            JsonNode pagination = response.path("pagination"); int pages = Math.max(1, pagination.path("pages").asInt(1)); long total = pagination.path("total").asLong(rows.size());
            long deletedCount = rows.stream().filter(r -> r.hasNonNull("deletedAt")).count();
            Platform.runLater(() -> { totalPages = pages; table.getItems().setAll(rows); pageLabel.setText("Page " + currentPage + " of " + totalPages);
                status.setText(total + " matching records · " + rows.size() + " on this page" + (deletedCount > 0 ? " · " + deletedCount + " deleted" : "")); });
        } catch (Exception ex) { Platform.runLater(() -> status.setText("Could not load records: " + ex.getMessage())); }
        }, "crud-refresh").start();
    }

    private static String normalizeDate(String raw, boolean end) {
        if (raw.matches("\\d{4}-\\d{2}-\\d{2}")) return raw + (end ? "T23:59:59.999999999Z" : "T00:00:00Z");
        return raw;
    }
    private static List<String> sortOptions(String endpoint) {
        return switch (endpoint) {
            case "/api/items" -> List.of("name", "pluCode", "price", "stockQuantity", "createdAt");
            case "/api/customers" -> List.of("createdAt", "name", "email", "phone", "loyaltyPoints");
            case "/api/promotions" -> List.of("createdAt", "name", "startDate", "endDate", "minItemCount");
            case "/api/users" -> List.of("createdAt", "username", "email", "firstName", "lastName", "role");
            default -> List.of("name", "createdAt", "updatedAt");
        };
    }
    private void create() { showForm(null); }
    private void edit() { JsonNode selected = table.getSelectionModel().getSelectedItem(); if (selected == null) { info("Select a record first."); return; } showForm(selected); }
    private void showForm(JsonNode existing) {
        boolean update = existing != null;
        Dialog<Map<String,Object>> dialog = new Dialog<>(); dialog.setTitle((update ? "Edit " : "Create ") + title); dialog.setHeaderText(update ? "Update the selected record" : "Enter the new record details");
        ButtonType save = new ButtonType(update ? "Save changes" : "Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        GridPane grid = new GridPane(); grid.setHgap(12); grid.setVgap(9); grid.setPadding(new Insets(12));
        Map<String,Control> controls = new LinkedHashMap<>(); int row = 0;
        for (Field f : fields) {
            if (update && f.key.equals("username")) continue;
            if (userResource && !update && f.key.equals("active")) continue;
            if (update && f.key.equals("temporaryPassword")) continue;
            Label label = new Label(f.label + (f.required ? " *" : "")); Control c;
            String value = existing == null || existing.path(f.key).isMissingNode() || existing.path(f.key).isNull() ? "" : existing.path(f.key).asText();
            if (f.kind.equals("boolean")) { CheckBox cb = new CheckBox("Enabled"); cb.setSelected(value.isBlank() ? f.key.equals("active") : Boolean.parseBoolean(value)); c = cb; }
            else if (f.kind.equals("password")) { PasswordField p = new PasswordField(); p.setPromptText("Temporary password (12–30 characters)"); c = p; }
            else { TextField t = new TextField(value); t.setPromptText(f.label); if (f.kind.equals("date")) t.setPromptText("2026-10-15T00:00:00Z"); c = t; }
            controls.put(f.key, c); grid.add(label, 0, row); grid.add(c, 1, row++);
        }
        Label note = new Label("* Required. For item/promotion IDs, copy the UUID from the related tab. Dates use ISO-8601 UTC."); note.setWrapText(true); grid.add(note, 0, row, 2, 1);
        ScrollPane scroll = new ScrollPane(grid); scroll.setFitToWidth(true); scroll.setPrefViewportHeight(470); dialog.getDialogPane().setContent(scroll);
        dialog.setResultConverter(b -> {
            if (b != save) return null; Map<String,Object> payload = new LinkedHashMap<>();
            for (Field f : fields) {
                Control c = controls.get(f.key); if (c == null) continue;
                if (c instanceof CheckBox cb) { payload.put(f.key, cb.isSelected()); continue; }
                String raw = c instanceof PasswordField p ? p.getText().trim() : ((TextField)c).getText().trim();
                if (raw.isEmpty()) { if (f.required) throw new IllegalArgumentException(f.label + " is required"); payload.put(f.key, null); continue; }
                try {
                    switch (f.kind) {
                        case "decimal" -> payload.put(f.key, new BigDecimal(raw));
                        case "integer" -> payload.put(f.key, Integer.parseInt(raw));
                        case "uuid" -> payload.put(f.key, UUID.fromString(raw).toString());
                        case "date" -> { java.time.Instant.parse(raw); payload.put(f.key, raw); }
                        default -> payload.put(f.key, raw);
                    }
                } catch (Exception ex) { throw new IllegalArgumentException("Invalid value for " + f.label + ". " + ex.getMessage()); }
            }
            return payload;
        });
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try { dialog.getResultConverter().call(save); }
            catch (Exception ex) { event.consume(); error(ex.getMessage()); }
        });
        try {
            Optional<Map<String,Object>> result = dialog.showAndWait();
            result.ifPresent(payload -> saveRecord(existing, payload));
        } catch (Exception ex) { error(ex.getMessage()); }
    }
    private void saveRecord(JsonNode existing, Map<String,Object> payload) {
        new Thread(() -> { try {
            if (userResource && existing == null) ApiClient.post(endpoint, payload);
            else if (existing == null) ApiClient.post(endpoint, payload);
            else ApiClient.put(endpoint + "/" + existing.path("id").asText(), payload);
            Platform.runLater(() -> { status.setText("Saved successfully"); refresh(); });
        } catch (Exception ex) { Platform.runLater(() -> error(ex.getMessage())); }
        }, "crud-save").start();
    }
    private void delete(boolean hard) {
        JsonNode selected = table.getSelectionModel().getSelectedItem(); if (selected == null) { info("Select a record first."); return; }
        String confirmMsg = hard
                ? "PERMANENTLY DELETE this " + title.toLowerCase() + " record?\n\nThis action CANNOT be undone. The record will be removed from the database entirely."
                : "Soft-delete this " + title.toLowerCase() + " record?\n\nThe record will be hidden from default views but can be restored later.";
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, confirmMsg, ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText(hard ? "⚠ Confirm HARD delete" : "Confirm soft delete");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        String deleteUrl = endpoint + "/" + selected.path("id").asText() + "?mode=" + (hard ? "hard" : "soft");
        new Thread(() -> { try { ApiClient.delete(deleteUrl); Platform.runLater(() -> { status.setText(hard ? "Record permanently deleted" : "Record soft-deleted"); refresh(); }); }
            catch (Exception ex) { Platform.runLater(() -> error(ex.getMessage())); } }, "crud-delete").start();
    }

    /** v7: Restore a soft-deleted record — sets deletedAt back to null. */
    private void restore() {
        JsonNode selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) { info("Select a deleted record to restore."); return; }
        if (!selected.hasNonNull("deletedAt")) { info("This record is not deleted."); return; }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Restore this " + title.toLowerCase() + " record?\n\nThe record will become visible in default views again.",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText("Confirm restore");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        String restoreUrl = endpoint + "/" + selected.path("id").asText() + "/restore";
        new Thread(() -> { try { ApiClient.post(restoreUrl, null); Platform.runLater(() -> { status.setText("Record restored"); refresh(); }); }
            catch (Exception ex) { Platform.runLater(() -> error(ex.getMessage())); } }, "crud-restore").start();
    }
    private void info(String message) { new Alert(Alert.AlertType.INFORMATION, message).showAndWait(); }
    private void error(String message) { new Alert(Alert.AlertType.ERROR, message == null ? "Invalid input" : message).showAndWait(); }
    private static List<Field> schema(String endpoint) {
        return switch (endpoint) {
            case "/api/categories" -> List.of(new Field("name","Name","text",true), new Field("description","Description","text",false), new Field("active","Active","boolean",false));
            case "/api/customers" -> List.of(new Field("name","Full name","text",true), new Field("email","Email","text",false), new Field("phone","Phone","text",false));
            case "/api/items" -> List.of(new Field("pluCode","PLU code","text",true), new Field("name","Item name","text",true), new Field("description","Description","text",false), new Field("categoryId","Category UUID","uuid",true), new Field("barcode","Barcode","text",false), new Field("qrCode","QR code","text",false), new Field("price","Selling price","decimal",true), new Field("costPrice","Cost price","decimal",false), new Field("unit","Unit","text",false), new Field("stockQuantity","Opening stock","decimal",false), new Field("lowStockThreshold","Low stock threshold","decimal",false), new Field("active","Active","boolean",false), new Field("hasDiscount","Has discount","boolean",false), new Field("discountPercentage","Discount percentage","decimal",false));
            case "/api/promotions" -> List.of(new Field("name","Promotion name","text",true), new Field("description","Description","text",false), new Field("itemId","Item UUID (optional)","uuid",false), new Field("categoryId","Category UUID (optional)","uuid",false), new Field("discountType","Discount type (percentage/fixed)","text",true), new Field("discountValue","Discount value","decimal",true), new Field("startDate","Start date/time UTC","date",true), new Field("endDate","End date/time UTC","date",true), new Field("minItemCount","Minimum item count","integer",true), new Field("active","Active","boolean",false));
            case "/api/users" -> List.of(new Field("username","Username","text",true), new Field("email","Email","text",true), new Field("firstName","First name","text",true), new Field("lastName","Last name","text",true), new Field("role","Role (cashier/supervisor/admin/master_admin)","text",true), new Field("temporaryPassword","Temporary password","password",true), new Field("active","Active","boolean",false));
            default -> List.of();
        };
    }
}
