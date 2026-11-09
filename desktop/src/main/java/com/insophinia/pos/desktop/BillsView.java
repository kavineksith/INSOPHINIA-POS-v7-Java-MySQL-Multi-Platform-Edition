package com.insophinia.pos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

/** Bill history, receipt export/printing, email, return and supervised cancellation. */
public final class BillsView {
    private final BorderPane root = new BorderPane();
    private final TableView<JsonNode> table = new TableView<>();
    private final Label status = new Label("Ready");
    private final TextField search = new TextField();
    private final TextField from = new TextField();
    private final TextField to = new TextField();
    private final ComboBox<String> statusFilter = new ComboBox<>();
    private final ComboBox<String> sortFilter = new ComboBox<>();
    private final ComboBox<String> directionFilter = new ComboBox<>();
    private final Label pageLabel = new Label("Page 1");
    private int page = 1, pages = 1;
    public BillsView() {
        Label title = new Label("Bills & receipts"); title.setStyle("-fx-font-size:20px;-fx-font-weight:bold;");
        Button refresh = new Button("Apply filters"), clear = new Button("Clear filters"), prev = new Button("Previous"), next = new Button("Next"), pdf = new Button("Save PDF"), print = new Button("Print"), a4print = new Button("A4 Print"), email = new Button("Email"), waText = new Button("WhatsApp"), emailPdf = new Button("Email PDF"), cancel = new Button("Cancel bill"), ret = new Button("Return bill");
        search.setPromptText("Bill ID/number, customer, mobile or email"); search.setPrefWidth(260);
        from.setPromptText("From date YYYY-MM-DD"); from.setPrefWidth(190); to.setPromptText("To date YYYY-MM-DD"); to.setPrefWidth(190);
        statusFilter.getItems().setAll("All statuses", "completed", "pending", "cancelled", "returned"); statusFilter.setValue("All statuses");
        sortFilter.getItems().setAll("createdAt", "billNumber", "customerName", "totalAmount", "status"); sortFilter.setValue("createdAt");
        directionFilter.getItems().setAll("desc", "asc"); directionFilter.setValue("desc");
        refresh.setOnAction(e -> { page=1; refresh(); }); clear.setOnAction(e->{search.clear();from.clear();to.clear();statusFilter.setValue("All statuses");sortFilter.setValue("createdAt");directionFilter.setValue("desc");page=1;refresh();});
        prev.setOnAction(e->{if(page>1){page--;refresh();}}); next.setOnAction(e->{if(page<pages){page++;refresh();}});
        pdf.setOnAction(e -> withSelected(this::savePdf)); print.setOnAction(e -> withSelected(this::print)); a4print.setOnAction(e -> withSelected(this::printA4)); email.setOnAction(e -> withSelected(this::email)); waText.setOnAction(e -> withSelected(b -> sendMultiChannel(b, "whatsapp", "text"))); emailPdf.setOnAction(e -> withSelected(b -> sendMultiChannel(b, "email", "pdf"))); cancel.setOnAction(e -> withSelected(this::cancel)); ret.setOnAction(e -> withSelected(this::returnBill));
        HBox actions = new HBox(8, title, new javafx.scene.layout.Region(), pdf, print, a4print, email, emailPdf, waText, cancel, ret); HBox.setHgrow(actions.getChildren().get(1), javafx.scene.layout.Priority.ALWAYS); actions.setPadding(new Insets(12));
        CheckBox showDeletedBills = new CheckBox("Show deleted");
        showDeletedBills.setOnAction(e->{page=1;refresh();});
        HBox filters = new HBox(8, search, from, to, statusFilter, sortFilter, directionFilter, showDeletedBills, refresh, clear); filters.setPadding(new Insets(0,12,8,12));
        HBox paging = new HBox(10, prev, pageLabel, next); paging.setPadding(new Insets(8,12,8,12));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().setAll(col("Bill #","billNumber"), col("Customer","customerName"), col("Cashier","staffName"), col("Total LKR","totalAmount"), col("Paid LKR","paidAmount"), col("Status","status"), col("Bill points","earnedPoints"), col("Total points after","totalPointsAfter"), col("Created","createdAt"));
        root.setTop(new VBox(6, actions, filters, new Label("Select a bill for PDF, printer, email, return or supervisor-authorized cancellation.")));
        root.setCenter(table); root.setBottom(new VBox(4, paging, status)); BorderPane.setMargin(status,new Insets(8)); refresh();
    }
    public BorderPane getRoot() { return root; }
    private TableColumn<JsonNode,String> col(String label,String key) { TableColumn<JsonNode,String> c=new TableColumn<>(label); c.setCellValueFactory(v->new SimpleStringProperty(v.getValue().path(key).asText(""))); return c; }
    private void withSelected(java.util.function.Consumer<JsonNode> action) { JsonNode b=table.getSelectionModel().getSelectedItem(); if(b==null){alert(Alert.AlertType.INFORMATION,"Select a bill first.");return;} action.accept(b); }
    public void refresh() { status.setText("Loading bills…"); StringBuilder url=new StringBuilder("/api/billing?page=").append(page).append("&limit=50");
        if(!search.getText().trim().isEmpty())url.append("&search=").append(java.net.URLEncoder.encode(search.getText().trim(),java.nio.charset.StandardCharsets.UTF_8));
        if(!from.getText().trim().isEmpty())url.append("&start_date=").append(java.net.URLEncoder.encode(normalizeDate(from.getText().trim(),false),java.nio.charset.StandardCharsets.UTF_8));
        if(!to.getText().trim().isEmpty())url.append("&end_date=").append(java.net.URLEncoder.encode(normalizeDate(to.getText().trim(),true),java.nio.charset.StandardCharsets.UTF_8));
        if(statusFilter.getValue()!=null&&!statusFilter.getValue().equals("All statuses"))url.append("&status=").append(statusFilter.getValue());
        url.append("&sort=").append(sortFilter.getValue()==null?"createdAt":sortFilter.getValue()).append("&direction=").append(directionFilter.getValue()==null?"desc":directionFilter.getValue());
        new Thread(()->{try { JsonNode response=ApiClient.get(url.toString()); JsonNode d=response.path("data"); List<JsonNode> rows=new ArrayList<>(); if(d.isArray()) d.forEach(rows::add); int newPages=Math.max(1,response.path("pagination").path("pages").asInt(1)); long total=response.path("pagination").path("total").asLong(rows.size()); Platform.runLater(()->{pages=newPages;table.getItems().setAll(rows);pageLabel.setText("Page "+page+" of "+pages);status.setText(total+" matching bills · "+rows.size()+" on this page");}); } catch(Exception e){Platform.runLater(()->status.setText("Unable to load bills: "+e.getMessage()));}},"bills-refresh").start(); }
    private static String normalizeDate(String raw, boolean end) {
        if (raw.matches("\\d{4}-\\d{2}-\\d{2}")) return raw + (end ? "T23:59:59.999999999Z" : "T00:00:00Z");
        return raw;
    }
    private void savePdf(JsonNode bill) { FileChooser chooser=new FileChooser(); chooser.setTitle("Save receipt as PDF"); chooser.setInitialFileName("receipt-"+bill.path("billNumber").asText("bill")+".pdf"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF document","*.pdf")); File f=chooser.showSaveDialog(root.getScene().getWindow()); if(f==null)return; try{ReceiptService.savePdf(bill,Path.of(f.toURI()));alert(Alert.AlertType.INFORMATION,"PDF receipt saved to "+f.getAbsolutePath());}catch(Exception e){alert(Alert.AlertType.ERROR,"PDF export failed: "+e.getMessage());} }
    private void print(JsonNode bill) { try { ReceiptService.printNative(bill); } catch(Exception e){alert(Alert.AlertType.ERROR,e.getMessage());} }
    private void printA4(JsonNode bill) { new Thread(()->{try{String html=ApiClient.getRaw("/api/billing/"+bill.path("id").asText()+"/a4-html");Platform.runLater(()->{javafx.scene.web.WebView wv=new javafx.scene.web.WebView();wv.getEngine().loadContent(html);Button printBtn=new Button("Print A4");printBtn.setOnAction(e->{javafx.print.PrinterJob job=javafx.print.PrinterJob.createPrinterJob();if(job!=null&&job.showPrintDialog(wv.getScene().getWindow())){wv.getEngine().print(job);job.endJob();}});VBox box=new VBox(8,printBtn,wv);VBox.setVgrow(wv,javafx.scene.layout.Priority.ALWAYS);Stage s=new Stage();s.setTitle("A4 Receipt — "+bill.path("billNumber").asText(""));s.setScene(new Scene(box,800,1100));s.show();});}catch(Exception e){Platform.runLater(()->alert(Alert.AlertType.ERROR,"A4 print failed: "+e.getMessage()));}},"a4-print").start(); }
    private void email(JsonNode bill) { TextInputDialog d=new TextInputDialog(bill.path("customerEmail").asText("")); d.setTitle("Email receipt");d.setHeaderText("Send receipt to email address");d.setContentText("Recipient:"); d.showAndWait().ifPresent(to->new Thread(()->{try{ApiClient.post("/api/billing/"+bill.path("id").asText()+"/email",Map.of("email",to.trim()));Platform.runLater(()->alert(Alert.AlertType.INFORMATION,"Receipt email sent."));}catch(Exception e){Platform.runLater(()->alert(Alert.AlertType.ERROR,"Email failed: "+e.getMessage()));}},"receipt-email").start()); }
    /** v7 multi-channel send — WhatsApp text/PDF + email text/PDF via POST /api/billing/{id}/send */
    private void sendMultiChannel(JsonNode bill, String channel, String format) {
        String defaultRecipient = channel.equals("email") ? bill.path("customerEmail").asText("") : bill.path("customerPhone").asText("");
        TextInputDialog d=new TextInputDialog(defaultRecipient); d.setTitle("Send receipt via "+channel+" ("+format+")");
        d.setHeaderText("Recipient "+(channel.equals("email")?"email address":"phone (with country code)"));
        d.setContentText("Recipient:"); d.showAndWait().ifPresent(recipient->new Thread(()->{
            try{JsonNode r=ApiClient.post("/api/billing/"+bill.path("id").asText()+"/send",
                    Map.of("channel",channel,"format",format,"recipient",recipient.trim()));
                JsonNode data=r.path("data");
                if(channel.equals("whatsapp")&&!data.path("failureReason").isMissingNode()){
                    String waUrl=data.path("failureReason").asText();
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(waUrl));
                }
                Platform.runLater(()->alert(Alert.AlertType.INFORMATION,
                    channel.equals("email")?"Receipt sent via email.":"WhatsApp URL opened in your browser."));
            }catch(Exception e){Platform.runLater(()->alert(Alert.AlertType.ERROR,"Send failed: "+e.getMessage()));}
        },"bill-send").start());
    }
    private Map<String,String> supervisorDialog(String title) { Dialog<Map<String,String>> d=new Dialog<>();d.setTitle(title);d.setHeaderText("Supervisor credentials are required. This is validated by the server."); TextField user=new TextField();user.setPromptText("Supervisor username");PasswordField pass=new PasswordField();pass.setPromptText("Supervisor password");TextField reason=new TextField();reason.setPromptText("Reason");VBox box=new VBox(8,user,pass,reason);box.setPadding(new Insets(12));d.getDialogPane().setContent(box);d.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);d.setResultConverter(b->b==ButtonType.OK?Map.of("authorizerUsername",user.getText().trim(),"authorizerPassword",pass.getText(),"reason",reason.getText().trim()):null);return d.showAndWait().orElse(null); }
    private void cancel(JsonNode bill) { Map<String,String> v=supervisorDialog("Cancel bill");if(v==null)return; new Thread(()->{try{ApiClient.post("/api/billing/"+bill.path("id").asText()+"/cancel",v);Platform.runLater(()->{alert(Alert.AlertType.INFORMATION,"Bill cancelled.");refresh();});}catch(Exception e){Platform.runLater(()->alert(Alert.AlertType.ERROR,"Cancellation failed: "+e.getMessage()));}},"bill-cancel").start(); }
    private void returnBill(JsonNode bill) { Map<String,String> v=supervisorDialog("Return bill");if(v==null)return; if(v.get("reason").isBlank()){alert(Alert.AlertType.WARNING,"A return reason is required.");return;} new Thread(()->{try{ApiClient.post("/api/billing/"+bill.path("id").asText()+"/return",v);Platform.runLater(()->{alert(Alert.AlertType.INFORMATION,"Return recorded.");refresh();});}catch(Exception e){Platform.runLater(()->alert(Alert.AlertType.ERROR,"Return failed: "+e.getMessage()));}},"bill-return").start(); }
    private void alert(Alert.AlertType type,String text){new Alert(type,text).showAndWait();}
}
