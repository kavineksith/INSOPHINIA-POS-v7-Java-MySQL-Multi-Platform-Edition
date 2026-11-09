# Testing Guide

## Backend

From `backend/`:

```bash
mvn test
```

The backend suite contains unit and Spring integration tests. For MySQL-specific migration validation, use a disposable database and the Docker Compose configuration; do not test migrations against the live shop database first.

## Desktop

From `desktop/`:

```bash
mvn test
mvn javafx:run
```

Desktop unit tests cover receipt-line rendering and PDF output. Manually verify on each target OS:

1. Login, forced password change, and 2FA flow.
2. CRUD create/update/delete for categories, items, customers, promotions, and users with both authorized and unauthorized accounts.
3. Inventory stock-in/out/damage/lost; confirm movement history and low-stock totals.
4. Checkout with cash/change, receipt PDF export, native print dialog, thermal printer, and email receipt.
5. Open customer display on a second monitor and verify it tracks cart changes and checkout completion.
6. Dashboard KPIs, date-filtered reports, CSV export, and SSE refresh after another terminal updates data.
7. Local setup wizard with a disposable database, restart persistence, and backup/restore rehearsal.
8. Confirm MySQL/API host ports bind to `127.0.0.1` by default.

No test result should be reported as passing unless the command was actually run and its exit status checked.
