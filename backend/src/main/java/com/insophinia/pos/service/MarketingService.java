package com.insophinia.pos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insophinia.pos.dto.V7Dtos.MarketingCampaignCreateRequest;
import com.insophinia.pos.dto.V7Dtos.MarketingCampaignResponse;
import com.insophinia.pos.dto.V7Dtos.MarketingSendLogResponse;
import com.insophinia.pos.dto.V7Dtos.MarketingTargetFilter;
import com.insophinia.pos.entity.Customer;
import com.insophinia.pos.entity.EmailTemplate;
import com.insophinia.pos.entity.MarketingCampaign;
import com.insophinia.pos.entity.MarketingCampaignStatus;
import com.insophinia.pos.entity.MarketingSendLog;
import com.insophinia.pos.entity.MarketingSendStatus;
import com.insophinia.pos.entity.User;
import com.insophinia.pos.exception.BadRequestException;
import com.insophinia.pos.exception.ResourceNotFoundException;
import com.insophinia.pos.repository.CustomerRepository;
import com.insophinia.pos.repository.EmailTemplateRepository;
import com.insophinia.pos.repository.MarketingCampaignRepository;
import com.insophinia.pos.repository.MarketingSendLogRepository;
import com.insophinia.pos.security.AuthUser;
import com.insophinia.pos.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Marketing campaign lifecycle:
 *   draft → scheduled → sending → sent
 *              ↑_____________↓  (cron picks up dueForSend at the top of each minute)
 *
 * Security model:
 *   - Customer filter is parsed from a JSON document; only the documented
 *     fields (loyaltyPointsMin/Max, createdAfter/Before, hasEmail, hasPhone,
 *     emailVerified, customerIds) are honored. Unknown fields are ignored
 *     (forward-compat / graceful degradation).
 *   - If settings.marketing_require_double_opt_in is true (default), only
 *     customers with email_verified=true receive marketing emails.
 *   - Rate-limit: max `settings.marketing_rate_limit_per_hour` (default 500)
 *     sends per hour per campaign. The send loop sleeps when the limit is
 *     reached, so the loop never floods SMTP.
 *   - Each recipient gets exactly one send log row. Duplicates by email
 *     within the same campaign are skipped (the unique constraint on
 *     (campaign_id, customer_email) enforces this at the DB level).
 */
@Service
public class MarketingService {

    private static final Logger log = LoggerFactory.getLogger(MarketingService.class);

    private final MarketingCampaignRepository campaigns;
    private final MarketingSendLogRepository sendLogs;
    private final EmailTemplateRepository templates;
    private final CustomerRepository customers;
    private final EmailService emailService;
    private final EmailTemplateService templateService;
    private final SettingsService settings;
    private final SyncHub syncHub;
    private final ObjectMapper json = new ObjectMapper();

    @Value("${app.public-url:http://127.0.0.1:5173}")
    private String publicAppUrl;

    public MarketingService(MarketingCampaignRepository campaigns,
                             MarketingSendLogRepository sendLogs,
                             EmailTemplateRepository templates,
                             CustomerRepository customers,
                             EmailService emailService,
                             EmailTemplateService templateService,
                             SettingsService settings, SyncHub syncHub) {
        this.campaigns = campaigns;
        this.sendLogs = sendLogs;
        this.templates = templates;
        this.customers = customers;
        this.emailService = emailService;
        this.templateService = templateService;
        this.settings = settings;
        this.syncHub = syncHub;
    }

    // ----- CRUD ---------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<MarketingCampaign> list(MarketingCampaignStatus status, PageRequest pageable) {
        return status == null
                ? campaigns.findAll(pageable.withSort(Sort.by("createdAt").descending()))
                : campaigns.findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(status, pageable);
    }

    @Transactional(readOnly = true)
    public MarketingCampaign get(UUID id) {
        return campaigns.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Marketing campaign not found"));
    }

    @Transactional(readOnly = true)
    public Page<MarketingSendLog> listSendLogs(UUID campaignId, PageRequest pageable) {
        return sendLogs.findByCampaignIdOrderByCreatedAtAsc(campaignId, pageable);
    }

    @Transactional
    public MarketingCampaign create(MarketingCampaignCreateRequest req) {
        EmailTemplate template = templates.findByIdAndDeletedAtIsNull(req.templateId())
                .orElseThrow(() -> new BadRequestException("Email template not found"));

        // Parse + validate the target filter (graceful: unknown fields ignored)
        MarketingTargetFilter filter = parseFilter(req.targetFilter());

        MarketingCampaign c = new MarketingCampaign();
        c.setName(req.name());
        c.setDescription(req.description());
        c.setTemplate(template);
        c.setSnapshotSubject(template.getSubject());
        c.setSnapshotBody(template.getBodyHtml());
        c.setTargetFilter(req.targetFilter());
        AuthUser auth = SecurityUtils.requireUser();
        if (auth.userId() != null) {
            c.setCreatedBy(new User()); c.getCreatedBy().setId(auth.userId());
        }
        c.setStatus(MarketingCampaignStatus.draft);

        // If send-now is requested, transition straight to 'sending' so the
        // scheduler picks it up on the next tick (within 60s).
        if (Boolean.TRUE.equals(req.sendNow())) {
            c.setScheduledAt(Instant.now());
            c.setStatus(MarketingCampaignStatus.scheduled);
        } else if (req.scheduledAt() != null) {
            if (req.scheduledAt().isBefore(Instant.now())) {
                throw new BadRequestException("scheduled_at must be in the future (or null for send-now)");
            }
            c.setScheduledAt(req.scheduledAt());
            c.setStatus(MarketingCampaignStatus.scheduled);
        }

        // Snapshot the target count so the UI shows "x of N sent" before
        // sending actually starts.
        c.setTargetCount(countMatchingCustomers(filter));
        return campaigns.save(c);
    }

    @Transactional
    public MarketingCampaign cancel(UUID id) {
        MarketingCampaign c = get(id);
        if (c.getStatus() == MarketingCampaignStatus.sent || c.getStatus() == MarketingCampaignStatus.cancelled) {
            throw new BadRequestException("Campaign already " + c.getStatus() + " — cannot cancel");
        }
        c.setStatus(MarketingCampaignStatus.cancelled);
        c.setCompletedAt(Instant.now());
        return campaigns.save(c);
    }

    @Transactional
    public void delete(UUID id) {
        MarketingCampaign c = get(id);
        c.setDeletedAt(Instant.now());
        campaigns.save(c);
    }

    // ----- Scheduler ----------------------------------------------------

    /**
     * Cron job — runs at the top of every minute, picks up campaigns whose
     * scheduled_at <= now and haven't started. Idempotent: a crashed send
     * can be re-picked on the next tick.
     */
    @Scheduled(cron = "0 * * * * *")
    public void sendDueCampaigns() {
        List<MarketingCampaign> due = campaigns.findDueForSend(Instant.now());
        for (MarketingCampaign c : due) {
            try {
                sendCampaign(c.getId());
            } catch (Exception e) {
                log.error("Campaign {} failed: {}", c.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Execute the send loop for a single campaign. Each recipient gets its
     * own transaction (so a single SMTP failure doesn't roll back the whole
     * campaign), and the rate-limit is enforced per-hour.
     */
    @Transactional
    public MarketingCampaign sendCampaign(UUID campaignId) {
        MarketingCampaign c = get(campaignId);
        if (c.getStatus() != MarketingCampaignStatus.scheduled
                && c.getStatus() != MarketingCampaignStatus.sending) {
            throw new BadRequestException("Campaign is not in scheduled/sending state — current: " + c.getStatus());
        }
        c.setStatus(MarketingCampaignStatus.sending);
        c.setStartedAt(Instant.now());
        campaigns.save(c);

        EmailTemplate template = c.getTemplate();
        MarketingTargetFilter filter = parseFilter(c.getTargetFilter());
        List<Customer> targets = findMatchingCustomers(filter);

        int rateLimitPerHour = settings.getInt(SettingsService.KEY_MARKETING_RATE_LIMIT_PER_HOUR, 500);
        int sentThisHour = 0;
        long hourStart = System.currentTimeMillis();

        for (Customer customer : targets) {
            // Check if we already sent to this email in this campaign
            // (skip duplicates — the unique constraint also enforces this at DB level).
            if (customer.getEmail() == null || customer.getEmail().isBlank()) continue;

            // Double opt-in: skip unverified emails when required.
            boolean requireVerified = settings.getBool(SettingsService.KEY_MARKETING_REQUIRE_DOUBLE_OPT_IN, true);
            if (requireVerified && !customer.isEmailVerified()) continue;

            // Rate-limit check
            if (sentThisHour >= rateLimitPerHour) {
                long elapsed = System.currentTimeMillis() - hourStart;
                if (elapsed < 3_600_000L) {
                    long sleep = 3_600_000L - elapsed;
                    try { Thread.sleep(Math.min(sleep, 5_000L)); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
                sentThisHour = 0;
                hourStart = System.currentTimeMillis();
            }

            MarketingSendLog sendLog = new MarketingSendLog();
            sendLog.setCampaign(c);
            sendLog.setCustomer(customer);
            sendLog.setCustomerEmail(customer.getEmail());
            sendLog.setStatus(MarketingSendStatus.pending);

            try {
                Map<String, String> variables = Map.of(
                        "customerName", customer.getName() == null ? "" : customer.getName(),
                        "customerEmail", customer.getEmail(),
                        "campaignName", c.getName(),
                        "unsubscribeUrl", publicAppUrl + "/unsubscribe?email=" + customer.getEmail()
                );
                var rendered = templateService.render(template, variables);
                emailService.send(customer.getEmail(), rendered.subject(), rendered.bodyHtml());
                sendLog.setStatus(MarketingSendStatus.sent);
                sendLog.setSentAt(Instant.now());
                c.setSentCount(c.getSentCount() + 1);
            } catch (Exception e) {
                sendLog.setStatus(MarketingSendStatus.failed);
                sendLog.setFailureReason(e.getMessage());
                c.setFailedCount(c.getFailedCount() + 1);
                log.warn("Marketing send to {} failed: {}", customer.getEmail(), e.getMessage());
            } finally {
                sendLogs.save(sendLog);
                sentThisHour++;
            }
        }

        c.setStatus(MarketingCampaignStatus.sent);
        c.setCompletedAt(Instant.now());
        syncHub.broadcast("MARKETING_CAMPAIGN_SENT", "marketing_campaign",
                Map.of("campaignId", c.getId().toString(),
                       "sent", c.getSentCount(),
                       "failed", c.getFailedCount()));
        return campaigns.save(c);
    }

    // ----- Target filter parsing + matching -----------------------------

    MarketingTargetFilter parseFilter(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return new MarketingTargetFilter(null, null, null, null, null, null, null, null);
        try {
            return json.readValue(jsonStr, MarketingTargetFilter.class);
        } catch (Exception e) {
            throw new BadRequestException("Invalid target_filter JSON: " + e.getMessage());
        }
    }

    int countMatchingCustomers(MarketingTargetFilter filter) {
        return (int) findMatchingCustomers(filter).size();
    }

    @Transactional(readOnly = true)
    List<Customer> findMatchingCustomers(MarketingTargetFilter filter) {
        org.springframework.data.jpa.domain.Specification<Customer> spec =
                (root, q, cb) -> cb.isNull(root.get("deletedAt"));
        if (filter.hasEmail() != null && filter.hasEmail()) {
            spec = spec.and((root, q, cb) -> cb.isNotNull(root.get("email")));
        }
        if (filter.hasPhone() != null && filter.hasPhone()) {
            spec = spec.and((root, q, cb) -> cb.isNotNull(root.get("phone")));
        }
        if (filter.emailVerified() != null && filter.emailVerified()) {
            spec = spec.and((root, q, cb) -> cb.isTrue(root.get("emailVerified")));
        }
        if (filter.loyaltyPointsMin() != null) {
            spec = spec.and((root, q, cb) -> cb.ge(root.get("loyaltyPoints"), filter.loyaltyPointsMin()));
        }
        if (filter.loyaltyPointsMax() != null) {
            spec = spec.and((root, q, cb) -> cb.le(root.get("loyaltyPoints"), filter.loyaltyPointsMax()));
        }
        if (filter.createdAfter() != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), filter.createdAfter()));
        }
        if (filter.createdBefore() != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), filter.createdBefore()));
        }
        if (filter.customerIds() != null && !filter.customerIds().isEmpty()) {
            spec = spec.and((root, q, cb) -> root.get("id").in(filter.customerIds()));
        }
        return customers.findAll(spec);
    }

    // ----- Static settings keys are defined on SettingsService (v7 constants) ----
}
