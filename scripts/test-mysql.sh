#!/usr/bin/env bash
# =============================================================================
# INSOPHINIA POS — Local MySQL helper for development & integration tests.
# =============================================================================
# Local MySQL helper for development & integration tests.
# the original release notes. It does three things:
#
#   1. Starts a disposable MySQL 8.0 container via docker compose.
#   2. Waits until MySQL accepts connections.
#   3. (Optional) Runs Flyway + Spring Boot integration tests against the
#      freshly migrated database (Testcontainers handles this transparently
#      for `mvn test`, so this is mostly useful for ad-hoc DB inspection).
#
# Usage:
#   ./scripts/test-mysql.sh up          # start MySQL on 127.0.0.1:3306
#   ./scripts/test-mysql.sh wait        # block until MySQL is healthy
#   ./scripts/test-mysql.sh migrate     # run Flyway migrations against the dev DB
#   ./scripts/test-mysql.sh shell       # open mysql CLI inside the container
#   ./scripts/test-mysql.sh down        # stop and remove the MySQL container
#   ./scripts/test-mysql.sh reset       # down + delete the data volume
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
COMPOSE_FILE="$PROJECT_DIR/docker-compose.yml"

if ! command -v docker >/dev/null 2>&1; then
    echo "ERROR: docker is required. Install Docker or Docker Desktop and retry." >&2
    exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
    echo "ERROR: 'docker compose' plugin v2 is required. Install the docker-compose-plugin." >&2
    exit 1
fi

cd "$PROJECT_DIR"

if [[ ! -f .env ]]; then
    echo "INFO: no .env file found; copying .env.example to .env"
    cp .env.example .env
fi

# shellcheck disable=SC1090
set -a; source .env; set +a

case "${1:-up}" in
    up)
        docker compose -f "$COMPOSE_FILE" up -d
        echo "MySQL starting on 127.0.0.1:3306 ..."
        "$0" wait
        echo "Ready. JDBC URL: jdbc:mysql://127.0.0.1:3306/${MYSQL_DATABASE:-insophinia_pos}"
        ;;
    wait)
        echo "Waiting for MySQL to accept connections ..."
        for i in {1..60}; do
            if docker exec insophinia-mysql-dev mysqladmin ping \
                    -h 127.0.0.1 -u root -p"${MYSQL_ROOT_PASSWORD:-LocalRootOnly_ChangeMe_123}" \
                    --silent 2>/dev/null; then
                echo "MySQL is healthy."
                exit 0
            fi
            sleep 1
        done
        echo "ERROR: MySQL did not become healthy within 60s." >&2
        exit 1
        ;;
    migrate)
        "$0" wait >/dev/null
        echo "Running Flyway migrations via Maven ..."
        (cd "$PROJECT_DIR/backend" && mvn flyway:migrate \
            -Dflyway.url="jdbc:mysql://127.0.0.1:3306/${MYSQL_DATABASE:-insophinia_pos}?allowPublicKeyRetrieval=true" \
            -Dflyway.user="${MYSQL_USER:-insophinia}" \
            -Dflyway.password="${MYSQL_PASSWORD:-CHANGE_ME}" \
            -Dflyway.locations=classpath:db/migration)
        ;;
    shell)
        docker exec -it insophinia-mysql-dev mysql -u"${MYSQL_USER:-insophinia}" -p"${MYSQL_PASSWORD:-CHANGE_ME}" "${MYSQL_DATABASE:-insophinia_pos}"
        ;;
    logs)
        docker compose -f "$COMPOSE_FILE" logs -f mysql
        ;;
    down)
        docker compose -f "$COMPOSE_FILE" down
        ;;
    reset)
        docker compose -f "$COMPOSE_FILE" down -v
        ;;
    *)
        echo "Usage: $0 {up|wait|migrate|shell|logs|down|reset}" >&2
        exit 2
        ;;
esac
