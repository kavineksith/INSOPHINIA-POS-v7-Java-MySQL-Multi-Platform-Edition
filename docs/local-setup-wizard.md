# Local Database Setup Wizard

## What it does

The desktop login screen's **Local database setup…** button opens a wizard that writes a backend `.env` file, generates random JWT/cron/TOTP/backup secrets, optionally accepts SMTP settings, and can invoke `docker compose up -d --build` for the MySQL + Spring Boot stack.

The wizard expects the selected directory to contain `docker-compose.yml` and the backend `Dockerfile`/`pom.xml`/`src` tree. Docker Engine and the Docker Compose plugin must be installed and available as `docker compose`.

## Default network boundary

The generated Compose configuration binds the MySQL and HTTP API host ports to `127.0.0.1`. This is the intended default for a single-terminal local POS. The container's Spring Boot process listens on its internal interface, but the host port is not published on the LAN by default.

Do not change `API_BIND_ADDRESS` or `DB_BIND_ADDRESS` to `0.0.0.0` for a shop LAN without a deliberate deployment review. Multi-terminal use should be deployed behind TLS with firewall rules, private network controls, unique accounts, server-side RBAC, and tested backups. Plain HTTP bearer tokens on an untrusted LAN are not an acceptable production configuration.

## Steps

1. Install Docker Engine and the Docker Compose plugin.
2. Open INSOPHINIA POS Desktop → **Local database setup…**.
3. Select the `backend` directory containing `docker-compose.yml` if it is not detected automatically.
4. Set the database name and user. Leave the database password blank to generate a random one, or provide a long unique secret.
5. Set an initial admin password with at least 12 characters, upper/lowercase letters, a digit, and a special character.
6. Optionally configure SMTP host, port, username, and password. Keep email credentials private.
7. Select **Save and start local POS**. The wizard writes `.env` with owner-only permissions where supported and runs `docker compose up -d --build`.
8. Sign in at the API URL shown by the wizard using username `master` and the initial admin password. Change temporary/default credentials if the application prompts you.

If startup fails, inspect Docker output and run these commands from the backend folder:

```bash
docker compose ps
docker compose logs --tail=200 db backend
```

The `.env` file contains database credentials and signing/encryption secrets. Do not commit it, upload it, or include it in support screenshots. It is excluded by `.gitignore`.

## SMTP

SMTP values are loaded as environment variables when the backend starts. After changing `.env`, recreate the backend service so the new values are loaded:

```bash
docker compose up -d --force-recreate backend
```

Use Settings → Send test email to validate delivery. No WhatsApp integration is configured.


For cross-terminal customer display, configure `POS_DISPLAY_CHANNEL` as the same random value (16+ characters) in the backend environment and the paired checkout/display process environment. Use a unique channel for each checkout lane. On a separate display terminal set `POS_CUSTOMER_DISPLAY_ONLY=true`; set `POS_TERMINAL_ID` uniquely for each checkout terminal. Keep the channel out of source control and use HTTPS for remote terminals.
