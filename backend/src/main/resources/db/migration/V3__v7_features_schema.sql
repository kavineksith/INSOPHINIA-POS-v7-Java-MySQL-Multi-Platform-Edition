-- =============================================================================
-- INSOPHINIA POS — v7.0 feature schema
-- =============================================================================
-- Adds the v7 feature surfaces to the the baseline schema:
--   1. BillItem sub-items + specifications + warranty fields
--   2. Warranty + WarrantyClaim (separate section for warranty claims)
--   3. Repair jobs (separate section for repair/maintenance work)
--   4. EmailTemplate (custom email templates with variable allowlist)
--   5. MarketingCampaign + MarketingSendLog (bulk customer marketing)
--   6. A4 bill template settings (sections to include + T&C content)
--   7. WhatsApp share log (text vs PDF version of receipts)
--
-- All new tables use the same conventions as V1:
--   - BINARY(16) UUID PKs (Hibernate generates app-side)
--   - DATETIME(3) timestamps
--   - VARCHAR enum literals with CHECK constraints
--   - InnoDB + utf8mb4 + utf8mb4_general_ci
--   - Soft-delete columns (deleted_at) where applicable
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. Extend bill_items for v7 sub-items + specifications + warranty
-- -----------------------------------------------------------------------------
-- Sub-items: a bill item can have N child bill items (e.g. "Website
-- development" parent line with sub-tasks "design", "frontend", "backend",
-- each billed separately). Self-referential FK; parent BillItem must exist.
-- Specifications: per-line JSON column for variant details (e.g. "RAM: 16GB,
-- Storage: 512GB SSD, Color: Space Gray"). Stored as JSON text since MySQL
-- JSON columns have type-coercion quirks under Hibernate; we validate the
-- schema in the service layer.
-- Warranty: months + expiry date for warranty-backed items (computer shop,
-- repair, freelance deliverables with guarantee period).
-- Revisions: count + price for billable revision rounds (freelance scope).
ALTER TABLE bill_items
  ADD COLUMN parent_bill_item_id BINARY(16) NULL              AFTER id,
  ADD COLUMN specifications       MEDIUMTEXT   NULL            AFTER item_name,
  ADD COLUMN warranty_months    INT          NOT NULL DEFAULT 0  AFTER subtotal,
  ADD COLUMN warranty_expires_at DATETIME(3) NULL              AFTER warranty_months,
  ADD COLUMN revision_count     INT          NOT NULL DEFAULT 0  AFTER warranty_expires_at,
  ADD COLUMN revision_price     DECIMAL(10,2) NOT NULL DEFAULT 0.00 AFTER revision_count,
  ADD COLUMN sort_order         INT          NOT NULL DEFAULT 0  AFTER revision_price,
  ADD CONSTRAINT bill_items_parent_fk
    FOREIGN KEY (parent_bill_item_id) REFERENCES bill_items (id)
    ON DELETE CASCADE ON UPDATE CASCADE;

CREATE INDEX idx_bill_items_parent       ON bill_items (parent_bill_item_id);
CREATE INDEX idx_bill_items_warranty_exp ON bill_items (warranty_expires_at);

-- -----------------------------------------------------------------------------
-- 2. Warranty registry — separate section that lists every warranty-backed
--    bill item, with its expiry. A bill item with warranty_months > 0 is
--    auto-promoted to a Warranty row on bill creation; rows can also be
--    manually added for items sold before v7.
-- -----------------------------------------------------------------------------
CREATE TABLE warranties (
  id                  BINARY(16)   NOT NULL,
  bill_id             BINARY(16)   NOT NULL,
  bill_item_id        BINARY(16)   NOT NULL,
  customer_id         BINARY(16)   NULL,
  customer_name       VARCHAR(255) NOT NULL DEFAULT 'Walk-in',
  customer_email      VARCHAR(255) NULL,
  customer_phone      VARCHAR(20)  NULL,
  item_id             BINARY(16)   NULL,
  item_name           VARCHAR(255) NOT NULL,
  plu_code            VARCHAR(50)  NULL,
  serial_number       VARCHAR(100) NULL,
  warranty_months     INT          NOT NULL DEFAULT 0,
  warranty_starts_at  DATETIME(3)  NOT NULL,
  warranty_expires_at DATETIME(3)  NOT NULL,
  status              VARCHAR(20)  NOT NULL DEFAULT 'active',
  notes               MEDIUMTEXT   NULL,
  created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at          DATETIME(3)  NULL,
  CONSTRAINT warranties_pkey PRIMARY KEY (id),
  CONSTRAINT warranties_status_chk CHECK (status IN ('active','expiring','expired','claimed','voided')),
  CONSTRAINT warranties_bill_fk      FOREIGN KEY (bill_id)      REFERENCES bills (id)      ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT warranties_bill_item_fk FOREIGN KEY (bill_item_id) REFERENCES bill_items (id) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT warranties_customer_fk  FOREIGN KEY (customer_id)  REFERENCES customers (id)  ON DELETE SET NULL ON UPDATE CASCADE,
  CONSTRAINT warranties_item_fk      FOREIGN KEY (item_id)      REFERENCES items (id)       ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_warranties_customer        ON warranties (customer_id);
CREATE INDEX idx_warranties_bill            ON warranties (bill_id);
CREATE INDEX idx_warranties_status_expiry  ON warranties (status, warranty_expires_at);
CREATE INDEX idx_warranties_expires_at      ON warranties (warranty_expires_at);
CREATE INDEX idx_warranties_deleted_at       ON warranties (deleted_at);

-- -----------------------------------------------------------------------------
-- 3. WarrantyClaim — each claim filed against a warranty (customer reports
--    a fault; technician investigates; resolves as repair / replacement /
--    refund / rejected). A warranty can have multiple claims.
-- -----------------------------------------------------------------------------
CREATE TABLE warranty_claims (
  id              BINARY(16)   NOT NULL,
  warranty_id     BINARY(16)   NOT NULL,
  customer_id     BINARY(16)   NULL,
  customer_name   VARCHAR(255) NOT NULL DEFAULT 'Walk-in',
  claim_number    VARCHAR(50)  NOT NULL,
  fault_reported  MEDIUMTEXT   NOT NULL,
  technician_id   BINARY(16)   NULL,
  technician_name VARCHAR(255) NULL,
  diagnosis      MEDIUMTEXT   NULL,
  resolution     VARCHAR(30)  NOT NULL DEFAULT 'pending',
  resolution_notes MEDIUMTEXT NULL,
  replacement_item_id BINARY(16) NULL,
  refund_amount  DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  status          VARCHAR(20)  NOT NULL DEFAULT 'open',
  filed_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  resolved_at     DATETIME(3)  NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3)  NULL,
  CONSTRAINT warranty_claims_pkey PRIMARY KEY (id),
  CONSTRAINT warranty_claims_claim_number_unique UNIQUE (claim_number),
  CONSTRAINT warranty_claims_resolution_chk CHECK (resolution IN ('pending','repair','replacement','refund','rejected','escalated')),
  CONSTRAINT warranty_claims_status_chk      CHECK (status IN ('open','in_progress','resolved','rejected','closed')),
  CONSTRAINT warranty_claims_warranty_fk  FOREIGN KEY (warranty_id) REFERENCES warranties (id)  ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT warranty_claims_customer_fk FOREIGN KEY (customer_id) REFERENCES customers (id)   ON DELETE SET NULL ON UPDATE CASCADE,
  CONSTRAINT warranty_claims_tech_fk     FOREIGN KEY (technician_id) REFERENCES users (id)     ON DELETE SET NULL ON UPDATE CASCADE,
  CONSTRAINT warranty_claims_item_fk     FOREIGN KEY (replacement_item_id) REFERENCES items (id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_warranty_claims_warranty ON warranty_claims (warranty_id);
CREATE INDEX idx_warranty_claims_status   ON warranty_claims (status);
CREATE INDEX idx_warranty_claims_customer ON warranty_claims (customer_id);
CREATE INDEX idx_warranty_claims_filed_at  ON warranty_claims (filed_at);

-- -----------------------------------------------------------------------------
-- 4. Repair jobs — separate section for repair / maintenance work that may
--    or may not be tied to a warranty. Used by freelance developers, repair
--    shops, and computer shops for repair-job tracking.
--
--    Status workflow: received → diagnosed → in_progress → completed →
--    delivered → closed (or rejected at any point).
-- -----------------------------------------------------------------------------
CREATE TABLE repairs (
  id                  BINARY(16)   NOT NULL,
  repair_number       VARCHAR(50)  NOT NULL,
  customer_id         BINARY(16)   NULL,
  customer_name       VARCHAR(255) NOT NULL DEFAULT 'Walk-in',
  customer_email      VARCHAR(255) NULL,
  customer_phone      VARCHAR(20)  NULL,
  item_description    VARCHAR(500) NOT NULL,
  item_serial         VARCHAR(100) NULL,
  problem_reported    MEDIUMTEXT   NOT NULL,
  diagnosis           MEDIUMTEXT   NULL,
  technician_id       BINARY(16)   NULL,
  technician_name     VARCHAR(255) NULL,
  status              VARCHAR(30)  NOT NULL DEFAULT 'received',
  priority            VARCHAR(20)  NOT NULL DEFAULT 'normal',
  received_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  diagnosed_at        DATETIME(3)  NULL,
  started_at          DATETIME(3)  NULL,
  completed_at        DATETIME(3)  NULL,
  delivered_at        DATETIME(3)  NULL,
  closed_at           DATETIME(3)  NULL,
  estimated_cost      DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  actual_cost         DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  final_bill_id        BINARY(16)   NULL,
  warranty_id         BINARY(16)   NULL,
  warranty_months     INT          NOT NULL DEFAULT 0,
  notes               MEDIUMTEXT   NULL,
  created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at          DATETIME(3)  NULL,
  CONSTRAINT repairs_pkey PRIMARY KEY (id),
  CONSTRAINT repairs_repair_number_unique UNIQUE (repair_number),
  CONSTRAINT repairs_status_chk   CHECK (status IN ('received','diagnosed','in_progress','awaiting_parts','completed','delivered','closed','rejected','cancelled')),
  CONSTRAINT repairs_priority_chk CHECK (priority IN ('low','normal','high','urgent')),
  CONSTRAINT repairs_customer_fk  FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE SET NULL ON UPDATE CASCADE,
  CONSTRAINT repairs_tech_fk      FOREIGN KEY (technician_id) REFERENCES users (id) ON DELETE SET NULL ON UPDATE CASCADE,
  CONSTRAINT repairs_bill_fk      FOREIGN KEY (final_bill_id) REFERENCES bills (id) ON DELETE SET NULL ON UPDATE CASCADE,
  CONSTRAINT repairs_warranty_fk FOREIGN KEY (warranty_id) REFERENCES warranties (id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_repairs_customer      ON repairs (customer_id);
CREATE INDEX idx_repairs_status        ON repairs (status);
CREATE INDEX idx_repairs_priority      ON repairs (priority);
CREATE INDEX idx_repairs_technician    ON repairs (technician_id);
CREATE INDEX idx_repairs_received_at   ON repairs (received_at);
CREATE INDEX idx_repairs_deleted_at     ON repairs (deleted_at);

-- Repair parts / labour line items — what was actually done during the repair
CREATE TABLE repair_items (
  id                BINARY(16)   NOT NULL,
  repair_id         BINARY(16)   NOT NULL,
  line_type         VARCHAR(20)  NOT NULL DEFAULT 'labour',
  description       VARCHAR(500) NOT NULL,
  item_id           BINARY(16)   NULL,
  quantity          DECIMAL(10,3) NOT NULL DEFAULT 1.000,
  unit_price        DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  discount          DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  subtotal          DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  notes             MEDIUMTEXT   NULL,
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at        DATETIME(3)  NULL,
  CONSTRAINT repair_items_pkey PRIMARY KEY (id),
  CONSTRAINT repair_items_type_chk CHECK (line_type IN ('labour','part','fee','tax','other')),
  CONSTRAINT repair_items_repair_fk FOREIGN KEY (repair_id) REFERENCES repairs (id) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT repair_items_item_fk   FOREIGN KEY (item_id) REFERENCES items (id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_repair_items_repair ON repair_items (repair_id);

-- -----------------------------------------------------------------------------
-- 5. EmailTemplate — user-defined email templates for receipts + marketing.
--    Body supports HTML with a strict variable allowlist ({{billNumber}},
--    {{customerName}}, {{totalAmount}}, etc.). Variables are substituted
--    server-side before send; arbitrary template variables are rejected.
--
--    `kind` discriminates receipt / marketing / test / custom templates so
--    the UI can group them.
-- -----------------------------------------------------------------------------
CREATE TABLE email_templates (
  id              BINARY(16)   NOT NULL,
  name            VARCHAR(100) NOT NULL,
  kind            VARCHAR(30)  NOT NULL DEFAULT 'custom',
  subject         VARCHAR(255) NOT NULL,
  body_html       MEDIUMTEXT   NOT NULL,
  body_text       MEDIUMTEXT   NULL,
  -- Whitelist of variables this template may reference (CSV; validated
  -- against the canonical variable registry in EmailTemplateService).
  allowed_variables VARCHAR(500) NULL,
  is_active        TINYINT(1)  NOT NULL DEFAULT 1,
  is_default       TINYINT(1)  NOT NULL DEFAULT 0,
  created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at       DATETIME(3) NULL,
  CONSTRAINT email_templates_pkey PRIMARY KEY (id),
  CONSTRAINT email_templates_name_unique UNIQUE (name),
  CONSTRAINT email_templates_kind_chk CHECK (kind IN ('receipt','marketing','test','welcome','custom')),
  CONSTRAINT email_templates_body_chk  CHECK (LENGTH(body_html) <= 65535)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_email_templates_kind      ON email_templates (kind);
CREATE INDEX idx_email_templates_default   ON email_templates (is_default);
CREATE INDEX idx_email_templates_deleted_at ON email_templates (deleted_at);

-- -----------------------------------------------------------------------------
-- 6. MarketingCampaign — bulk-email sends to filtered customer segments.
--    Stores the template + filter criteria + scheduling + send count.
--
--    `status` lifecycle: draft → scheduled → sending → sent → cancelled.
--    `target_filter` is a JSON document describing the customer filter
--    (loyalty_points_min, loyalty_points_max, created_after, created_before,
--    has_email=true, has_phone=true, etc.).
-- -----------------------------------------------------------------------------
CREATE TABLE marketing_campaigns (
  id                BINARY(16)   NOT NULL,
  name              VARCHAR(255) NOT NULL,
  description       MEDIUMTEXT   NULL,
  template_id       BINARY(16)   NOT NULL,
  status            VARCHAR(20)  NOT NULL DEFAULT 'draft',
  target_filter     MEDIUMTEXT   NULL,
  scheduled_at      DATETIME(3)  NULL,
  started_at        DATETIME(3)  NULL,
  completed_at      DATETIME(3)  NULL,
  -- Snapshot of the template body at send time, so historical sends remain
  -- reproducible even if the template is later edited.
  snapshot_subject  VARCHAR(255) NOT NULL,
  snapshot_body     MEDIUMTEXT   NOT NULL,
  target_count      INT          NOT NULL DEFAULT 0,
  sent_count        INT          NOT NULL DEFAULT 0,
  failed_count      INT          NOT NULL DEFAULT 0,
  opened_count      INT          NOT NULL DEFAULT 0,
  clicked_count     INT          NOT NULL DEFAULT 0,
  created_by        BINARY(16)   NULL,
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at        DATETIME(3)  NULL,
  CONSTRAINT marketing_campaigns_pkey PRIMARY KEY (id),
  CONSTRAINT marketing_campaigns_status_chk CHECK (status IN ('draft','scheduled','sending','sent','cancelled','failed')),
  CONSTRAINT marketing_campaigns_template_fk FOREIGN KEY (template_id) REFERENCES email_templates (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT marketing_campaigns_creator_fk  FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_marketing_campaigns_status     ON marketing_campaigns (status);
CREATE INDEX idx_marketing_campaigns_scheduled  ON marketing_campaigns (scheduled_at);
CREATE INDEX idx_marketing_campaigns_created_by  ON marketing_campaigns (created_by);

-- MarketingSendLog — one row per recipient per campaign. Used to retry
-- failures + audit every send.
CREATE TABLE marketing_send_logs (
  id             BINARY(16)   NOT NULL,
  campaign_id    BINARY(16)   NOT NULL,
  customer_id    BINARY(16)   NULL,
  customer_email VARCHAR(255) NOT NULL,
  status         VARCHAR(20)  NOT NULL DEFAULT 'pending',
  failure_reason MEDIUMTEXT   NULL,
  provider_message_id VARCHAR(255) NULL,
  sent_at        DATETIME(3)  NULL,
  opened_at      DATETIME(3)  NULL,
  clicked_at     DATETIME(3)  NULL,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT marketing_send_logs_pkey PRIMARY KEY (id),
  CONSTRAINT marketing_send_logs_status_chk CHECK (status IN ('pending','sent','failed','bounced','opened','clicked')),
  CONSTRAINT marketing_send_logs_campaign_fk FOREIGN KEY (campaign_id) REFERENCES marketing_campaigns (id) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT marketing_send_logs_customer_fk FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE SET NULL ON UPDATE CASCADE,
  CONSTRAINT marketing_send_logs_email_unique UNIQUE (campaign_id, customer_email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_marketing_send_logs_campaign ON marketing_send_logs (campaign_id);
CREATE INDEX idx_marketing_send_logs_status   ON marketing_send_logs (status);
CREATE INDEX idx_marketing_send_logs_customer ON marketing_send_logs (customer_id);

-- -----------------------------------------------------------------------------
-- 7. BillChannelSendLog — records every WhatsApp / email send per bill
--    (text vs PDF). Used for audit + retry.
-- -----------------------------------------------------------------------------
CREATE TABLE bill_channel_send_logs (
  id            BINARY(16)   NOT NULL,
  bill_id       BINARY(16)   NOT NULL,
  channel       VARCHAR(20)  NOT NULL,
  format        VARCHAR(10)  NOT NULL,
  recipient     VARCHAR(255) NOT NULL,
  status        VARCHAR(20)  NOT NULL DEFAULT 'sent',
  failure_reason MEDIUMTEXT  NULL,
  sent_by       BINARY(16)   NULL,
  sent_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT bill_channel_send_logs_pkey PRIMARY KEY (id),
  CONSTRAINT bill_channel_send_logs_channel_chk CHECK (channel IN ('whatsapp','email')),
  CONSTRAINT bill_channel_send_logs_format_chk   CHECK (format IN ('text','pdf')),
  CONSTRAINT bill_channel_send_logs_status_chk    CHECK (status IN ('sent','failed','delivered','bounced','opened')),
  CONSTRAINT bill_channel_send_logs_bill_fk FOREIGN KEY (bill_id) REFERENCES bills (id) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT bill_channel_send_logs_user_fk FOREIGN KEY (sent_by) REFERENCES users (id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_bill_channel_send_logs_bill    ON bill_channel_send_logs (bill_id);
CREATE INDEX idx_bill_channel_send_logs_channel ON bill_channel_send_logs (channel, format);

-- -----------------------------------------------------------------------------
-- 8. Default v7 settings — A4 bill template + T&C defaults
-- -----------------------------------------------------------------------------
INSERT INTO settings (id, `key`, `value`, type, description, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), 'a4_bill_include_subitems', 'true', 'boolean',
       'Show sub-item breakdowns on A4 receipts (thermal receipts always hide them)',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM settings WHERE `key` = 'a4_bill_include_subitems');

INSERT INTO settings (id, `key`, `value`, type, description, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), 'a4_bill_include_specifications', 'true', 'boolean',
       'Show per-line specifications (RAM/Storage/Color etc.) on A4 receipts',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM settings WHERE `key` = 'a4_bill_include_specifications');

INSERT INTO settings (id, `key`, `value`, type, description, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), 'a4_bill_include_warranty', 'true', 'boolean',
       'Show warranty duration + expiry per bill item on A4 receipts',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM settings WHERE `key` = 'a4_bill_include_warranty');

INSERT INTO settings (id, `key`, `value`, type, description, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), 'a4_bill_include_revisions', 'true', 'boolean',
       'Show revision count + price per bill item on A4 receipts (freelance scope)',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM settings WHERE `key` = 'a4_bill_include_revisions');

INSERT INTO settings (id, `key`, `value`, type, description, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), 'a4_bill_include_terms', 'true', 'boolean',
       'Append Terms & Conditions page after the bill body on A4 receipts',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM settings WHERE `key` = 'a4_bill_include_terms');

INSERT INTO settings (id, `key`, `value`, type, description, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), 'a4_bill_terms_content', '<h2>Terms &amp; Conditions</h2>\n<ol>\n<li>All sales are subject to the warranty period printed on the receipt.</li>\n<li>Goods once sold can only be returned with the original receipt and undamaged packaging.</li>\n<li>Refunds are processed within 7 business days to the original payment method.</li>\n<li>The company is not liable for any data loss on devices returned for repair — please back up before submitting.</li>\n<li>Warranty does not cover physical damage, liquid damage, or unauthorized modifications.</li>\n</ol>\n', 'json',
       'Terms & Conditions body (HTML or Markdown) appended to A4 receipts when a4_bill_include_terms is true',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM settings WHERE `key` = 'a4_bill_terms_content');

INSERT INTO settings (id, `key`, `value`, type, description, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), 'whatsapp_default_format', 'text', 'string',
       'Default WhatsApp receipt format: text (no PDF) or pdf (full A4 receipt)',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM settings WHERE `key` = 'whatsapp_default_format');

INSERT INTO settings (id, `key`, `value`, type, description, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), 'email_default_format', 'pdf', 'string',
       'Default email receipt format: text (HTML only) or pdf (A4 PDF attachment)',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM settings WHERE `key` = 'email_default_format');

INSERT INTO settings (id, `key`, `value`, type, description, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), 'marketing_rate_limit_per_hour', '500', 'integer',
       'Maximum marketing emails per hour (anti-spam throttle; SMTP providers cap at ~500/hr by default)',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM settings WHERE `key` = 'marketing_rate_limit_per_hour');

INSERT INTO settings (id, `key`, `value`, type, description, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), 'marketing_require_double_opt_in', 'true', 'boolean',
       'Require customer email_verified=true before sending any marketing email (anti-spam compliance)',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM settings WHERE `key` = 'marketing_require_double_opt_in');

-- -----------------------------------------------------------------------------
-- 9. New RBAC permissions for v7 features
-- -----------------------------------------------------------------------------
-- These are convention-only (the Permissions matrix in Java hard-codes them);
-- no DB rows needed since v4 RBAC doesn't use a permission table. The Java
-- Permissions class will be extended with: MANAGE_WARRANTIES, MANAGE_REPAIRS,
-- MANAGE_EMAIL_TEMPLATES, MANAGE_MARKETING, SEND_BILL_WHATSAPP, SEND_BILL_PDF.
-- See Permissions.java + SecurityConfig.java for the wired-up allowlists.