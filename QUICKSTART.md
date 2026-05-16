# Quick Start

## 1 — Start the backing services

```bash
docker compose up -d postgres localstack
```

Wait until both are healthy (about 10 seconds):

```bash
docker compose ps
```

---

## 2 — Terminal 1: start the API

```bash
AWS_REGION=us-east-1 \
AWS_ACCESS_KEY_ID=test \
AWS_SECRET_ACCESS_KEY=test \
SECRETS_MANAGER_ENDPOINT=http://localhost:4566 \
S3_ENDPOINT=http://localhost:4566 \
DB_SECRET_NAME=securetask/db \
JWT_SECRET_NAME=securetask/jwt \
./gradlew :api:bootRun
```

Ready when you see: `Started SecureTaskApplication`

---

## 3 — Terminal 2: start the BFF

```bash
./gradlew :bff:bootRun
```

Ready when you see: `Tomcat started on port 8081`

---

## 4 — Open the browser

**http://localhost:8081**

Register an account — the first user becomes **ADMIN**.

---

## Ports

| Service | URL | Use for |
|---------|-----|---------|
| BFF | http://localhost:8081 | Browser, all lab exercises |
| API | http://localhost:8080 | Direct curl / Postman (JWT Bearer) |

---

## Run tests

```bash
./gradlew :api:test
```

(Docker must be running — tests use Testcontainers for PostgreSQL.)

---

## Stop everything

```bash
# Stop Spring Boot apps: Ctrl+C in each terminal

# Stop Docker services:
docker compose down
```
