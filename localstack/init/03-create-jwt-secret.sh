#!/usr/bin/env bash
set -euo pipefail
REGION="us-east-1"
ENDPOINT="http://localhost:4566"
create_or_update() {
  local name="$1" value="$2"
  echo "[LocalStack init] Creating secret: ${name}"
  aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
      secretsmanager create-secret --name "${name}" --secret-string "${value}" 2>/dev/null \
  || aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
         secretsmanager put-secret-value --secret-id "${name}" --secret-string "${value}"
  echo "[LocalStack init] Secret '${name}' is ready."
}
# 32-byte Base64-encoded HS256 secret — replace with a securely generated value in production
create_or_update "securetask/jwt" '{"secret":"c2VjdXJldGFzay1kZWZhdWx0LWp3dC1zZWNyZXQta2V5"}'
