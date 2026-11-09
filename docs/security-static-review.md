# Static security review — 2026-10-15

## Scope and confidence
Reviewed the Spring Boot API, JavaFX client integration, authentication/security configuration, request DTOs, sync hub, query paths, and backup path handling at source level. This is **not a full penetration test**, does not certify the app as secure, and does not include a live deployment scan. Build/runtime verification remains outstanding in the current environment.

## Improvements applied in this pass
- Added a per-IP API request guard (300 requests/minute) in addition to the existing login-specific rate limit. It is in-memory and is not sufficient as the only production DDoS control.
- Restricted credentialed CORS; this bearer-token API does not require cross-origin cookies. CORS origins remain explicit and must be configured for the actual web deployment.
- Added request DTO validation for customer email, user email/password lengths, promotion quantity threshold, email receipt address, customer display cart payload, and selected text lengths.
- Removed trust in caller-supplied `X-Forwarded-For` for audit IP addresses by default. Configure trusted proxy handling explicitly if deployed behind a reverse proxy.
- Customer display events use a separate configured channel and are excluded from the general sync event history. Remote cart publishing requires `manage:billing`; display subscription requires `view:billing`.
- Added safe sort-field allow-lists, bounded page sizes, database-side search/date filters, two-day default windows for bill/customer history, and indexes for common date/lookup paths.
- Added security test checklist covering BOLA/IDOR, BFLA, auth/session, injection/input boundaries, business logic, data leakage, dependency CVEs and deployment configuration.

## Risks / checks still required
- Run dependency CVE scanning and OWASP ZAP against a non-production instance; inspect the the API reference application separately if it will remain deployed.
- Test every controller permission using every role. A static annotation scan cannot prove object-level authorization.
- The API rate limiter and SSE hub are process-local. Multi-instance deployments require shared rate-limit/session/event infrastructure.
- The customer-display channel must be random (at least 16 characters), configured outside source control, and unique per checkout lane/display pair. Use HTTPS on remote networks.
- The desktop app still trusts the API response for display values, while the server remains authoritative for prices, inventory, promotions, and checkout. Never use display payload values to calculate a bill.
- Review secret rotation, TLS termination, DB role privileges, backup encryption key custody, SMTP secrets, production logging, request-size limits, and operational alerting before production.
- CSP contains inline allowances to support the bundled API documentation UI; if Swagger UI is not needed in production, disable it or use a stricter production-only CSP.
- CSRF is disabled for stateless bearer-token API authentication. Do not add cookie authentication without CSRF protections.


## OWASP API / web review coverage
- **Broken Object Level Authorization (BOLA/IDOR):** resource UUIDs and ownership boundaries need adversarial role-to-role tests; `@PreAuthorize` alone does not prove record-level access.
- **Broken Authentication:** JWT expiry/revocation, active session checks, login throttling, password policy and 2FA require runtime tests.
- **Broken Object Property Level Authorization:** verify sensitive response fields and mass assignment on user/settings/customer DTOs; request DTOs should never accept role/permissions unless the actor is authorized.
- **Unrestricted Resource Consumption:** bounded page sizes and per-IP request limiting added; test body sizes, exports, SSE connections, backup sizes and expensive report filters.
- **Broken Function Level Authorization:** exercise every endpoint with each role, especially settings, backups, logs, exports, cancellation, return and user management.
- **Unrestricted Access to Sensitive Business Flows:** test checkout replay, race conditions, loyalty redemption, return/cancel abuse and promotion rules.
- **SSRF:** no SSRF sink was identified in the small static scan, but verify any future URL-fetching/integration feature.
- **Security Misconfiguration:** validate production CORS, TLS, headers, Swagger exposure, DB/network bindings, secrets, proxy trust and error responses.
- **Improper Inventory Management:** keep an API route inventory and disable unused/debug endpoints; document any the API endpoints still deployed.
- **Unsafe Consumption of APIs:** validate/limit all external SMTP, printer, backup and future provider data; never trust client-calculated bill totals.
- **Web-specific risks:** if the the API reference web app remains deployed, separately test XSS, CSRF/token storage, open redirects, cache leakage, CSP, file upload/download paths and browser-specific CORS behavior. The JavaFX desktop client itself is not a browser-origin web app, but it still needs secure API transport and input handling.
