-- =============================================================================
-- INSOPHINIA POS — Initial MySQL schema (production-ready, initial)
-- =============================================================================
-- Complete MySQL schema for the INSOPHINIA POS system.
-- MySQL schema definition.
-- optimized for InnoDB + utf8mb4
 Per the project's release-blocker notice (MYSQL_MIGRATION_PLAN.md),
-- they must NOT be used against a MySQL database.
--
-- Schema design choices:
--   * UUID primary keys — `BINARY(16) NOT NULL`. Hibernate 6's
--     `GenerationType.UUID` generates identifiers in application memory and
--     persists them as raw 16-byte values, which is the most efficient and
--     index-friendly representation on InnoDB (clustered PK).
--   * Timestamps — `DATETIME(3)` (millisecond precision) with explicit
--     `CURRENT_TIMESTAMP(3)` defaults, matching Hibernate's preferred
--     `TIMESTAMP` JDBC type for MySQL.
--   * Enums — `VARCHAR(20)` literals with `CHECK` constraints (Spring already
--     persists enums as strings via `@Enumerated(EnumType.STRING)`).
--   * Booleans — `TINYINT(1)` (MySQL's idiomatic boolean column; Hibernate
--     maps `boolean` to this type automatically).
--   * Stock quantities — `DECIMAL(10,3)` to preserve fractional-unit stock
--     (e.g. weighed goods) introduced by the current version of the database schema.
--   * Real-time sync is handled in-memory by the Spring SyncHub (no DB triggers needed)

--   * No partial / expression indexes — MySQL does not support
--     `WHERE deleted_at IS NULL` partial indexes or `lower(email)` expression
--     indexes (functional indexes on MySQL 8 require generated columns).
--     We use regular indexes on `created_at`, `phone`, `email` (case-insensitive
--     lookups use `COLLATE utf8mb4_general_ci` which is the column default).
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- Table: users
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id                       BINARY(16)       NOT NULL,
    username                 VARCHAR(100)     NOT NULL,
    email                    VARCHAR(255)     NOT NULL,
    password                 VARCHAR(255)     NOT NULL,
    role                     VARCHAR(30)      NOT NULL DEFAULT 'cashier',
    first_name               VARCHAR(100)     NOT NULL,
    last_name                VARCHAR(100)     NOT NULL,
    is_active                TINYINT(1)       NOT NULL DEFAULT 1,
    password_changed_at      DATETIME(3)      NULL,
    password_expires_at      DATETIME(3)      NULL,
    must_change_password     TINYINT(1)       NOT NULL DEFAULT 1,
    failed_login_attempts    INT              NOT NULL DEFAULT 0,
    locked_until             DATETIME(3)      NULL,
    two_factor_secret        MEDIUMTEXT       NULL,
    two_factor_enabled       TINYINT(1)       NOT NULL DEFAULT 0,
    two_factor_backup_codes  MEDIUMTEXT       NULL,
    created_at               DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at               DATETIME(3)      NULL,
    CONSTRAINT users_pkey PRIMARY KEY (id),
    CONSTRAINT users_role_chk CHECK (role IN ('admin','cashier','supervisor','master_admin')),
    CONSTRAINT users_username_unique UNIQUE (username),
    CONSTRAINT users_email_unique    UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_users_username    ON users (username);
CREATE INDEX idx_users_email       ON users (email);
CREATE INDEX idx_users_role        ON users (role);
CREATE INDEX idx_users_deleted_at  ON users (deleted_at);

-- -----------------------------------------------------------------------------
-- Table: categories
-- -----------------------------------------------------------------------------
CREATE TABLE categories (
    id           BINARY(16)   NOT NULL,
    name         VARCHAR(255) NOT NULL,
    description  MEDIUMTEXT   NULL,
    is_active    TINYINT(1)   NOT NULL DEFAULT 1,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at   DATETIME(3)  NULL,
    CONSTRAINT categories_pkey PRIMARY KEY (id),
    CONSTRAINT categories_name_unique UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_categories_name       ON categories (name);
CREATE INDEX idx_categories_deleted_at  ON categories (deleted_at);

-- -----------------------------------------------------------------------------
-- Table: items
-- -----------------------------------------------------------------------------
CREATE TABLE items (
    id                    BINARY(16)      NOT NULL,
    plu_code              VARCHAR(50)     NOT NULL,
    name                  VARCHAR(255)    NOT NULL,
    description           MEDIUMTEXT      NULL,
    category_id           BINARY(16)      NOT NULL,
    barcode               VARCHAR(100)    NULL,
    qr_code               VARCHAR(100)    NULL,
    price                 DECIMAL(10,2)   NOT NULL DEFAULT 0.00,
    cost_price            DECIMAL(10,2)   NOT NULL DEFAULT 0.00,
    unit                  VARCHAR(50)     NOT NULL DEFAULT 'pcs',
    stock_quantity        DECIMAL(10,3)   NOT NULL DEFAULT 0.000,
    total_quantity        DECIMAL(10,3)   NOT NULL DEFAULT 0.000,
    sell_quantity         DECIMAL(10,3)   NOT NULL DEFAULT 0.000,
    return_quantity       DECIMAL(10,3)   NOT NULL DEFAULT 0.000,
    damage_quantity       DECIMAL(10,3)   NOT NULL DEFAULT 0.000,
    lost_quantity         DECIMAL(10,3)   NOT NULL DEFAULT 0.000,
    low_stock_threshold   DECIMAL(10,3)   NOT NULL DEFAULT 10.000,
    version               INT             NOT NULL DEFAULT 0,
    is_active             TINYINT(1)      NOT NULL DEFAULT 1,
    has_discount         TINYINT(1)      NOT NULL DEFAULT 0,
    discount_percentage   DECIMAL(5,2)    NOT NULL DEFAULT 0.00,
    created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at            DATETIME(3)     NULL,
    CONSTRAINT items_pkey PRIMARY KEY (id),
    CONSTRAINT items_plu_code_unique   UNIQUE (plu_code),
    CONSTRAINT items_barcode_unique   UNIQUE (barcode),
    CONSTRAINT items_qr_code_unique    UNIQUE (qr_code),
    CONSTRAINT items_category_fk      FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_items_plu_code        ON items (plu_code);
CREATE INDEX idx_items_barcode         ON items (barcode);
CREATE INDEX idx_items_category_id     ON items (category_id);
CREATE INDEX idx_items_deleted_at       ON items (deleted_at);
CREATE INDEX idx_items_category_active_deleted ON items (category_id, is_active, deleted_at);
CREATE INDEX idx_items_active_stock          ON items (is_active, stock_quantity);

-- -----------------------------------------------------------------------------
-- Table: promotions
-- -----------------------------------------------------------------------------
CREATE TABLE promotions (
    id               BINARY(16)    NOT NULL,
    item_id          BINARY(16)     NULL,
    category_id      BINARY(16)     NULL,
    name             VARCHAR(255)   NOT NULL,
    description      MEDIUMTEXT     NULL,
    discount_type    VARCHAR(20)    NOT NULL DEFAULT 'percentage',
    discount_value  DECIMAL(10,2)  NOT NULL,
    min_item_count   INT            NOT NULL DEFAULT 1,
    start_date      DATETIME(3)     NOT NULL,
    end_date        DATETIME(3)     NOT NULL,
    is_active        TINYINT(1)     NOT NULL DEFAULT 1,
    created_at       DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at       DATETIME(3)    NULL,
    CONSTRAINT promotions_pkey PRIMARY KEY (id),
    CONSTRAINT promotions_discount_type_chk CHECK (discount_type IN ('percentage','fixed')),
    CONSTRAINT promotions_min_item_count_chk CHECK (min_item_count >= 1),
    CONSTRAINT promotions_item_fk     FOREIGN KEY (item_id)     REFERENCES items (id)       ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT promotions_category_fk  FOREIGN KEY (category_id) REFERENCES categories (id)  ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_promotions_item_id           ON promotions (item_id);
CREATE INDEX idx_promotions_category_id        ON promotions (category_id);
CREATE INDEX idx_promotions_start_end          ON promotions (start_date, end_date);
CREATE INDEX idx_promotions_deleted_at         ON promotions (deleted_at);
CREATE INDEX idx_promotions_active_dates       ON promotions (is_active, start_date, end_date);

-- -----------------------------------------------------------------------------
-- Table: customers
-- -----------------------------------------------------------------------------
CREATE TABLE customers (
    id               BINARY(16)    NOT NULL,
    name             VARCHAR(255)   NOT NULL,
    email            VARCHAR(255)   NULL,
    phone            VARCHAR(20)    NULL,
    address          MEDIUMTEXT     NULL,
    loyalty_points   INT            NOT NULL DEFAULT 0,
    -- Email verification lifecycle (port of the current version email-verification feature)
    email_verified                TINYINT(1)  NOT NULL DEFAULT 0,
    email_verification_token      VARCHAR(128) NULL,
    email_verification_expires_at DATETIME(3)  NULL,
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at        DATETIME(3)   NULL,
    CONSTRAINT customers_pkey PRIMARY KEY (id),
    CONSTRAINT customers_email_unique UNIQUE (email),
    CONSTRAINT customers_phone_unique UNIQUE (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_customers_email        ON customers (email);
CREATE INDEX idx_customers_phone        ON customers (phone);
CREATE INDEX idx_customers_deleted_at   ON customers (deleted_at);
CREATE INDEX idx_customers_created_at   ON customers (created_at);
CREATE INDEX idx_customers_verification_token ON customers (email_verification_token);

-- -----------------------------------------------------------------------------
-- Table: bills
-- -----------------------------------------------------------------------------
CREATE TABLE bills (
    id                       BINARY(16)    NOT NULL,
    bill_number              VARCHAR(50)    NOT NULL,
    customer_id              BINARY(16)     NULL,
    customer_name            VARCHAR(255)   NOT NULL DEFAULT 'Customer',
    customer_email           VARCHAR(255)   NULL,
    staff_id                 BINARY(16)     NOT NULL,
    staff_name               VARCHAR(255)   NOT NULL,
    start_time               DATETIME(3)    NOT NULL,
    end_time                 DATETIME(3)    NULL,
    subtotal                 DECIMAL(10,2)  NOT NULL DEFAULT 0.00,
    total_discount           DECIMAL(10,2)  NOT NULL DEFAULT 0.00,
    vat_amount               DECIMAL(10,2)  NOT NULL DEFAULT 0.00,
    sscl_amount              DECIMAL(10,2)  NOT NULL DEFAULT 0.00,
    tax_total                DECIMAL(10,2)  NOT NULL DEFAULT 0.00,
    total_amount             DECIMAL(10,2)  NOT NULL DEFAULT 0.00,
    paid_amount              DECIMAL(10,2)  NOT NULL DEFAULT 0.00,
    balance                  DECIMAL(10,2)  NOT NULL DEFAULT 0.00,
    status                   VARCHAR(20)    NOT NULL DEFAULT 'pending',
    return_reason            MEDIUMTEXT     NULL,
    return_authorized_by     BINARY(16)     NULL,
    return_authorized_at     DATETIME(3)    NULL,
    remarks                  MEDIUMTEXT     NULL,
    is_printed               TINYINT(1)     NOT NULL DEFAULT 0,
    is_email_sent            TINYINT(1)     NOT NULL DEFAULT 0,
    earned_points            INT            NOT NULL DEFAULT 0,
    points_redeemed          INT            NOT NULL DEFAULT 0,
    points_value             DECIMAL(10,2)  NOT NULL DEFAULT 0.00,
    points_authorized_by     VARCHAR(255)   NULL,
    points_authorized_at     DATETIME(3)    NULL,
    total_points_after       INT            NOT NULL DEFAULT 0,
    created_at               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at               DATETIME(3)    NULL,
    CONSTRAINT bills_pkey PRIMARY KEY (id),
    CONSTRAINT bills_bill_number_unique UNIQUE (bill_number),
    CONSTRAINT bills_status_chk CHECK (status IN ('pending','completed','returned','cancelled')),
    CONSTRAINT bills_customer_fk       FOREIGN KEY (customer_id)         REFERENCES customers (id) ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT bills_staff_fk          FOREIGN KEY (staff_id)            REFERENCES users (id)     ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT bills_return_auth_fk    FOREIGN KEY (return_authorized_by) REFERENCES users (id)     ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_bills_bill_number       ON bills (bill_number);
CREATE INDEX idx_bills_customer_id       ON bills (customer_id);
CREATE INDEX idx_bills_staff_id          ON bills (staff_id);
CREATE INDEX idx_bills_status            ON bills (status);
CREATE INDEX idx_bills_created_at        ON bills (created_at);
CREATE INDEX idx_bills_deleted_at         ON bills (deleted_at);
CREATE INDEX idx_bills_staff_created      ON bills (staff_id, created_at);
CREATE INDEX idx_bills_status_created     ON bills (status, created_at);

-- -----------------------------------------------------------------------------
-- Table: bill_items
-- -----------------------------------------------------------------------------
CREATE TABLE bill_items (
    id                    BINARY(16)    NOT NULL,
    bill_id               BINARY(16)     NOT NULL,
    item_id               BINARY(16)     NOT NULL,
    plu_code              VARCHAR(50)    NOT NULL,
    item_name             VARCHAR(255)   NOT NULL,
    quantity              DECIMAL(10,3)  NOT NULL DEFAULT 1.000,
    unit                  VARCHAR(50)    NOT NULL DEFAULT 'pcs',
    unit_price            DECIMAL(10,2)  NOT NULL,
    actual_price          DECIMAL(10,2)  NOT NULL,
    discount              DECIMAL(10,2)  NOT NULL DEFAULT 0.00,
    discount_percentage   DECIMAL(5,2)   NOT NULL DEFAULT 0.00,
    subtotal              DECIMAL(10,2)  NOT NULL,
    created_at            DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at            DATETIME(3)    NULL,
    CONSTRAINT bill_items_pkey PRIMARY KEY (id),
    CONSTRAINT bill_items_bill_fk  FOREIGN KEY (bill_id) REFERENCES bills (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT bill_items_item_fk FOREIGN KEY (item_id) REFERENCES items (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_bill_items_bill_id   ON bill_items (bill_id);
CREATE INDEX idx_bill_items_item_id   ON bill_items (item_id);

-- -----------------------------------------------------------------------------
-- Table: stock_movements
-- -----------------------------------------------------------------------------
CREATE TABLE stock_movements (
    id               BINARY(16)    NOT NULL,
    item_id          BINARY(16)     NOT NULL,
    movement_type    VARCHAR(20)    NOT NULL,
    quantity         DECIMAL(10,3)  NOT NULL,
    reference_type  VARCHAR(20)    NULL,
    reference_id    BINARY(16)     NULL,
    notes            MEDIUMTEXT    NULL,
    movement_date    DATETIME(3)    NOT NULL,
    created_by       BINARY(16)     NOT NULL,
    created_at       DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at       DATETIME(3)    NULL,
    CONSTRAINT stock_movements_pkey PRIMARY KEY (id),
    CONSTRAINT stock_movements_type_chk        CHECK (movement_type IN ('in','out','return_item','damage','lost')),
    CONSTRAINT stock_movements_reference_chk   CHECK (reference_type IS NULL OR reference_type IN ('bill','manual','adjustment')),
    CONSTRAINT stock_movements_item_fk     FOREIGN KEY (item_id)    REFERENCES items (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT stock_movements_creator_fk  FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_stock_movements_item_id          ON stock_movements (item_id);
CREATE INDEX idx_stock_movements_movement_type     ON stock_movements (movement_type);
CREATE INDEX idx_stock_movements_movement_date     ON stock_movements (movement_date);
CREATE INDEX idx_stock_movements_item_date         ON stock_movements (item_id, movement_date);
CREATE INDEX idx_stock_movements_reference         ON stock_movements (reference_type, reference_id);

-- -----------------------------------------------------------------------------
-- Table: settings
-- -----------------------------------------------------------------------------
CREATE TABLE settings (
    id           BINARY(16)    NOT NULL,
    `key`        VARCHAR(100)   NOT NULL,
    `value`      MEDIUMTEXT     NULL,
    type         VARCHAR(20)    NOT NULL DEFAULT 'string',
    description  MEDIUMTEXT     NULL,
    created_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at   DATETIME(3)    NULL,
    CONSTRAINT settings_pkey PRIMARY KEY (id),
    CONSTRAINT settings_key_unique UNIQUE (`key`),
    CONSTRAINT settings_type_chk CHECK (type IN ('string','integer','boolean','bool','json'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_settings_key ON settings (`key`);

-- -----------------------------------------------------------------------------
-- Table: backups
-- -----------------------------------------------------------------------------
CREATE TABLE backups (
    id           BINARY(16)    NOT NULL,
    filename     VARCHAR(255)   NOT NULL,
    file_path    VARCHAR(512)   NOT NULL,
    file_size    BIGINT         NOT NULL,
    backup_type  VARCHAR(20)    NOT NULL DEFAULT 'manual',
    created_by   BINARY(16)     NULL,
    created_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at   DATETIME(3)    NULL,
    CONSTRAINT backups_pkey PRIMARY KEY (id),
    CONSTRAINT backups_type_chk CHECK (backup_type IN ('automatic','manual')),
    CONSTRAINT backups_creator_fk FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_backups_backup_type  ON backups (backup_type);
CREATE INDEX idx_backups_created_at   ON backups (created_at);

-- -----------------------------------------------------------------------------
-- Table: login_sessions
-- -----------------------------------------------------------------------------
CREATE TABLE login_sessions (
    id                BINARY(16)    NOT NULL,
    user_id           BINARY(16)     NOT NULL,
    session_token     VARCHAR(255)   NOT NULL,
    ip_address        VARCHAR(45)    NOT NULL,
    user_agent        MEDIUMTEXT     NOT NULL,
    browser           VARCHAR(100)   NOT NULL DEFAULT 'Unknown',
    os                VARCHAR(100)   NOT NULL DEFAULT 'Unknown',
    device            VARCHAR(100)   NOT NULL DEFAULT 'Unknown',
    city              VARCHAR(100)   NOT NULL DEFAULT 'Unknown',
    country           VARCHAR(100)   NOT NULL DEFAULT 'Unknown',
    region            VARCHAR(100)   NOT NULL DEFAULT 'Unknown',
    created_at        DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_activity_at  DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at        DATETIME(3)    NOT NULL,
    is_active         TINYINT(1)     NOT NULL DEFAULT 1,
    revoked_at        DATETIME(3)    NULL,
    revoke_reason     MEDIUMTEXT    NULL,
    updated_at        DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at        DATETIME(3)    NULL,
    CONSTRAINT login_sessions_pkey PRIMARY KEY (id),
    CONSTRAINT login_sessions_token_unique UNIQUE (session_token),
    CONSTRAINT login_sessions_user_fk FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_login_sessions_user_active  ON login_sessions (user_id, is_active);
CREATE INDEX idx_login_sessions_expires_at   ON login_sessions (expires_at);

-- -----------------------------------------------------------------------------
-- Table: token_blacklist
-- -----------------------------------------------------------------------------
CREATE TABLE token_blacklist (
    id           BINARY(16)    NOT NULL,
    token_hash   VARCHAR(255)   NOT NULL,
    user_id      BINARY(16)     NOT NULL,
    reason       VARCHAR(255)   NOT NULL DEFAULT 'logout',
    expires_at   DATETIME(3)    NOT NULL,
    created_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at   DATETIME(3)    NULL,
    CONSTRAINT token_blacklist_pkey PRIMARY KEY (id),
    CONSTRAINT token_blacklist_token_unique UNIQUE (token_hash),
    CONSTRAINT token_blacklist_user_fk FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_token_blacklist_user_id    ON token_blacklist (user_id);
CREATE INDEX idx_token_blacklist_expires_at ON token_blacklist (expires_at);

-- -----------------------------------------------------------------------------
-- Table: security_events
-- -----------------------------------------------------------------------------
CREATE TABLE security_events (
    id           BINARY(16)    NOT NULL,
    user_id      BINARY(16)     NULL,
    event_type   VARCHAR(100)   NOT NULL,
    ip_address   VARCHAR(45)    NOT NULL,
    user_agent   MEDIUMTEXT     NOT NULL,
    details      MEDIUMTEXT    NULL,
    severity     VARCHAR(20)    NOT NULL DEFAULT 'low',
    created_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at   DATETIME(3)    NULL,
    CONSTRAINT security_events_pkey PRIMARY KEY (id),
    CONSTRAINT security_events_user_fk FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_security_events_user_type  ON security_events (user_id, event_type);
CREATE INDEX idx_security_events_created_at ON security_events (created_at);

-- -----------------------------------------------------------------------------
-- Table: audit_logs
-- -----------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id           BINARY(16)    NOT NULL,
    user_id      BINARY(16)     NULL,
    action       VARCHAR(100)   NOT NULL,
    entity       VARCHAR(100)   NOT NULL,
    entity_id    VARCHAR(100)   NULL,
    details      MEDIUMTEXT    NULL,
    ip_address   VARCHAR(45)    NULL,
    user_agent   MEDIUMTEXT    NULL,
    created_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at   DATETIME(3)    NULL,
    CONSTRAINT audit_logs_pkey PRIMARY KEY (id),
    CONSTRAINT audit_logs_user_fk FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_audit_logs_user_id    ON audit_logs (user_id);
CREATE INDEX idx_audit_logs_action     ON audit_logs (action);
CREATE INDEX idx_audit_logs_entity     ON audit_logs (entity);
CREATE INDEX idx_audit_logs_created_at ON audit_logs (created_at);

-- -----------------------------------------------------------------------------
-- Table: system_logs
-- -----------------------------------------------------------------------------
CREATE TABLE system_logs (
    id           BINARY(16)    NOT NULL,
    level        VARCHAR(20)    NOT NULL,
    category     VARCHAR(100)   NOT NULL,
    action       VARCHAR(255)   NOT NULL,
    message      MEDIUMTEXT    NOT NULL,
    metadata     MEDIUMTEXT    NULL,
    user_id      BINARY(16)     NULL,
    ip_address   VARCHAR(200)   NULL,
    created_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at   DATETIME(3)    NULL,
    CONSTRAINT system_logs_pkey PRIMARY KEY (id),
    CONSTRAINT system_logs_user_fk FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_system_logs_level         ON system_logs (level);
CREATE INDEX idx_system_logs_category      ON system_logs (category);
CREATE INDEX idx_system_logs_created_at    ON system_logs (created_at);

-- -----------------------------------------------------------------------------
-- Table: system_state  (deadman switch — singleton row seeded by DataInitializer)
-- -----------------------------------------------------------------------------
CREATE TABLE system_state (
    id              BINARY(16)    NOT NULL,
    is_locked       TINYINT(1)    NOT NULL DEFAULT 0,
    lock_reason     MEDIUMTEXT   NULL,
    locked_by       BINARY(16)    NULL,
    locked_at       DATETIME(3)   NULL,
    last_checkin_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deadman_days    INT           NOT NULL DEFAULT 72,
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at      DATETIME(3)   NULL,
    CONSTRAINT system_state_pkey PRIMARY KEY (id),
    CONSTRAINT system_state_locked_by_fk FOREIGN KEY (locked_by) REFERENCES users (id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- -----------------------------------------------------------------------------
-- Table: device_profiles
-- -----------------------------------------------------------------------------
CREATE TABLE device_profiles (
    id                BINARY(16)    NOT NULL,
    device_identifier VARCHAR(255)   NOT NULL,
    name              VARCHAR(100)   NOT NULL DEFAULT 'New Device',
    default_printer   VARCHAR(50)    NOT NULL DEFAULT 'thermal',
    default_scanner   VARCHAR(50)    NOT NULL DEFAULT 'keyboard',
    is_active         TINYINT(1)    NOT NULL DEFAULT 1,
    created_at        DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at        DATETIME(3)    NULL,
    CONSTRAINT device_profiles_pkey PRIMARY KEY (id),
    CONSTRAINT device_profiles_identifier_unique UNIQUE (device_identifier)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_device_profiles_identifier ON device_profiles (device_identifier);
