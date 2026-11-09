# INSOPHINIA POS — Disaster Recovery Runbook

**Last updated:** 2026-10-18 · **Document owner:** INSOPHINIA OPS · **Review cadence:** Quarterly

This runbook documents the backup, restore, and disaster-recovery procedure for the INSOPHINIA POS production deployment. It is intended to be executed by an on-call operator during a real incident — every command is copy-pasteable, and the RTO/RPO targets are measurable so you can verify you met them.

## 1. Recovery objectives (RTO / RPO)

| Metric | Target | Definition |
|---|---|---|
| **RPO** (Recovery Point Objective) | **≤ 24 hours** | Maximum acceptable data loss. POS bills, stock movements, and customer loyalty points created in the last 24h can be re-entered manually if a backup older than 24h must be restored. |
| **RTO** (Recovery Time Objective) | **≤ 4 hours** | Maximum acceptable downtime from incident detection to full service restoration. Includes backup download, MySQL restore, Spring Boot restart, and smoke-test verification. |
| **RTO — partial (read-only)** | **≤ 30 minutes** | Time to restore read-only access (e.g., dashboard, reports, bill history) before billing is re-enabled. The master_admin can deliberately keep billing disabled until full verification passes. |
| **RTO — checkout resumed** | **≤ 2 hours** | Time to restore billing + checkout capability. Bounded by the MySQL restore time on the production-grade backup file. |

These targets assume:
- A weekly offsite backup rotation (encrypted .posbak files uploaded to an S3-compatible bucket or rotated to offline USB).
- A daily automated backup at 02:00 local time via the cron endpoint (`POST /api/cron/backups` with `X-Cron-Secret` header).
- The on-call operator has access to: the production `.env` file, the backup encryption key, and the MySQL root credentials.

## 2. Backup inventory

The INSOPHINIA POS backend produces encrypted backup files in `.posbak` format:

- **Envelope:** `INSOBK01` magic header (4 bytes) + 12-byte IV + 16-byte AES-256-GCM auth tag + ciphertext
- **Payload:** gzip-compressed JSON of all allowlisted tables (users, items, bills, bill_items, stock_movements, customers, promotions, categories, settings, system_state, system_logs, audit_logs, security_events, login_sessions, token_blacklist, backups)
- **Excluded:** Auth/session/audit tables (intentionally — restoring a 24h-old session table would invalidate every active login).
- **Retention:** 7 calendar days on disk; daily offsite rotation.
- **Verification:** Each `.posbak` file carries a SHA-256 key derived from `BACKUP_ENCRYPTION_KEY` (env). Verify integrity with `POST /api/backups/preview-file` (admin only) before any restore attempt.

Backup file locations:
- **Local:** `$BACKUP_DIR` (default: `./backups/` in the backend working directory)
- **Offsite:** S3-compatible bucket `pos_backups` (configured via `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY`, `S3_REGION`, `S3_ENDPOINT`)

## 3. Pre-restore safety procedure

**Before any restore**, perform the following safety steps:

1. **Announce the incident** to the on-call channel (Slack #ops-oncall, PagerDuty incident, etc.).
2. **Take a safety backup** of the current (broken) database state. Even if the database is in a degraded state, capturing it lets you recover data entered between the last good backup and the incident:
   ```bash
   # On the production MySQL host:
   mysqldump --single-transaction --routines --triggers \
     -u root -p insophinia_pos > /tmp/safety-snapshot-$(date +%Y%m%d-%H%M%S).sql
   # Or via the Spring Boot API (preferred — produces an encrypted .posbak):
   curl -X POST http://127.0.0.1:8080/api/backups \
     -H "Authorization: Bearer $ADMIN_JWT"
   ```
3. **Notify shop operators** that checkout will be suspended. They can keep ringing up sales on paper receipts; the bills will be re-entered once the system is restored.
4. **Lock the system** (HTTP 423 LOCKED will be returned on every mutating endpoint, so no new bills can be created):
   ```bash
   curl -X POST http://127.0.0.1:8080/api/system/lock \
     -H "Authorization: Bearer $ADMIN_JWT" \
     -H "Content-Type: application/json" \
     -d '{"locked": true, "reason": "DR drill / restore in progress"}'
   ```

## 4. Restore procedure

### 4.1. Locate the latest good backup

```bash
# List available backups via the API (admin only):
curl http://127.0.0.1:8080/api/backups \
  -H "Authorization: Bearer $ADMIN_JWT" | jq '.data'

# Or directly on the production host:
ls -lht $BACKUP_DIR/*.posbak | head -10
```

Pick the most recent `.posbak` whose `preview-file` endpoint reports the expected row counts:

```bash
curl -X POST http://127.0.0.1:8080/api/backups/preview-file \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{"filePath": "/var/backups/insophinia/insophinia-pos-2026-10-17-0200.posbak"}' | jq
```

Expected preview output:
```json
{
  "users": 8,
  "items": 124,
  "bills": 1842,
  "bill_items": 6317,
  "stock_movements": 5210,
  "customers": 312,
  "audit_logs": 8421
}
```

If the row counts are roughly what you expect (within 10% of the latest known good state), proceed. If they look wrong (e.g., `bills: 0`), DO NOT RESTORE — the backup file is corrupt or the decryption key is wrong. Re-verify `BACKUP_ENCRYPTION_KEY` matches the env var that was in effect when the backup was created.

### 4.2. Restore the backup via the Spring Boot API (preferred — transactional)

The backend exposes a transactional restore endpoint that:
- Takes a safety backup before any data is overwritten.
- Wraps the restore in a single DB transaction.
- Replaces the contents of every allowlisted table.
- Returns a preview of post-restore row counts for verification.

```bash
# Restore by backup ID (looks up the file in $BACKUP_DIR):
curl -X POST http://127.0.0.1:8080/api/backups/restore \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{"backupId": "<UUID from step 4.1>"}'

# Restore by file path (e.g., for offsite-restored files):
curl -X POST http://127.0.0.1:8080/api/backups/restore \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{"filePath": "/tmp/insophinia-pos-2026-10-17-0200.posbak"}'
```

The endpoint requires:
- The operator's account has `manage:settings` permission (admin+).
- The operator confirms by typing `RESTORE` in the request body's `confirm` field.

### 4.3. Alternative: restore the MySQL dump directly

If the Spring Boot backend is down and can't be started (e.g., JVM crash, corrupted JAR), restore directly from a raw SQL dump:

```bash
# Stop the backend service (so no new writes interfere with the restore)
sudo systemctl stop insophinia-pos-backend

# Drop and recreate the database (clean slate)
mysql -u root -p -e "DROP DATABASE IF EXISTS insophinia_pos; CREATE DATABASE insophinia_pos CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

# Restore from the SQL dump
mysql -u root -p insophinia_pos < /var/backups/insophinia/safety-snapshot-20261017-0200.sql

# Or: replay all Flyway migrations on the empty DB, then load the dump
mysql -u root -p insophinia_pos < backend/src/main/resources/db/migration/V1__initial_mysql_schema.sql
mysql -u root -p insophinia_pos < backend/src/main/resources/db/migration/V2__canonical_tax_enabled_setting.sql
mysql -u root -p insophinia_pos < safety-snapshot.sql

# Restart the backend
sudo systemctl start insophinia-pos-backend
```

## 5. Post-restore verification

After the restore completes, verify the system is healthy before unlocking it:

1. **Health check** (no auth required):
   ```bash
   curl http://127.0.0.1:8080/api/system/health
   # Expected: {"status":"ok","time":"2026-10-18T..."}
   ```

2. **Smoke test the dashboard** (master_admin login required):
   ```bash
   curl http://127.0.0.1:8080/api/dashboard -H "Authorization: Bearer $ADMIN_JWT"
   # Verify: todaySales > 0, totalItems > 0, lowStockCount is plausible
   ```

3. **Verify the most recent bill** is the one you expect from the backup:
   ```bash
   curl 'http://127.0.0.1:8080/api/billing?page=1&limit=1&sort=createdAt&direction=desc' \
     -H "Authorization: Bearer $ADMIN_JWT"
   # Compare the bill number + customer name against your paper receipts.
   ```

4. **Verify stock levels** are plausible:
   ```bash
   curl http://127.0.0.1:8080/api/inventory/low-stock -H "Authorization: Bearer $ADMIN_JWT"
   ```

5. **Unlock the system** (only after all checks pass):
   ```bash
   curl -X POST http://127.0.0.1:8080/api/system/lock \
     -H "Authorization: Bearer $ADMIN_JWT" \
     -H "Content-Type: application/json" \
     -d '{"locked": false, "reason": ""}'
   ```

6. **Notify shop operators** that checkout can resume. They should ring up the bills they took on paper during the outage.

## 6. Disaster-recovery drill procedure

Run this drill **quarterly** to verify the procedure works end-to-end. The drill exercises:
- A simulated disk failure on the production MySQL volume.
- A restore from the latest offsite backup.
- A measurement of the actual RTO against the 4h target.

### Drill pre-conditions
- A staging environment that mirrors production (same Spring Boot version, same MySQL 8 version, same `.env` secrets).
- A recent offsite backup file (uploaded within the last 24h).
- A test dataset of at least 100 bills + 50 customers.

### Drill script

```bash
# Step 1: Snapshot the staging DB (so we can compare pre/post drill state).
mysqldump --single-transaction insophinia_pos_staging > /tmp/drill-pre-snapshot.sql

# Step 2: Simulate disk failure — drop the staging database.
mysql -u root -p -e "DROP DATABASE IF EXISTS insophinia_pos_staging;"

# Step 3: Start the clock.
DRILL_START=$(date +%s)

# Step 4: Restore the latest offsite backup.
curl -X POST http://staging:8080/api/backups/restore \
  -H "Authorization: Bearer $DRILL_JWT" \
  -H "Content-Type: application/json" \
  -d '{"backupId": "<latest>", "confirm": "RESTORE"}'

# Step 5: Run the post-restore verification suite (see section 5).
# Step 6: Stop the clock.
DRILL_END=$(date +%s)
DRILL_DURATION=$(( (DRILL_END - DRILL_START) / 60 ))
echo "RTO: ${DRILL_DURATION} minutes (target: ≤ 240 minutes)"

# Step 7: Verify row counts match the pre-snapshot (within tolerance).
mysql -u root -p insophinia_pos_staging -e "SELECT COUNT(*) FROM bills;"
# Compare against the pre-snapshot count.

# Step 8: Document the drill outcome in the runbook's history table below.
```

### Drill pass criteria
- **RTO ≤ 240 minutes** (target: 4 hours).
- All post-restore verification checks pass.
- Row counts match the pre-snapshot within 1% tolerance (some new rows may have been created between snapshot and restore).
- No operator-reported issues during the simulated outage window.

If any criterion fails, file an incident and re-run the drill after the fix is verified.

## 7. Drill history

| Date | Operator | RTO measured | Pass/Fail | Notes |
|---|---|---|---|---|
| _To be filled in by the first quarterly drill_ | — | — | — | — |

## 8. Key rotation

The backup encryption key (`BACKUP_ENCRYPTION_KEY`) is the single most sensitive secret in the system. Rotate it annually:

1. Generate a new 32-byte key: `openssl rand -base64 32`
2. Set the new value in the production `.env` file and restart the backend.
3. All NEW backups created after the restart will use the new key.
4. Re-encrypt or destroy old backups encrypted with the previous key. Old `.posbak` files cannot be restored once the previous key is destroyed — keep one offsite copy of the previous key in a sealed envelope for the duration of the retention window (7 days).

The TOTP encryption key (`TOTP_ENCRYPTION_KEY`) follows the same rotation procedure. Note that rotating this key invalidates all stored 2FA secrets — affected users must re-enroll 2FA on next login.

The JWT signing secret (`JWT_SECRET`) rotation invalidates ALL active sessions immediately (every user is logged out). Schedule this during a low-traffic window.

## 9. Contact list

| Role | Name | Contact | Notes |
|---|---|---|---|
| On-call operator | _fill in_ | _phone / pager_ | First responder to any DR incident |
| DBA | _fill in_ | _phone / pager_ | For raw SQL restore (section 4.3) |
| Backend engineer | _fill in_ | _phone / pager_ | For Spring Boot backend crashes |
| Shop manager | _fill in_ | _phone_ | To notify paper-receipt fallback procedure |
