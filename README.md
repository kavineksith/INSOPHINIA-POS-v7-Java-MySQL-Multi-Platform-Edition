# INSOPHINIA POS --- Spring Boot + React/Vite + JavaFX Desktop

A multi-platform point-of-sale system with three runtime clients sharing
one Spring Boot REST API and one MySQL database.

-   `web/` --- React + Vite + Tailwind CSS, Redux Toolkit, and Context
    API.
-   `backend/` --- Java Spring Boot REST API with JWT authentication,
    role-based access control (RBAC), two-factor authentication (2FA),
    billing, inventory, warranties, repairs, marketing, and encrypted
    backups.
-   `desktop/` --- JavaFX desktop client communicating with the same
    REST API.

> **Important:** The JavaFX desktop client may also support an H2
> file-based `desktop` profile for embedded single-user operation.
> Review the project configuration and documentation before choosing a
> runtime profile.

## Repository

**GitHub:**
[INSOPHINIA-POS-v7-Java-MySQL-Multi-Platform-Edition](https://github.com/kavineksith/INSOPHINIA-POS-v7-Java-MySQL-Multi-Platform-Edition)

Clone the repository:

``` bash
git clone https://github.com/kavineksith/INSOPHINIA-POS-v7-Java-MySQL-Multi-Platform-Edition.git
cd INSOPHINIA-POS-v7-Java-MySQL-Multi-Platform-Edition
```

## Disclaimer, Warranty & Usage Notice

This project is provided **"AS IS" and "AS AVAILABLE"**, without
warranties or guarantees of any kind, whether express, implied, or
statutory.

-   **No production guarantee:** This project has not necessarily been
    independently audited, certified, penetration-tested, load-tested,
    or validated for real-world production use. Do not assume that it is
    production-ready merely because it builds or runs.
-   **Test before use:** You are responsible for reviewing the source
    code, testing all workflows, validating business calculations, and
    conducting appropriate security, privacy, reliability, and
    performance assessments before using it with real business data.
-   **Use at your own risk:** The maintainers and contributors are not
    responsible for data loss, business interruption, financial loss,
    security incidents, misconfiguration, or other damage arising from
    the use or inability to use this project, to the extent permitted by
    applicable law.
-   **Protect credentials:** Replace sample, default, or development
    credentials. Never commit `.env` files, passwords, signing keys,
    database credentials, API tokens, encryption keys, or other secrets.
-   **Back up your data:** Configure backups and verify that restoration
    works before relying on the system. A backup that has never been
    restored and tested should not be treated as a verified recovery
    plan.
-   **Review third-party components:** This project may use third-party
    libraries, frameworks, services, and tools that are governed by
    their own licenses and terms. You are responsible for reviewing and
    complying with them.
-   **Legal and regulatory compliance:** You are responsible for
    determining whether your deployment complies with applicable laws,
    tax rules, privacy requirements, payment-processing obligations, and
    industry regulations.
-   **No professional advice:** The project and its documentation are
    provided for software development, learning, and evaluation purposes
    and do not constitute legal, financial, security, or compliance
    advice.

The Apache License 2.0 also contains its own warranty disclaimer and
limitation-of-liability provisions. Read the complete license in the
`LICENSE` file. This README notice supplements the license and does not
replace or modify it.

## Security & Deployment Checklist

Before connecting this application to real business data or making it
accessible over a network, review every applicable item:

-   [ ] Replace all sample and default usernames, passwords, and
    credentials.
-   [ ] Create strong, unique secrets for JWT signing, 2FA, encryption,
    and other security-sensitive functions. Use the exact environment
    variable names documented by the application.
-   [ ] Keep secrets outside source control. Confirm `.env` and other
    local secret files are ignored by Git; never publish real
    credentials in issues, logs, screenshots, or documentation.
-   [ ] Use HTTPS/TLS for network communication outside a trusted local
    development environment. Do not expose an unencrypted production API
    to the public internet.
-   [ ] Restrict MySQL network access to the backend host and authorized
    administrators. Do not expose the database port publicly.
-   [ ] Use a dedicated database account with only the privileges
    required by the application. Avoid using the MySQL root account for
    normal application access.
-   [ ] Review Spring Security configuration, JWT validation, RBAC
    permissions, 2FA flows, session/token handling, CORS rules, and all
    authorization checks.
-   [ ] Disable development/debug settings and verbose error responses
    in production. Do not expose stack traces, internal configuration,
    or sensitive data to clients.
-   [ ] Keep JDK, Maven dependencies, Node packages, MySQL, Docker
    images, and operating-system packages updated. Review dependency
    vulnerabilities before deployment.
-   [ ] Review logging and encrypted-backup configuration to avoid
    leaking credentials, personal information, payment data, or
    encryption keys.
-   [ ] Configure automated backups, protect backup storage, and
    periodically perform a test restoration.
-   [ ] Test the web, backend, and desktop clients together, including
    API failures, database outages, concurrent transactions, and
    recovery scenarios.
-   [ ] Restrict administrative tools and management endpoints to
    trusted users and networks.
-   [ ] Verify firewall rules and expose only the ports required for the
    intended deployment.

This checklist is a starting point, not a complete security audit or a
guarantee of safety.

## Architecture

``` text
React/Vite web client ─┐
                       ├── HTTP/HTTPS JSON REST API ── Spring Boot backend ── MySQL 8.0
JavaFX desktop client ─┘
```

The Spring Boot backend owns the application database credentials and
database access. The web and JavaFX clients communicate through the
backend API contract; they should not independently connect to the
shared MySQL database.

Use HTTPS when clients communicate across untrusted networks. The local
development setup may use HTTP on loopback interfaces.

## Start Here

Before running the application, review these project documents:

1.  `docs/LOCAL_WORKSTATION_GUIDE.md` --- workstation prerequisites and
    local setup, including JDK 17, Maven 3.9+, Node.js LTS, and MySQL 8.
2.  `docs/TEST_MATRIX.md` --- test coverage and test execution guidance.
3.  `docs/disaster-recovery-runbook.md` --- backup and restore
    procedures.

Also review `.env.example` and the configuration files for the profile
you intend to use.

## Prerequisites

The supplied workstation guide identifies these primary requirements:

-   JDK 17
-   Apache Maven 3.9+
-   Node.js LTS and npm
-   MySQL 8.0
-   Docker and Docker Compose for the MySQL container workflow and
    integration tests that require Docker

Confirm the exact versions and any additional platform-specific
requirements in the project documentation before installation.

## Quick Start --- Local Development

Run commands from the repository root unless a step changes directory.

### 1. Start MySQL with Docker Compose

``` bash
./scripts/test-mysql.sh up
```

Check the script and Compose configuration before running them,
especially if you already have a MySQL service using the same port.

### 2. Configure environment variables

``` bash
cp .env.example .env
```

Edit `.env` and supply appropriate local development values. Do not
commit this file or place production secrets in it.

### 3. Start the Spring Boot backend

``` bash
cd backend
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
```

### 4. Start the React/Vite web client

Open a second terminal at the repository root:

``` bash
cd web
npm install
npm run dev
```

### 5. (Optional) Run the JavaFX desktop client

Open another terminal at the repository root:

``` bash
cd desktop
mvn javafx:run
```

The desktop client must be configured to communicate with the intended
backend/API endpoint when using the shared REST API architecture. Check
its configuration before use.

## Configuration Profiles

The supplied project configuration describes these profiles:

  -----------------------------------------------------------------------
  Profile                 Datasource              Intended use
  ----------------------- ----------------------- -----------------------
  `dev`                   MySQL at                Local workstation
                          `127.0.0.1:3306`        development

  `test`                  Testcontainers MySQL 8  JUnit integration tests

  `prod`                  External MySQL instance Deployment with secrets
                                                  supplied through
                                                  environment variables

  `desktop`               H2 file                 Embedded single-user
                                                  mode
  -----------------------------------------------------------------------

The `desktop` H2 profile is a distinct operating mode from the shared
MySQL/API deployment. Do not assume that data stored in the H2 file is
automatically shared with the web client or the MySQL database. Verify
the profile-specific behavior in the application configuration and
documentation.

Do not use the `dev` or `test` profile for a production deployment.

## Running Tests

Run the relevant tests after making changes and before deploying.

### Backend

``` bash
cd backend

# Run all tests (Docker is required for integration tests that use Testcontainers)
mvn test

# Run unit tests only, excluding integration tests
mvn -Dtest='!*IntegrationTest' test
```

### Web

``` bash
cd web

# Run Vitest tests
npm run test

# Create a production build
npm run build
```

### Desktop

``` bash
cd desktop

# Run desktop unit tests
mvn test
```

See `docs/TEST_MATRIX.md` for the intended test plan. A successful build
or passing test suite does not, by itself, establish production
security, correctness, or suitability for a particular business.

## Production Deployment

The following is a high-level build outline. Adapt it to the actual
target environment and review the deployment documentation before
exposing any service.

1.  Configure the production profile:

    ``` bash
    export SPRING_PROFILES_ACTIVE=prod
    ```

2.  Supply all required secrets and database settings through
    environment variables or an approved secrets-management mechanism.
    Refer to `.env.example` for the expected variable names; do not copy
    development secrets into production.

3.  Build the backend:

    ``` bash
    cd backend
    mvn -DskipTests clean package
    ```

    Run the required test suite separately before deployment.
    `-DskipTests` skips test execution and is not a substitute for
    testing.

4.  Optionally build the backend Docker image from the repository root:

    ``` bash
    docker build -f backend/Dockerfile -t insophinia-pos-backend .
    ```

5.  Build the JavaFX desktop installer, if required:

    ``` bash
    cd desktop
    mvn -Pjpackage package
    ```

6.  Build and deploy the web client using the deployment method
    appropriate for the target environment. Configure its API base URL
    to point to the intended backend endpoint.

7.  Configure HTTPS/TLS, firewall rules, database access restrictions,
    logging, monitoring, backup storage, and a tested restore procedure
    before enabling real users.

No cloud deployment is required by the supplied local-workstation
workflow. A local workstation may be the intended deployment target, but
local deployment does not remove the need for access control, backups,
secure configuration, and operational testing.

## Important Operational Warnings

### Database and Docker volumes

Be careful with commands that remove Docker volumes. For example:

``` bash
docker compose down -v
```

The `-v` option removes volumes declared by the Compose project and can
permanently delete the database data stored in those volumes. Do not run
it against a database containing data you need. Confirm the Compose
project and volume names, and take a verified backup first.

The `./scripts/test-mysql.sh` script is project-specific. Review its
contents before using any command that resets, recreates, or removes
containers, volumes, or databases.

### Database exposure

Do not expose MySQL port `3306` to the public internet. Bind development
database ports to loopback where possible and restrict access using
firewall rules and database permissions.

### Backend API exposure

Do not expose the Spring Boot API publicly without reviewing
authentication, authorization, HTTPS, CORS, rate limiting, error
handling, logging, and production profile settings.

### Secrets and source control

Never push `.env`, private keys, production configuration, database
dumps, backup encryption keys, or real customer/business data to GitHub.
If a secret is accidentally committed, removing it from the latest
commit is not sufficient: rotate the exposed credential and address the
repository history.

### Backups and recovery

Encrypted backups are useful only when their encryption keys are
protected and recovery is possible. Store backup copies separately from
the primary database, restrict access, and test restoration periodically
using a safe environment.

## Project Documentation

  -------------------------------------------------------------------------
  Document                              Purpose
  ------------------------------------- -----------------------------------
  `docs/LOCAL_WORKSTATION_GUIDE.md`     Local development environment and
                                        setup

  `docs/TEST_MATRIX.md`                 Test plan and coverage guidance

  `docs/disaster-recovery-runbook.md`   Backup and disaster-recovery
                                        procedures

  `.env.example`                        Environment variable template
  -------------------------------------------------------------------------

Check the repository for additional documentation and component-specific
setup instructions.

## License

This project is licensed under the **Apache License, Version 2.0**.

You may use, reproduce, modify, and distribute the software in
accordance with the terms of the Apache License 2.0. You must comply
with its conditions, including applicable copyright and license notices,
and include a copy of the license with distributions.

See the [`LICENSE`](LICENSE) file in this repository or read the full
license at:

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, the software
is distributed on an **"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
OF ANY KIND**, either express or implied. See the license for the
specific language governing permissions and limitations.

## Third-Party Software

This project uses or may use third-party frameworks, libraries, build
tools, and services, including Spring Boot, React, Vite, Tailwind CSS,
Redux Toolkit, JavaFX, Maven, Node.js packages, and MySQL.

Each third-party component remains subject to its own license and terms.
Refer to the relevant dependency manifests and upstream project notices
for details. The Apache License 2.0 applied to this project does not
override the licenses of third-party components.

## Final Notice

Review the source code, project documentation, configuration, and
license before using, modifying, or distributing this software.
Deployments involving real transactions, customer information, employee
records, or business-critical data should undergo appropriate technical,
security, legal, and operational review first.
