package com.insophinia.pos.controller;

import com.insophinia.pos.dto.ApiResponse;
import com.insophinia.pos.dto.BillingDtos.BillResponse;
import com.insophinia.pos.dto.BillingDtos.CancelBillRequest;
import com.insophinia.pos.dto.BillingDtos.CreateBillRequest;
import com.insophinia.pos.dto.BillingDtos.EmailReceiptRequest;
import com.insophinia.pos.dto.BillingDtos.ReturnBillRequest;
import com.insophinia.pos.dto.PagedResponse;
import com.insophinia.pos.dto.V7Dtos.BillSendRequest;
import com.insophinia.pos.dto.V7Dtos.BillSendResponse;
import com.insophinia.pos.entity.Bill;
import com.insophinia.pos.entity.BillChannelSendLog;
import com.insophinia.pos.entity.SendChannel;
import com.insophinia.pos.entity.SendFormat;
import com.insophinia.pos.entity.SendStatus;
import com.insophinia.pos.exception.BadRequestException;
import com.insophinia.pos.security.AuthUser;
import com.insophinia.pos.security.SecurityUtils;
import com.insophinia.pos.service.BillPdfService;
import com.insophinia.pos.service.BillingService;
import com.insophinia.pos.service.SystemService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final BillingService billingService;
    private final SystemService systemService;
    private final com.insophinia.pos.service.EmailService emailService;
    private final BillPdfService billPdfService;
    private final com.insophinia.pos.repository.BillChannelSendLogRepository sendLogRepository;

    public BillingController(BillingService billingService, SystemService systemService,
                             com.insophinia.pos.service.EmailService emailService,
                             BillPdfService billPdfService,
                             com.insophinia.pos.repository.BillChannelSendLogRepository sendLogRepository) {
        this.billingService = billingService;
        this.systemService = systemService;
        this.emailService = emailService;
        this.billPdfService = billPdfService;
        this.sendLogRepository = sendLogRepository;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('view:billing')")
    public PagedResponse<BillResponse> list(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int limit,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false, name = "start_date") Instant startDate,
                                            @RequestParam(required = false, name = "end_date") Instant endDate,
                                            @RequestParam(required = false) String search,
                                            @RequestParam(defaultValue = "createdAt") String sort,
                                            @RequestParam(defaultValue = "desc") String direction) {
        int safePage = Math.max(1, page);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        Instant effectiveStart = startDate == null && endDate == null && (search == null || search.isBlank()) ? Instant.now().minus(2, java.time.temporal.ChronoUnit.DAYS) : startDate;
        if (effectiveStart != null && endDate != null && endDate.isBefore(effectiveStart)) throw new BadRequestException("End date must be on or after start date");
        String safeSort = java.util.Set.of("createdAt", "billNumber", "customerName", "totalAmount", "status").contains(sort) ? sort : "createdAt";
        Sort ordering = "asc".equalsIgnoreCase(direction) ? Sort.by(safeSort).ascending() : Sort.by(safeSort).descending();
        return PagedResponse.of(billingService.listBills(status, effectiveStart, endDate, search,
                PageRequest.of(safePage - 1, safeLimit, ordering))
                .map(BillingService::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('view:billing')")
    public ApiResponse<BillResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(BillingService.toResponse(billingService.getBill(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('manage:billing')")
    public ApiResponse<BillResponse> create(@Valid @RequestBody CreateBillRequest req) {
        systemService.assertNotLocked(); // deadman lock hard-blocks checkout
        AuthUser user = SecurityUtils.requireUser();
        return ApiResponse.ok(BillingService.toResponse(billingService.createBill(req, user.userId())),
                "Bill created successfully");
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasAuthority('manage:billing')")
    public ApiResponse<BillResponse> returnBill(@PathVariable UUID id, @Valid @RequestBody ReturnBillRequest req) {
        return ApiResponse.ok(BillingService.toResponse(billingService.returnBill(id, req)), "Bill returned");
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('manage:billing')")
    public ApiResponse<BillResponse> cancelBill(@PathVariable UUID id, @RequestBody(required = false) CancelBillRequest req) {
        return ApiResponse.ok(BillingService.toResponse(
                billingService.cancelBill(id, req == null ? null : req.reason(), req == null ? null : req.authorizerUsername(), req == null ? null : req.authorizerPassword())), "Bill cancelled");
    }

    @PostMapping("/{id}/email")
    @PreAuthorize("hasAuthority('manage:billing')")
    public ApiResponse<Map<String, Boolean>> emailReceipt(@PathVariable UUID id,
            @Valid @RequestBody(required = false) EmailReceiptRequest request) {
        var bill = billingService.getBill(id);
        if (!emailService.isEnabled()) throw new BadRequestException("Receipt email is disabled in Settings");
        String to = request == null ? null : request.email();
        if (to == null || to.isBlank()) to = bill.getCustomerEmail();
        if (to == null || to.isBlank()) throw new BadRequestException("A recipient email address is required");
        String html = "<h2>INSOPHINIA POS Receipt</h2><p>Bill: " + escapeHtml(bill.getBillNumber())
                + "</p><p>Customer: " + escapeHtml(bill.getCustomerName()) + "</p><p>Total: LKR "
                + bill.getTotalAmount().toPlainString() + "</p><p>Paid: LKR " + bill.getPaidAmount().toPlainString()
                + "</p><p>Balance: LKR " + bill.getBalance().toPlainString() + "</p>";
        emailService.sendReceipt(to, "INSOPHINIA POS receipt " + bill.getBillNumber(), html);
        billingService.markEmailSent(id);
        return ApiResponse.ok(Map.of("sent", true), "Receipt email sent");
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    @PostMapping("/{id}/print")
    @PreAuthorize("hasAuthority('manage:billing')")
    public ApiResponse<Map<String, Boolean>> markPrinted(@PathVariable UUID id) {
        billingService.markPrinted(id);
        return ApiResponse.ok(Map.of("printed", true));
    }

    // =====================================================================
    // v7 endpoints — A4 PDF generation + multi-channel send
    // =====================================================================

    /**
     * GET /api/billing/{id}/a4-html — returns the A4 receipt HTML
     * (sanitized + sections toggled via settings). The web client opens this
     * in a popup window and calls window.print(); the desktop client passes
     * the HTML to PDFBox for native PDF rendering.
     */
    @GetMapping(value = "/{id}/a4-html", produces = MediaType.TEXT_HTML_VALUE)
    @PreAuthorize("hasAuthority('view:billing')")
    public ResponseEntity<String> getA4Html(@PathVariable UUID id) {
        Bill bill = billingService.getBill(id);
        String html = billPdfService.buildA4Html(bill);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        headers.set(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
        return new ResponseEntity<>(html, headers, HttpStatus.OK);
    }

    /**
     * POST /api/billing/{id}/send — multi-channel send (WhatsApp text/PDF +
     * email text/PDF). Persists a BillChannelSendLog row for audit + retry.
     *
     * WhatsApp channel: we don't actually send the message ourselves (the
     * WhatsApp Business API requires a paid Meta Business account + the
     * recipient must have opted in). Instead, we return a wa.me URL the
     * operator clicks; their phone opens WhatsApp Web with the prefilled
     * message. For PDF format, we return a URL the operator can attach.
     *
     * Email channel: actually sends via SMTP (existing EmailService).
     */
    @PostMapping("/{id}/send")
    @PreAuthorize("hasAuthority('manage:billing')")
    public ApiResponse<BillSendResponse> sendReceipt(
            @PathVariable UUID id,
            @Valid @RequestBody BillSendRequest req) {
        SendChannel channel;
        SendFormat format;
        try {
            channel = SendChannel.valueOf(req.channel());
            format = SendFormat.valueOf(req.format());
        } catch (Exception e) {
            throw new BadRequestException("Invalid channel/format: " + req.channel() + "/" + req.format());
        }

        Bill bill = billingService.getBill(id);
        AuthUser user = SecurityUtils.requireUser();
        Instant now = Instant.now();
        String failureReason = null;
        SendStatus status = SendStatus.sent;

        if (channel == SendChannel.email) {
            if (!emailService.isEnabled()) {
                status = SendStatus.failed;
                failureReason = "Email sending is disabled in Settings";
            } else {
                try {
                    String html;
                    if (format == SendFormat.pdf) {
                        html = billPdfService.buildA4Html(bill);
                    } else {
                        html = buildTextReceiptEmail(bill);
                    }
                    emailService.sendReceipt(req.recipient(),
                            "INSOPHINIA POS receipt " + bill.getBillNumber(), html);
                } catch (Exception e) {
                    status = SendStatus.failed;
                    failureReason = e.getMessage();
                }
            }
        } else if (channel == SendChannel.whatsapp) {
            // We don't send directly — return a wa.me URL the operator clicks.
            // The send log records the attempt; status is 'sent' once the URL
            // is built (the actual send happens in the operator's WhatsApp).
            String text = format == SendFormat.pdf
                    ? "INSOPHINIA POS receipt " + bill.getBillNumber()
                            + " — PDF: " + buildPdfUrl(bill)
                    : buildWhatsappTextReceipt(bill);
            String waUrl = "https://wa.me/" + sanitizePhone(req.recipient())
                    + "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
            // The URL is what the operator clicks; we don't have a "delivered"
            // status from WhatsApp's free tier. Mark as 'sent' so the audit
            // log captures the operator's intent.
            // (Future: integrate the WhatsApp Business API for real delivery status.)
            failureReason = waUrl; // pass back to the UI via the failure_reason field
        }

        // Persist the send log
        BillChannelSendLog log = new BillChannelSendLog();
        log.setBill(bill);
        log.setChannel(channel);
        log.setFormat(format);
        log.setRecipient(req.recipient());
        log.setStatus(status);
        log.setFailureReason(failureReason);
        if (user.userId() != null) {
            com.insophinia.pos.entity.User sender = new com.insophinia.pos.entity.User();
            sender.setId(user.userId());
            log.setSentBy(sender);
        }
        log.setSentAt(now);
        BillChannelSendLog saved = sendLogRepository.save(log);

        // For WhatsApp channel, surface the wa.me URL in the response so the
        // web client can window.open() it.
        BillSendResponse response = new BillSendResponse(
                saved.getId(), channel.name(), format.name(), req.recipient(),
                status.name(), saved.getSentAt(), channel == SendChannel.whatsapp ? failureReason : null);
        return ApiResponse.ok(response,
                channel == SendChannel.whatsapp ? "WhatsApp URL generated" : "Receipt sent via " + channel);
    }

    /**
     * GET /api/billing/{id}/send-logs — list every WhatsApp/email send for
     * this bill (audit history).
     */
    @GetMapping("/{id}/send-logs")
    @PreAuthorize("hasAuthority('view:billing')")
    public ApiResponse<List<com.insophinia.pos.dto.V7Dtos.BillSendLogResponse>> listSendLogs(@PathVariable UUID id) {
        billingService.getBill(id); // sanity check the bill exists
        return ApiResponse.ok(sendLogRepository.findByBillIdOrderBySentAtDesc(id).stream()
                .map(this::toSendLogResponse).toList());
    }

    private com.insophinia.pos.dto.V7Dtos.BillSendLogResponse toSendLogResponse(BillChannelSendLog l) {
        return new com.insophinia.pos.dto.V7Dtos.BillSendLogResponse(
                l.getId(), l.getBill().getId(), l.getChannel().name(), l.getFormat().name(),
                l.getRecipient(), l.getStatus().name(), l.getFailureReason(),
                l.getSentBy() == null ? null : l.getSentBy().getId(),
                l.getSentAt(), l.getCreatedAt());
    }

    // ----- helpers ------------------------------------------------------

    private String buildWhatsappTextReceipt(Bill bill) {
        StringBuilder sb = new StringBuilder();
        sb.append("INSOPHINIA POS receipt\n");
        sb.append("Bill: ").append(bill.getBillNumber()).append("\n");
        sb.append("Customer: ").append(bill.getCustomerName() == null ? "Walk-in" : bill.getCustomerName()).append("\n");
        sb.append("Date: ").append(bill.getCreatedAt() == null ? "—" : bill.getCreatedAt().toString()).append("\n");
        sb.append("Total: Rs. ").append(bill.getTotalAmount().toPlainString()).append("\n");
        sb.append("Paid: Rs. ").append(bill.getPaidAmount().toPlainString()).append("\n");
        sb.append("Balance: Rs. ").append(bill.getBalance().toPlainString()).append("\n");
        sb.append("\nThank you for your purchase!");
        return sb.toString();
    }

    private String buildTextReceiptEmail(Bill bill) {
        // Plain-text email body for the "text" email format (no A4 PDF attachment).
        // Uses the same content shape as the WhatsApp text version, wrapped in
        // minimal HTML so it renders as a paragraph in the recipient's inbox.
        String text = buildWhatsappTextReceipt(bill);
        return "<pre style=\"font-family: monospace; font-size: 12pt;\">" + escapeHtml(text) + "</pre>";
    }

    private String buildPdfUrl(Bill bill) {
        // The web client serves /a4-html?id={billId}; the desktop client can
        // pass this URL to a PDF rendering service.
        return "/api/billing/" + bill.getId() + "/a4-html";
    }

    /** Strip everything except digits for the wa.me URL. */
    private String sanitizePhone(String phone) {
        if (phone == null) return "";
        // Drop leading + and any non-digit chars
        String digits = phone.replaceAll("[^0-9]", "");
        // Sri Lankan 07xxxxxxxx → 947xxxxxxxx (WhatsApp requires country code, no +)
        if (digits.startsWith("07") && digits.length() == 10) {
            digits = "94" + digits.substring(1);
        }
        return digits;
    }
}
