# SecureTask

A step-by-step secure task-management lab for the secure development course.

Each lab builds on the previous one and introduces a new class of security vulnerability — first by leaving it open, then by fixing it properly.

## Lab order

| # | Topic | Walkthrough |
|---|-------|-------------|
| 00 | Setup from scratch | [walkthroughs/00-setup-from-scratch.md](walkthroughs/00-setup-from-scratch.md) |
| 01 | Authentication | [walkthroughs/01-authentication.md](walkthroughs/01-authentication.md) |
| 02 | JWT authentication | [walkthroughs/02-jwt.md](walkthroughs/02-jwt.md) |
| 03 | Authorization | [walkthroughs/03-authorization.md](walkthroughs/03-authorization.md) |
| 04 | CSRF protection | [walkthroughs/04-csrf.md](walkthroughs/04-csrf.md) |
| 05 | SSRF prevention | [walkthroughs/05-ssrf.md](walkthroughs/05-ssrf.md) |
| 06 | Input validation and XSS prevention | [walkthroughs/06-xss-validation.md](walkthroughs/06-xss-validation.md) |
| 07 | Secure file upload | [walkthroughs/07-file-upload.md](walkthroughs/07-file-upload.md) |
| 08 | Mass assignment | [walkthroughs/08-mass-assignment.md](walkthroughs/08-mass-assignment.md) |

---

## Project structure

```
SecureTask_Java/
├── frontend/          # Plain HTML, CSS, JavaScript — served by the BFF
├── api/               # Spring Boot REST API — port 8080, JWT Bearer only
├── bff/               # Spring Boot BFF — port 8081, browser session cookies
├── localstack/        # LocalStack init scripts (secrets, S3 bucket)
├── docker-compose.yml
└── walkthroughs/      # Lab guides
```

**Browser access** goes through the BFF at **http://localhost:8081**. The BFF manages server-side sessions and injects JWT Bearer tokens when calling the main API — the browser never sees a JWT.

**Direct API access** (curl, Postman) uses **http://localhost:8080** with `Authorization: Bearer <token>`.

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
- **LocalStack 3** — emulates AWS Secrets Manager and S3 on `localhost:4566`

When LocalStack starts, the init scripts run automatically and create two secrets:

| Secret name | Contents |
|-------------|----------|
| `securetask/db` | PostgreSQL URL, username, password |
| `securetask/jwt` | JWT signing key |

You can verify with:

```bash
aws --endpoint-url=http://localhost:4566 \
    --region us-east-1 \
    secretsmanager get-secret-value \
    --secret-id securetask/db
```

### 2. Run the API

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

The API starts on **http://localhost:8080**.

### 3. Run the BFF

In a second terminal:

```bash
./gradlew :bff:bootRun
```

The BFF starts on **http://localhost:8081** and connects to the API at `http://localhost:8080` by default. No environment variables are needed for local development.

### 4. Alternatively, run everything with Docker Compose

```bash
docker compose up
```

This builds both app images and starts all four services (postgres, localstack, api, bff).

---

## How Spring Boot reads the database secret

`SecretsManagerConfig` runs at startup, before any database connection is attempted:

1. Creates a `SecretsManagerClient` pointed at `SECRETS_MANAGER_ENDPOINT`.
2. Calls `GetSecretValue` with the secret name from `DB_SECRET_NAME`.
3. Parses the JSON payload and extracts `url`, `username`, and `password`.
4. Builds a `DataSource` programmatically.

The app will **refuse to start** if the secret is missing or the endpoint is unreachable — there is no fallback to hard-coded credentials.

---

## Run tests

Tests use Testcontainers (real PostgreSQL, no LocalStack needed):

```bash
./gradlew :api:test
```

Docker must be running so Testcontainers can start a PostgreSQL container.

---

## First admin user

There is no pre-seeded admin account. The **first user to register** automatically receives the `ADMIN` role. Every subsequent registration receives `VIEWER`.

1. Open **http://localhost:8081/register.html**
2. Create your account.
3. You will be assigned `ADMIN` because the users table is empty.

This assignment is protected by a database transaction. If two users register at exactly the same moment, the one whose transaction commits first becomes ADMIN; the other may also become ADMIN (if both observed an empty table) or fail with a conflict error depending on timing and the database's isolation level.
