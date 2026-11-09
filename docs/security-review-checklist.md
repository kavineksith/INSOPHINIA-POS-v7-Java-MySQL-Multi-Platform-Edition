# Spring Boot API & web security review checklist

This is a code-review aid, **not a claim of security certification**. The current repository has not been runtime-penetration-tested in this environment. Re-run this checklist for every release and deployment.

## Controls present in code (verify at runtime)
- Stateless JWT authentication; server-side permission checks via `@PreAuthorize`.
- Password hashing with BCrypt cost 12; 2FA/TOTP support and token blacklist/session support.
- DTO validation via Jakarta Bean Validation; use `@Valid` at every write endpoint.
- CORS origins are explicit configuration; remote desktop clients require HTTPS.
- HSTS, `X-Content-Type-Options`, frame denial, referrer policy, CSP headers.
- SQL data access is via JPA/parameterized queries; search/sort fields must be allow-listed.
- Server-side page-size caps and database-side date/search filtering.
- Loopback bind defaults in local setup; do not expose MySQL to the internet.

## Release-blocking checks
1. Run `mvn -f backend/pom.xml test` and `mvn -f desktop/pom.xml test`; run `docker compose config` and a clean DB migration/startup.
2. Run OWASP ZAP baseline/active scan against a non-production test deployment; run dependency CVE scan (OWASP Dependency-Check or equivalent) and secret scanning.
3. Test BOLA/IDOR: change resource UUIDs across users/roles for bills, customers, backups, logs, device profiles and settings.
4. Test BFLA: call every write/export/admin endpoint using cashier, supervisor, admin, unauthenticated, expired and revoked tokens.
5. Test auth: brute-force/lockout, refresh/logout revocation, 2FA replay, weak/default secrets, JWT algorithm/signature/expiry/tampering.
6. Test input boundaries: oversized JSON/body, invalid UUID/date/enum/decimal, huge page/limit, sort injection, malformed email, promotion min quantity, Unicode/control characters.
7. Test business logic: race-condition checkout, negative/zero quantities, price tampering, duplicate bill requests, points over-redemption, return/cancel replay, promotion thresholds.
8. Test data leakage: exception bodies, logs, SSE streams, backups, exported CSV formulas, customer PII, CORS preflight and response headers.
9. Test transport/deployment: HTTPS certificate validation, TLS configuration, proxy headers, firewall, database least privilege, backup encryption/restore, SMTP secrets, production profile.
10. Ensure all authenticated sync streams are authorized and cart-display streams are channel-scoped; never place secrets in URLs or logs.

## Known review focus areas
- `SecurityConfig` disables CSRF because the API is designed for stateless bearer tokens. Do not switch to cookie-based authentication without CSRF protection. Keep browser JWT storage/XSS risk in mind.
- CORS is not authentication. The allow-list must be configured to actual trusted origins; credentialed CORS is disabled for the bearer-token API.
- HSTS is useful only over HTTPS; terminate TLS correctly and do not rely on it for local HTTP.
- In-memory rate limits and sync event buffers are per-process and reset on restart; multi-instance deployments need shared infrastructure.
- Check all controller paths for method-level permissions, object ownership, DTO constraints, and safe error messages.
