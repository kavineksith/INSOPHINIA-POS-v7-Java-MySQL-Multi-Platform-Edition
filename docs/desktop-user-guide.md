# INSOPHINIA POS Desktop — User Guide

## Desktop workspaces

The JavaFX desktop includes permission-aware workspaces for checkout, dashboard, inventory, bill history, items, categories, promotions, customers, users, reports, settings, security events, logs, backups, device profiles, and hardware diagnostics. Tabs are shown according to the authenticated user's permission list. The Spring Boot API remains the source of truth and independently checks authorization on each write.

## Catalog CRUD

- **Items:** create, edit, and delete items. Item forms require a valid category UUID; copy it from the Categories workspace. PLU, selling price, and category are required.
- **Categories:** create, edit, and delete categories.
- **Promotions:** create, edit, and delete promotions. Dates must be ISO-8601 instants such as `2026-10-15T00:00:00Z`; discount type is `percentage` or `fixed`. Related item/category UUIDs are optional.
- **Customers:** create, edit, and delete customer records.
- **Users:** create users with a temporary password, update identity/role/active state, and manage accounts. User creation and editing require the server's `manage:users` authority. Use the server-side user management endpoint for password resets.

A delete can be rejected by the server when a record is referenced by another record. Do not bypass such constraints directly in SQL.

## Inventory

Select an item, then use Stock in, Stock out, Record damage, or Record lost. Each movement requires a positive quantity and is validated by the server. Cashiers without `manage:inventory` can view inventory but cannot submit adjustments.

## Checkout and customer display

Open **Point of Sale** and scan/type the barcode or PLU into the input field. The customer display is a separate window opened with **Open customer display**. It mirrors the local checkout cart and total as items are added/removed and resets when checkout completes. Place the customer display window on a second monitor if available.

The display is currently local to the checkout desktop process. SSE updates refresh operational screens across connected terminals; they do not mirror a different terminal's cart into this terminal's display.

## Bills, receipts, and email

The Bills workspace supports history, PDF export, native print dialog, configured thermal printer output, email receipt, return, and supervisor-authorized cancellation. Cancellation and return are server-authorized; a UI control is not a security boundary.

- **PDF:** select a bill → Save PDF → choose the destination.
- **Native printer:** select a bill → Print → choose an OS printer.
- **Thermal printer:** configure the printer in the operating system and set `POS_PRINTER_NAME` if the desired device is not the default. Test it from Hardware.
- **Email:** configure SMTP in the local setup environment file, then use Settings → Send test email. A receipt email requires a valid customer/recipient email and an enabled SMTP configuration.

WhatsApp and camera-based scanning are intentionally not included in this edition. Barcode input and optional serial/HID scanner devices are supported separately from camera scanning.

## Dashboard and reports

The dashboard displays today's sales/bills, month sales, low-stock count, customer/item totals, a seven-day revenue trend, and top-selling items. Reports can be filtered by date and exported to CSV. The backend currently provides sales-by-day and item-performance reports; this is not yet a full consistency implementation of every metric from the the API dashboard.

## User and permission safety

If an action is disabled or returns HTTP 403, ask an administrator to grant the appropriate server-side permission. Do not share credentials or use the master account for daily cashier work. Use separate cashier/supervisor/admin accounts and enable two-factor authentication where supported.


## Cross-terminal customer display
Set the same random shared channel on the backend and each desktop that participates in the display pair. Configure `POS_DISPLAY_CHANNEL` to a random value of at least 16 characters in the backend environment and desktop process environment. Configure `POS_TERMINAL_ID` uniquely per checkout terminal. Use a different random channel per checkout lane/display pair; for multiple pairs, list every allowed channel comma-separated in backend `POS_DISPLAY_CHANNELS`. Do not share one channel between independent checkout lanes. On a separate display-only terminal, set `POS_CUSTOMER_DISPLAY_ONLY=true` so its local empty cart does not overwrite the paired cashier display. Only authenticated users with billing view permission can subscribe; publishing requires billing management permission. Cart snapshots are sent only to the paired channel through the `X-Display-Channel` header, are not added to the general sync history, and are not persisted. Use HTTPS for remote terminals. If the channel is unset, the current desktop's local display still works but remote cart streaming is disabled.
