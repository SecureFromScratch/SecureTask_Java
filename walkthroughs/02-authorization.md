# Lab 02 — Authorization

## Learning goals

By the end of this lab you will be able to:

- Explain the difference between authentication and authorization.
- Distinguish between vertical and horizontal privilege escalation.
- Use `@PreAuthorize` to enforce role-based access control at the service layer.
- Explain why method-level security is more reliable than URL-only security.
- Identify and prevent self-escalation attacks.
- Return correct HTTP status codes for authorization failures (403 vs 401).

---

## Background

Authorization answers the question: _what are you allowed to do?_

Authentication (Lab 01) established who the user is. Authorization builds on that — knowing who someone is does not automatically mean they can do everything.

### Two types of privilege escalation

**Vertical privilege escalation** — a lower-privileged user accesses functionality reserved for a higher-privileged role.

Example: a `VIEWER` calls `GET /api/admin/users` and receives a full list of all accounts.

**Horizontal privilege escalation** — a user accesses or modifies another user's resources at the same privilege level.

Example: user A changes user B's role by guessing B's ID in the URL.
This lab addresses both.

### OWASP A01:2021 — Broken Access Control

Missing or incorrectly implemented authorization is the number-one web application security risk according to OWASP. Common mistakes include:

| Mistake | Consequence |
|---------|-------------|
| Checking authorization only in the URL filter, not in the service | A new endpoint added later silently bypasses the check |
| Trusting client-supplied user IDs | Any user can act on any other user's data |
| Returning 403 for unauthenticated requests | Misleads clients; the correct code is 401 |
| Allowing users to change their own role | A compromised account can re-grant itself privileges |

---

## Relevant files

| File | Purpose |
|------|---------|
| `src/main/java/com/securetask/service/AdminService.java` | Authorization logic — `@PreAuthorize` and self-escalation guard |
| `src/main/java/com/securetask/controller/AdminController.java` | HTTP layer — translates requests and responses |
| `src/main/java/com/securetask/dto/RoleChangeRequest.java` | Inbound DTO — accepts only `role`, nothing else |
| `src/main/java/com/securetask/security/SecurityConfig.java` | `@EnableMethodSecurity` activates `@PreAuthorize` |
| `src/main/java/com/securetask/dto/UserResponse.java` | Outbound DTO — safe fields only, no `passwordHash` |
| `src/test/java/com/securetask/AdminControllerTest.java` | Tests for all authorization scenarios |

---

## Security risks addressed

### 1. Vertical privilege escalation
`@PreAuthorize("hasRole('ADMIN')")` on `AdminService.listAllUsers()` and `AdminService.changeUserRole()` ensures that only ADMIN users can reach those methods. A VIEWER or DEVELOPER receives a 403 before the method body executes.

### 2. Authorization bypass via new endpoints
The `@PreAuthorize` annotation lives on the **service**, not only on the controller. If a second controller or a scheduled job ever calls `AdminService` directly, the check still fires. URL-only rules in `SecurityConfig` only protect routes that were known at configuration time.

### 3. Self-escalation
An admin cannot change their own role. The guard in `AdminService.changeUserRole()` compares the target user's ID with the authenticated caller's ID and throws `ConflictException` if they match. This prevents:
- A compromised admin account from re-granting itself privileges after a downgrade.
- Accidental self-lockout by an admin demoting themselves to VIEWER.

### 4. Caller identity from the session, not the request body
The caller's username is read from `Authentication` (the server-side session), not from any field in the request body. The client cannot impersonate a different admin.

---

## Step-by-step implementation guide

> **Note:** The implementation is already complete. Use these steps to understand what was built, or to re-implement it from scratch.

### Step 1 — Enable method security

Open `SecurityConfig.java`. Find the `@EnableMethodSecurity` annotation.

Without this annotation, `@PreAuthorize` is silently ignored — the method executes regardless of the caller's role. This is a common, hard-to-spot mistake.

**Question:** Where would you discover that `@PreAuthorize` was being ignored? What test would catch it?

### Step 2 — Trace a forbidden request

A `VIEWER` calls `GET /api/admin/users`:

1. The request passes the filter chain — the user is authenticated, and the path is not explicitly blocked by URL rules.
2. Spring AOP intercepts the call to `AdminService.listAllUsers()`.
3. `@PreAuthorize("hasRole('ADMIN')")` evaluates the caller's authorities.
4. The caller has `ROLE_VIEWER`, not `ROLE_ADMIN` — the check fails.
5. Spring Security throws `AccessDeniedException`.
6. The exception handler returns `403 Forbidden`.

The method body never executes.

### Step 3 — Inspect the self-escalation guard

Open `AdminService.changeUserRole()`. Find the block that compares IDs:

```java
if (caller.getId().equals(targetId)) {
    throw new ConflictException("Admins cannot change their own role");
}
```

**Question:** Why compare IDs rather than usernames?

### Step 4 — Verify the caller identity source

Open `AdminController.changeRole()`. Find where `authentication.getName()` is passed to the service.

The controller reads the caller's username from the **session** (the `Authentication` object injected by Spring Security), not from the request body or a query parameter. The client has no way to claim a different identity.

### Step 5 — Try to escalate via curl

Register two users (first becomes ADMIN, second becomes VIEWER), then:

```bash
# Login as VIEWER and save the session cookie
curl -c viewer-cookies.txt -X POST http://localhost:8080/login \
  -d "username=viewer&password=password123"

# Try to list all users as VIEWER → expect 403
curl -b viewer-cookies.txt http://localhost:8080/api/admin/users

# Try to change admin's role as VIEWER → expect 403
curl -b viewer-cookies.txt -X PATCH \
  http://localhost:8080/api/admin/users/1/role \
  -H "Content-Type: application/json" \
  -d '{"role":"ADMIN"}'
```

### Step 6 — Try self-escalation as ADMIN

```bash
# Login as ADMIN
curl -c admin-cookies.txt -X POST http://localhost:8080/login \
  -d "username=admin&password=password123"

# Get your own ID from /api/me
curl -b admin-cookies.txt http://localhost:8080/api/me

# Try to change your own role → expect 409
curl -b admin-cookies.txt -X PATCH \
  http://localhost:8080/api/admin/users/<your-id>/role \
  -H "Content-Type: application/json" \
  -d '{"role":"VIEWER"}'
```

---

## Manual test checklist

- [ ] `GET /api/admin/users` as ADMIN → 200, list of users, no `passwordHash` field
- [ ] `GET /api/admin/users` as VIEWER → 403
- [ ] `GET /api/admin/users` unauthenticated → 401
- [ ] `PATCH /api/admin/users/{id}/role` as ADMIN on another user → 200, updated role
- [ ] `PATCH /api/admin/users/{id}/role` as ADMIN on own ID → 409
- [ ] `PATCH /api/admin/users/{id}/role` as VIEWER → 403
- [ ] `PATCH /api/admin/users/99999/role` as ADMIN → 404

---

## Expected results

| Request | Caller | Expected |
|---------|--------|----------|
| `GET /api/admin/users` | ADMIN | 200, list of all users |
| `GET /api/admin/users` | VIEWER | 403 Forbidden |
| `GET /api/admin/users` | Not logged in | 401 Unauthorized |
| `PATCH .../role` (other user) | ADMIN | 200, updated `UserResponse` |
| `PATCH .../role` (own ID) | ADMIN | 409 Conflict |
| `PATCH .../role` | VIEWER | 403 Forbidden |
| `PATCH .../role` (id=99999) | ADMIN | 404 Not Found |

---

## Common mistakes

**Mistake:** Putting `@PreAuthorize` only on the controller method.
**Why it matters:** Any code path that bypasses the controller — a scheduled task, an internal service call, a new endpoint added later — skips the check entirely. Annotating the service method ensures the check is enforced everywhere.

**Mistake:** Forgetting `@EnableMethodSecurity` in `SecurityConfig`.
**Why it matters:** Without it, `@PreAuthorize` annotations are silently ignored. The endpoints appear to work but enforce nothing. This is caught only by tests that explicitly verify a non-admin is rejected.

**Mistake:** Accepting the caller's ID from the request body rather than from the session.
**Why it matters:** A client can send any ID in the body. Reading the identity from `Authentication` (the server-side session) makes it unforgeable.

**Mistake:** Not guarding against self-role-changes.
**Why it matters:** If an admin account is compromised, an attacker could demote other admins and then re-elevate their own account. The self-change guard breaks this attack path.

**Mistake:** Returning 403 for an unauthenticated request.
**Why it matters:** 403 means "authenticated but forbidden." An unauthenticated request should return 401 so the client knows to prompt for login. Spring Security's default for API paths is already configured in `SecurityConfig` to return 401.

---

## Discussions

1. What is the difference between authentication and authorization?
2. Why is `@PreAuthorize` placed on the service method rather than only on the controller?
3. What is vertical privilege escalation? Give an example using this codebase.
4. Why can't an admin change their own role? What attack does this guard against?
5. The caller's username comes from `Authentication`, not from the request body. Why does this matter?
6. What HTTP status code is returned when an authenticated user calls an admin endpoint without the ADMIN role? Why not 401?
7. What would happen if `@EnableMethodSecurity` were removed from `SecurityConfig`?

---

## Answers

### 1. What is the difference between authentication and authorization?

Authentication answers _who are you?_ — it verifies identity, typically via a username and password. The result is a known principal (a logged-in user).

Authorization answers _what are you allowed to do?_ — it checks whether the authenticated principal has permission to perform a specific action or access a specific resource.

Authentication must happen before authorization. You cannot make a meaningful access decision about someone whose identity you have not yet verified.

### 2. Why is `@PreAuthorize` placed on the service method rather than only on the controller?

A URL-based rule in `SecurityConfig` (`.requestMatchers("/api/admin/**").hasRole("ADMIN")`) only protects routes that go through the HTTP layer. If someone adds a second controller that calls `AdminService` directly, or a scheduled job calls the service, the URL rule is never evaluated.

`@PreAuthorize` on the service method is enforced by Spring AOP regardless of who calls the method. The authorization check travels with the code, not with the route. It cannot be bypassed by adding a new entry point.

### 3. What is vertical privilege escalation? Give an example using this codebase.

Vertical privilege escalation is when a lower-privileged user accesses functionality reserved for a higher-privileged role.

In this codebase: a `VIEWER` sending `GET /api/admin/users`. Without `@PreAuthorize`, the endpoint would return the full user list to anyone who is authenticated, regardless of role. With `@PreAuthorize("hasRole('ADMIN')")`, the call is rejected with 403 before the service method body executes.

### 4. Why can't an admin change their own role? What attack does this guard against?

If an admin account is compromised, an attacker might try to:
1. Demote all other admins to VIEWER (removing their ability to revoke access).
2. Re-elevate the compromised account back to ADMIN if it gets demoted.

The self-change guard breaks step 2. A compromised admin account cannot re-grant itself ADMIN privileges after a security team downgrades it. It also prevents accidental self-lockout — an admin cannot accidentally demote themselves to VIEWER and lose all administrative access.

### 5. The caller's username comes from `Authentication`, not from the request body. Why does this matter?

`Authentication` is populated by Spring Security from the server-side session. The client cannot forge or modify it — the session token is opaque, and the server controls what identity it maps to.

If the caller's identity came from the request body (e.g., `{"callerUsername": "admin", "targetId": 5}`), any client could claim to be any user. A VIEWER could send `callerUsername: admin` and bypass the self-escalation guard or impersonate an admin entirely.

### 6. What HTTP status code is returned when an authenticated user calls an admin endpoint without the ADMIN role? Why not 401?

`403 Forbidden`. The distinction:

- **401 Unauthorized** — the server does not know who you are. No valid session was provided. The client should prompt for login.
- **403 Forbidden** — the server knows exactly who you are, and you are not permitted to do this. Prompting for login again would not help.

Returning 401 for a role failure misleads the client into thinking re-authenticating would solve the problem. It would not — the user's role is set at registration time.

### 7. What would happen if `@EnableMethodSecurity` were removed from `SecurityConfig`?

`@PreAuthorize` annotations would be silently ignored. Spring would not create the AOP proxy that intercepts method calls and evaluates the expression. Every call to `AdminService.listAllUsers()` and `AdminService.changeUserRole()` would succeed regardless of the caller's role.

The endpoints would appear to work correctly for admins, but a VIEWER would also receive a 200 response with the full user list. This failure is invisible without tests that explicitly verify non-admin access is rejected — which is exactly why `AdminControllerTest` includes `viewerCannotListUsers()` and `viewerCannotChangeAnyRole()`.
