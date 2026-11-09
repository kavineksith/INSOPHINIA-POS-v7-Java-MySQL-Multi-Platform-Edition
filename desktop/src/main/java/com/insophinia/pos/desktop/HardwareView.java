package com.insophinia.pos.desktop;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Hardware diagnostics for ESC/POS printers, cash drawers and serial barcode scanners. */
public final class HardwareView {
    private final BorderPane root = new BorderPane();
    private final ListView<String> printers = new ListView<>();
    private final ListView<String> ports = new ListView<>();
    private final TextArea status = new TextArea();
    public HardwareView() {
        Label heading = new Label("Hardware & Devices"); heading.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        Label printerLabel = new Label("Installed printers (set POS_PRINTER_NAME to select one)");
        Label portLabel = new Label("Serial scanner ports (keyboard-wedge scanners work in Point of Sale)");
        Button refresh = new Button("Refresh devices"); refresh.setOnAction(e -> refresh());
        Button testPrint = new Button("Print test receipt"); testPrint.setOnAction(e -> runHardware("Test print", HardwareService::testPrint));
        Button drawer = new Button("Kick cash drawer"); drawer.setOnAction(e -> runHardware("Cash drawer", HardwareService::kickCashDrawer));
        HBox actions = new HBox(10, refresh, testPrint, drawer);
        VBox top = new VBox(10, heading, actions); top.setPadding(new Insets(14));
        VBox deviceList = new VBox(6, printerLabel, printers, portLabel, ports); deviceList.setPadding(new Insets(12));
        printers.setPrefHeight(130); ports.setPrefHeight(100); VBox.setVgrow(printers, javafx.scene.layout.Priority.ALWAYS);
        status.setEditable(false); status.setPrefRowCount(4); status.setPromptText("Device status and diagnostics");
        root.setTop(top); root.setCenter(deviceList); root.setBottom(status); BorderPane.setMargin(status, new Insets(12));
        refresh();
    }
    public BorderPane getRoot() { return root; }
    private void refresh() {
        printers.getItems().setAll(HardwareService.printers());
        ports.getItems().setAll(SerialBarcodeScanner.availablePorts());
        status.setText("Printers: " + printers.getItems().size() + " | Serial ports: " + ports.getItems().size()
                + "\nConfigure POS_PRINTER_NAME with the exact printer name before using receipts or cash-drawer pulses.");
    }
    private void runHardware(String label, HardwareAction action) {
        try { action.run(); status.setText(label + " command submitted successfully."); }
        catch (Exception ex) { status.setText(label + " failed: " + ex.getMessage()); new Alert(Alert.AlertType.WARNING, status.getText()).showAndWait(); }
    }
    @FunctionalInterface private interface HardwareAction { void run() throws Exception; }
}
