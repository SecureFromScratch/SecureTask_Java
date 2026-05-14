# SecureTask

A step-by-step secure task-management lab for the secure development course.

Each lab builds on the previous one and introduces a new class of security vulnerability — first by leaving it open, then by fixing it properly.

## Lab order

| # | Topic | Walkthrough |
|---|-------|-------------|
| 00 | Setup from scratch | [walkthroughs/00-setup-from-scratch.md](walkthroughs/00-setup-from-scratch.md) |
| 01 | Authentication | [walkthroughs/01-authentication.md](walkthroughs/01-authentication.md) |
| 02 | Authorization | [walkthroughs/02-authorization.md](walkthroughs/02-authorization.md) |
| 03 | CSRF protection | [walkthroughs/03-csrf.md](walkthroughs/03-csrf.md) |
| 04 | SSRF prevention | [walkthroughs/04-ssrf.md](walkthroughs/04-ssrf.md) |
| 05 | Mass assignment prevention | _(coming soon)_ |
| 06 | Input validation, XSS, SQL injection | _(coming soon)_ |

---

## Prerequisites

- Docker and Docker Compose
- Java 21+
- Internet access (to download Gradle on first run)

---

## Setup

### 1. Start PostgreSQL and LocalStack

```bash
docker compose up -d postgres localstack
```

Docker Compose starts:
- **PostgreSQL 16** — listens on `localhost:5432`, database `securetask`
- **LocalStack 3** — emulates AWS Secrets Manager on `localhost:4566`

When LocalStack starts, `localstack/init/01-create-db-secret.sh` runs automatically and creates the secret `securetask/db` in Secrets Manager:

```json
{
  "url": "jdbc:postgresql://postgres:5432/securetask",
  "username": "securetask_user",
  "password": "securetask_password"
}
```

You can verify the secret with:

```bash
aws --endpoint-url=http://localhost:4566 \
    --region us-east-1 \
    secretsmanager get-secret-value \
    --secret-id securetask/db
```

### 2. Run the application

```bash
AWS_REGION=us-east-1 \
AWS_ACCESS_KEY_ID=test \
AWS_SECRET_ACCESS_KEY=test \
SECRETS_MANAGER_ENDPOINT=http://localhost:4566 \
DB_SECRET_NAME=securetask/db \
./gradlew bootRun
```

Or with the `local` Spring profile (reads defaults from `application-local.properties`):

```bash
AWS_REGION=us-east-1 \
AWS_ACCESS_KEY_ID=test \
AWS_SECRET_ACCESS_KEY=test \
SECRETS_MANAGER_ENDPOINT=http://localhost:4566 \
DB_SECRET_NAME=securetask/db \
./gradlew bootRun --args='--spring.profiles.active=local'
```

The app starts on **http://localhost:8080**.

### 3. Alternatively, run everything with Docker Compose

```bash
docker compose up
```

This builds the app image and starts all three services.

---

## How Spring Boot reads the database secret

`SecretsManagerConfig` runs at startup, before any database connection is attempted:

1. Creates an `SecretsManagerClient` pointed at `SECRETS_MANAGER_ENDPOINT`.
2. Calls `GetSecretValue` with the secret name from `DB_SECRET_NAME`.
3. Parses the JSON payload and extracts `url`, `username`, and `password`.
4. Builds a `DataSource` programmatically.

The app will **refuse to start** if the secret is missing or the endpoint is unreachable — there is no fallback to hard-coded credentials.

---

## Run tests

Tests use Testcontainers (real PostgreSQL, no LocalStack needed):

```bash
./gradlew test
```

Docker must be running so Testcontainers can start a PostgreSQL container.

---

## First admin user

There is no pre-seeded admin account. The **first user to register** automatically receives the `ADMIN` role. Every subsequent registration receives `VIEWER`.

1. Open http://localhost:8080/register.html
2. Create your account.
3. You will be assigned `ADMIN` because the users table is empty.

This assignment is protected by a database transaction. If two users register at exactly the same moment, the one whose transaction commits first becomes ADMIN; the other may also become ADMIN (if both observed an empty table) or fail with a conflict error depending on timing and the database's isolation level.
