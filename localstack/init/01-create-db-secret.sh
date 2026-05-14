#!/usr/bin/env bash
# Creates two database connection secrets in LocalStack Secrets Manager.
# This script runs automatically when LocalStack starts (ready.d hook).
#
# securetask/db        — used when running the app locally (./gradlew bootRun)
#                        PostgreSQL is reachable at localhost:5432 from the host machine.
#
# securetask/db-docker — used when the app runs inside Docker Compose
#                        PostgreSQL is reachable at the service name "postgres" inside the Docker network.
set -euo pipefail

REGION="us-east-1"
ENDPOINT="http://localhost:4566"

create_or_update() {
  local name="$1"
  local value="$2"
  echo "[LocalStack init] Creating secret: ${name}"
  aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
      secretsmanager create-secret --name "${name}" --secret-string "${value}" 2>/dev/null \
  || aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
         secretsmanager put-secret-value --secret-id "${name}" --secret-string "${value}"
  echo "[LocalStack init] Secret '${name}' is ready."
}

# For ./gradlew bootRun on the host machine
create_or_update "securetask/db" '{
  "url": "jdbc:postgresql://localhost:5432/securetask",
  "username": "securetask_user",
  "password": "securetask_password"
}'

# For the app container inside Docker Compose
create_or_update "securetask/db-docker" '{
  "url": "jdbc:postgresql://postgres:5432/securetask",
  "username": "securetask_user",
  "password": "securetask_password"
}'
