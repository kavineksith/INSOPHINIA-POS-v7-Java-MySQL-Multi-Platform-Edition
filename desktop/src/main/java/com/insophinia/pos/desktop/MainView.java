package com.insophinia.pos.desktop;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/** Main application shell; tabs are permission-aware and all writes are server-authorized. */
public class MainView {
    private final BorderPane root;
    private final SyncClient syncClient = new SyncClient();
    private final SyncClient displaySyncClient = new SyncClient();
    private final PosView pos = new PosView();
    private final InventoryView inventory = new InventoryView();
    private final DashboardView dashboard = new DashboardView();
    private final BillsView bills = new BillsView();
    private final CustomerDisplayView display = new CustomerDisplayView();
    private Stage displayStage;
    private boolean displayStreamStarted;

    public MainView(Runnable onLogout) {
        Label user = new Label(Session.fullName + "  (" + Session.role + ")");
        Button openDisplay = new Button("Open customer display"), logout = new Button("Sign out");
        openDisplay.setOnAction(e -> openCustomerDisplay());
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, user, spacer, openDisplay, logout); header.setPadding(new Insets(10)); header.setStyle("-fx-background-color:#1f2937;"); user.setStyle("-fx-text-fill:white;-fx-font-weight:bold;");
        TabPane tabs = new TabPane(); tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(new Tab("Point of Sale", pos.getRoot()));
        if (Session.has("view:dashboard")) tabs.getTabs().add(new Tab("Dashboard", dashboard.getRoot()));
        if (Session.has("view:inventory")) tabs.getTabs().add(new Tab("Inventory", inventory.getRoot()));
        if (Session.has("view:billing")) tabs.getTabs().add(new Tab("Bills", bills.getRoot()));
        addCrudTab(tabs,"Items","/api/items","view:items","manage:items");
        addCrudTab(tabs,"Categories","/api/categories","view:categories","manage:categories");
        addCrudTab(tabs,"Promotions","/api/promotions","view:promotions","manage:promotions");
        addCrudTab(tabs,"Customers","/api/customers","view:customers","manage:customers");
        addCrudTab(tabs,"Users","/api/users","view:users","manage:users");
        // v7 feature tabs
        addResourceTab(tabs,"Warranties","/api/warranties?page=1&limit=50","view:warranties");
        addResourceTab(tabs,"Repairs","/api/repairs?page=1&limit=50","view:repairs");
        if (Session.has("view:reports")) tabs.getTabs().add(new Tab("Reports", new ReportView().getRoot()));
        addResourceTab(tabs,"Marketing","/api/marketing/campaigns?page=1&limit=50","view:marketing");
        addResourceTab(tabs,"Email Templates","/api/email-templates","view:email_templates");
        addResourceTab(tabs,"Security","/api/auth/security-events?page=0&size=50","view:security");
        if (Session.has("view:logs")) tabs.getTabs().add(new Tab("Logs", new LogsView().getRoot()));
        if (Session.has("manage:settings")) tabs.getTabs().add(new Tab("Backups", new BackupsView().getRoot()));
        if (Session.has("view:settings")) tabs.getTabs().add(new Tab("Settings", new SettingsView().getRoot()));
        addResourceTab(tabs,"Device Profiles","/api/settings/device","view:settings");
        if (Session.has("manage:settings")) tabs.getTabs().add(new Tab("Hardware", new HardwareView().getRoot()));
        pos.setCustomerDisplay(display);
        logout.setOnAction(e -> { syncClient.stop(); displaySyncClient.stop(); if(displayStage!=null)displayStage.close(); new Thread(() -> { try { ApiClient.post("/api/auth/logout", null); } catch (Exception ignored) {} Session.clear(); javafx.application.Platform.runLater(onLogout); }).start(); });
        syncClient.start(payload -> { pos.refresh(); inventory.refresh(); dashboard.refresh(); bills.refresh(); });
        tabs.getSelectionModel().selectedItemProperty().addListener((obs,oldTab,newTab)-> { if(newTab!=null && newTab.getText().equals("Dashboard"))dashboard.refresh(); if(newTab!=null && newTab.getText().equals("Inventory"))inventory.refresh(); if(newTab!=null && newTab.getText().equals("Bills"))bills.refresh(); });
        root = new BorderPane(tabs); root.setTop(header);
    }
    private void openCustomerDisplay() {
        if (displayStage == null) { displayStage = new Stage(); displayStage.setTitle("INSOPHINIA POS — Customer Display"); displayStage.setScene(new Scene(display.getRoot(), 900, 600)); displayStage.setOnCloseRequest(e -> { e.consume(); displayStage.hide(); }); }
        displayStage.show(); displayStage.toFront();
        if (Session.displayChannel.length() >= 16 && !displayStreamStarted) {
            displayStreamStarted = true;
            display.showMessage("Connecting to paired checkout terminals…");
            displaySyncClient.startDisplay(Session.displayChannel, payload -> {
                try { display.showRemoteCart(new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload)); }
                catch (Exception ignored) {}
            });
        } else {
            display.showMessage("Cross-terminal display disabled. Configure POS_DISPLAY_CHANNEL (16+ characters) on server and terminals.");
        }
    }
    private void addCrudTab(TabPane tabs,String title,String endpoint,String viewPermission,String managePermission) {
        if(Session.has(viewPermission)) tabs.getTabs().add(new Tab(title,new CrudResourceView(title,endpoint,managePermission).getRoot()));
    }
    private void addResourceTab(TabPane tabs,String title,String endpoint,String permission) {
        if(Session.has(permission)) tabs.getTabs().add(new Tab(title,new ResourceView(title,endpoint).getRoot()));
    }
    public BorderPane getRoot() { return root; }
}
