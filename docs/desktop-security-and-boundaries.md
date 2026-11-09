# Desktop Security Model and Deployment Boundaries

## Trust boundaries

- The JavaFX desktop is a client, not an authorization authority.
- Spring Security JWT authentication and method-level `@PreAuthorize` checks enforce permissions on the server.
- Local Compose defaults publish the API and MySQL ports only on loopback (`127.0.0.1`).
- The JavaFX client refuses to send credentials or JWTs to a remote plain-HTTP origin; remote API URLs must use HTTPS.
- Secrets live in a local `.env` file that should be restricted to the operating-system user and excluded from source control.
- SMTP credentials are environment-only and are not returned by the Settings API.

## Recommended single-terminal deployment

Run the database and backend locally with Docker Compose, keep both host ports on loopback, use separate accounts per staff member, enable 2FA where available, keep automatic backups enabled, and test a restore before entering real data. Do not expose Swagger/OpenAPI or the database port beyond the local machine.

## Multi-terminal deployment

The setup wizard intentionally does not enable LAN access by default. For multiple terminals, deploy a dedicated server on a controlled private network and terminate HTTPS at a properly configured reverse proxy or ingress. Apply firewall allow-lists, restrict database access to the backend container, rotate secrets, back up the encryption keys separately, and validate TLS certificates on clients. Never send JWTs over plain HTTP on an untrusted network.

## Operational limitations

- Customer-display cart synchronization is local to the checkout process; server SSE is used to refresh data views, not to stream another terminal's in-progress cart.
- Native printer output uses the Java desktop printing API. ESC/POS output depends on the configured printer/driver. Printer success should be verified physically.
- PDF receipt export is local and does not upload the file.
- Camera scanning and WhatsApp integration are intentionally out of scope.
- This package must be compiled and tested in the target OS before production deployment. Do not treat source presence or ZIP creation as a passing test run.
