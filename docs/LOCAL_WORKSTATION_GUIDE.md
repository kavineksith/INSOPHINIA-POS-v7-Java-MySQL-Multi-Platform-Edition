# Local workstation setup

## Required tools
- Supported JDK and Maven
- Node.js LTS and npm
- MySQL Server
- Optional local SMTP sink for email verification tests

## Environment separation
Maintain distinct `.env.dev`, `.env.test`, and `.env.prod` files outside Git. Use separate database names/users. Do not put real credentials in tracked files. `.env.example` files contain placeholders only.

## Run components
1. Create separate MySQL databases/users for dev and test.
2. Ensure MySQL 8 is running before starting the backend.
3. Run backend: `mvn -f backend/pom.xml spring-boot:run` with the selected Spring profile and environment variables.
4. Run web: `cd web && npm ci && npm run dev`, with `VITE_API_BASE_URL=http://127.0.0.1:8080`.
5. Run desktop: `mvn -f desktop/pom.xml test`; use the desktop launch instructions and configure its API URL to the local Spring Boot server.
6. Run tests from `TEST_MATRIX.md` against isolated test databases only.

## Local-only services
No AWS, cloud services, or Docker is required. Use local filesystem backups. In dev/test, use a local SMTP test sink; configure a real SMTP relay only when explicitly approved. Never use production data for tests.
