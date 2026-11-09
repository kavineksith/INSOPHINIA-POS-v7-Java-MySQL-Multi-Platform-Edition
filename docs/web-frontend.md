# React web frontend migration notes

The `web/` directory is a browser-based React client that targets the same Spring Boot API as the JavaFX desktop. It replaces the the specification UI runtime for this client while keeping the original INSOPHINIA logo assets and the major POS workflows.

## Architecture

- React + TypeScript + Vite
- Tailwind CSS v4 (Vite plugin) with a custom responsive operations UI layer
- React Context for auth/session state
- Redux Toolkit for checkout cart state
- React Router for route/layout handling
- Recharts for dashboard/report charts
- `html5-qrcode` for browser camera barcode/QR scanning
- Web Serial ESC/POS receipt hook adapted from the reference frontend

## Functional areas

- Login, 2FA challenge, forced password change
- Dashboard and loyalty points total
- Checkout with catalog lookup, keyboard-wedge barcode scanner, camera scan, customer search, checkout and loyalty redemption
- Bill history, server-side pagination/search/date/status filters, bill detail, print, email, WhatsApp share link, return/cancel authorization
- Items, categories, customers, promotions, users CRUD with server-side pagination/search/sorting and clear filters
- Inventory adjustments and low-stock view
- Sales/item reports and CSV export
- Settings, device profiles, backups, logs, active sessions and security events
- Customer display SSE stream with channel pairing

## Pairing the customer display

1. Generate a high-entropy random channel (32 random bytes recommended).
2. Set it in backend `.env` as `POS_DISPLAY_CHANNEL` for one lane, or list allowed lane/display channels in `POS_DISPLAY_CHANNELS`.
3. Restart the backend after changing environment values.
4. In the cashier POS page, paste the channel in the pairing field. In the separate customer-display browser tab/device, sign in with an account that has `view:billing` and paste the same channel.
5. Use HTTPS for remote devices. The pairing channel is a bearer-like capability; don't put it in source control or publicly hosted build artifacts. Browser session storage is not a substitute for a secret-management system.

## Browser hardware differences

- Keyboard-wedge barcode scanners work like a keyboard and usually need no browser API.
- Camera scanning requires permission and a secure context (HTTPS or localhost).
- Direct serial ESC/POS printing uses Web Serial where available (typically Chromium browsers, secure context, and user gesture). Printer baud rates, encoding, cutter commands and drawer pulses are hardware-specific; test on the actual printer.
- Browser print uses the operating system's print dialog and cannot guarantee silent thermal printing.
- WhatsApp opens a `wa.me` share link for the user to confirm. Automated WhatsApp Business messaging requires separate official API credentials/provider integration; no such credentials are bundled.
- USB cash drawer support is limited to the ESC/POS drawer pulse on a compatible connected printer and must be physically tested.

## Validation status

The source was checked for TypeScript/TSX syntax diagnostics and project configuration consistency. A full `npm install`, `npm run typecheck`, and production build could not be completed in the authoring environment because npm registry access failed (`EAI_AGAIN`). Run those commands on a machine with registry access before deployment. Also run the UI against a disposable backend/database and test role permissions, 2FA, checkout, customer display stream, camera permissions, receipt email, printer/cutter/drawer behavior, and WhatsApp share behavior.
