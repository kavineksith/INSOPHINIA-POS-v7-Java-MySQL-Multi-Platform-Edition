-- =============================================================================
-- V2 — Canonical settings reconciliation (carried forward from the current version PG migration)
-- =============================================================================
-- The original the current version migration reconciled legacy `vat_enabled` and
-- `sscl_enabled` boolean settings into the canonical `tax_enabled` key consumed
-- by the Spring BillingService. This MySQL migration performs the same
-- reconciliation on first-install MySQL databases. On a fresh schema there are
-- no legacy rows, so the result is a single canonical `tax_enabled=true` row.
-- The DataInitializer also seeds this row defensively, but having it in the
-- migration guarantees the row exists even before the Spring context boots.
-- =============================================================================

INSERT INTO settings (id, `key`, `value`, type, description, created_at, updated_at)
SELECT
    UUID_TO_BIN(UUID()),
    'tax_enabled',
    CASE
        WHEN EXISTS (SELECT 1 FROM settings s WHERE s.`key` = 'vat_enabled'    AND LOWER(s.`value`) = 'false')
         AND EXISTS (SELECT 1 FROM settings s WHERE s.`key` = 'sscl_enabled' AND LOWER(s.`value`) = 'false')
        THEN 'false'
        ELSE 'true'
    END,
    'boolean',
    'Canonical tax enablement migrated from legacy VAT/SSCL flags',
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3)
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM settings WHERE `key` = 'tax_enabled');
