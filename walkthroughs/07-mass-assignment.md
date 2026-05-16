# Lab 07 — Mass Assignment

## Learning goals

By the end of this lab you will be able to:

1. Explain what mass assignment is and why it occurs in Spring Boot applications.
2. Identify which fields in a DTO should never be user-controlled.
3. Exploit mass assignment to set a backdated completion timestamp and pin a task without admin privileges.
4. Fix mass assignment by removing sensitive fields from input DTOs and managing them server-side.
5. Implement a dedicated privileged endpoint with a role check instead of embedding a privileged field in a general-purpose DTO.

---

## Background

### What is mass assignment?

Mass assignment occurs when a framework automatically binds HTTP request data to object properties without an explicit whitelist of allowed fields. The attacker adds extra fields to the request body that the application did not intend to accept, and the framework silently sets them.

In Spring Boot, this happens in two common forms:

**Form 1 — Entity used directly as `@RequestBody`**

```java
// VULNERABLE — Jackson binds every JSON field directly onto the JPA entity.
@PostMapping
public Task create(@RequestBody Task task, Authentication auth) {
    task.setOwner(resolveUser(auth.getName()));
    return taskRepository.save(task);
}
```

An attacker can send:

```json
{
  "title": "My task",
  "id": 99,
  "pinned": true,
  "completedAt": "2020-01-01T00:00:00Z",
  "owner": { "id": 3 }
}
```

- `id: 99` overwrites an existing task record (upsert)
- `pinned: true` self-promotes the task without admin privileges
- `completedAt` is backdated — tampers with the audit trail
- `owner.id: 3` reassigns the task to another user

**Form 2 — DTO includes fields the user should not control**

A developer adds a field to a DTO "for convenience":

```java
// In TaskUpdateRequest — VULNERABLE
private Instant completedAt;   // attacker can set any past or future date
private boolean pinned;        // any user can pin their own task — no role check
```

The service blindly copies it:

```java
task.setCompletedAt(request.getCompletedAt());   // no server-side enforcement
task.setPinned(request.isPinned());              // no role check
```

This is subtler than Form 1 but equally dangerous: the DTO looks controlled, but the sensitive fields are inside it.

### Why is this a security problem?

| Field | What it should mean | What mass assignment lets an attacker do |
|-------|--------------------|-----------------------------------------|
| `id` | Database primary key, assigned by DB | Overwrite an existing record |
| `owner` | Set from authenticated user | Reassign task to another user |
| `createdAt` | Set by server at creation | Backdate task creation |
| `completedAt` | Set by server on status → DONE | Fake a completion timestamp for any date |
| `pinned` | Set only by ADMIN role | Self-promote tasks without admin privileges |

### The fix: separate input DTOs and server-side management

- **Input DTOs** (`TaskCreateRequest`, `TaskUpdateRequest`) contain only the fields a user is allowed to set: `title`, `description`, `status`.
- `completedAt` is never in any request DTO. `TaskService.update()` sets it as a side effect of the `status → DONE` transition and clears it on any other status.
- `pinned` is never in any request DTO. It has a dedicated `PATCH /api/tasks/{id}/pin` endpoint that requires `ADMIN` role.
- The entity is never used as `@RequestBody`.

---

## Relevant files

| File | Role |
|------|------|
| `entity/Task.java` | `completedAt` (server-managed), `pinned` (admin-only) |
| `dto/TaskCreateRequest.java` | `title`, `description` only — no `pinned`, no `completedAt` |
| `dto/TaskUpdateRequest.java` | `title`, `description`, `status` only |
| `dto/TaskPinRequest.java` | `{ pinned: boolean }` — body for the admin pin endpoint |
| `dto/TaskResponse.java` | Exposes `completedAt` and `pinned` in responses |
| `service/TaskService.java` | Manages `completedAt` on status transition; `pin()` is admin-only |
| `controller/TaskController.java` | `PATCH /api/tasks/{id}/pin` — requires `ADMIN` role |

---

## Vulnerable patterns (from the previous commit — do not use)

### Vulnerable TaskCreateRequest

```java
// Adds pinned to the create DTO — any user can self-pin on creation.
public class TaskCreateRequest {
    private String title;
    private String description;
    private boolean pinned = false;  // VULNERABLE
}
```

### Vulnerable TaskUpdateRequest

```java
// Adds completedAt and pinned — user controls both fields directly.
public class TaskUpdateRequest {
    private String title;
    private String description;
    private TaskStatus status;
    private Instant completedAt;  // VULNERABLE — audit tampering
    private boolean pinned;       // VULNERABLE — no role check
}
```

### Vulnerable TaskService (update)

```java
// Blindly copies both fields from the request — no server enforcement.
task.setCompletedAt(request.getCompletedAt());
task.setPinned(request.isPinned());
```

---

## How the fix works

### completedAt — server-managed

`completedAt` does not appear in `TaskCreateRequest` or `TaskUpdateRequest`. The service manages it based on status transitions:

```java
// TaskService.update()
if (request.getStatus() == TaskStatus.DONE && task.getStatus() != TaskStatus.DONE) {
    task.setCompletedAt(Instant.now());      // entering DONE: set server timestamp
} else if (request.getStatus() != TaskStatus.DONE) {
    task.setCompletedAt(null);               // leaving DONE: clear it
}
task.setStatus(request.getStatus());
```

A user cannot supply their own `completedAt`. When they send `{"completedAt":"2020-01-01T00:00:00Z"}` in the request body, Jackson tries to bind it but finds no matching field in `TaskUpdateRequest`, so it silently ignores it (default Jackson behaviour with `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES=false`).

### pinned — dedicated admin endpoint

`pinned` does not appear in any create or update DTO. The only way to change it is via:

```
PATCH /api/tasks/{id}/pin
Body: { "pinned": true }
```

The controller enforces the role at the HTTP layer:

```java
@PreAuthorize("hasRole('ADMIN')")
@PatchMapping("/{id}/pin")
public ResponseEntity<?> pin(@PathVariable Long id, @RequestBody TaskPinRequest request) {
    ...
}
```

The service enforces it again (`@PreAuthorize("hasRole('ADMIN')")`) for defence in depth. A `VIEWER` hitting this endpoint receives **403 Forbidden**.

Note that `pin()` uses `findById` rather than `findByIdAndOwner`: admins can pin any user's task, not just their own.

### Input DTO as whitelist

The input DTOs act as an explicit allowlist. Any field in the request body that does not appear in the DTO is discarded by Jackson before it reaches the service layer. The security guarantee is:

> *If it is not in the input DTO, it cannot be set through that endpoint.*

---

## Step-by-step guide

### Step 1 — Confirm the attack works on the vulnerable commit

Check out the previous commit to run the vulnerable version:

```bash
git stash           # save any local changes
git checkout HEAD~1 # the vulnerable commit
./gradlew bootRun
```

Log in and create a task. Now try to create a pinned task as a regular user:

```bash
curl -b "JSESSIONID=<session>; XSRF-TOKEN=<token>" \
     -H "X-XSRF-TOKEN: <token>" \
     -H "Content-Type: application/json" \
     -d '{"title":"Important","pinned":true}' \
     http://localhost:8080/api/tasks
```

The response shows `"pinned": true` — a regular user successfully pinned their own task without any admin check.

Now try to set a backdated completion time:

```bash
curl -X PUT \
     -b "JSESSIONID=<session>; XSRF-TOKEN=<token>" \
     -H "X-XSRF-TOKEN: <token>" \
     -H "Content-Type: application/json" \
     -d '{"title":"Done task","status":"OPEN","completedAt":"2015-06-15T09:00:00Z"}' \
     http://localhost:8080/api/tasks/<id>
```

The response shows `"completedAt": "2015-06-15T09:00:00Z"` — the user set an arbitrary timestamp in the past, tampering with the audit trail.

Return to the fixed version when done:

```bash
git checkout main
./gradlew bootRun
```

### Step 2 — Verify pinned is ignored on creation

Try the same create request against the fixed version:

```bash
curl -b "JSESSIONID=<session>; XSRF-TOKEN=<token>" \
     -H "X-XSRF-TOKEN: <token>" \
     -H "Content-Type: application/json" \
     -d '{"title":"Important","pinned":true}' \
     http://localhost:8080/api/tasks
```

The response shows `"pinned": false` — the field was discarded because it is not in `TaskCreateRequest`.

### Step 3 — Verify completedAt is ignored on update

```bash
curl -X PUT \
     -b "JSESSIONID=<session>; XSRF-TOKEN=<token>" \
     -H "X-XSRF-TOKEN: <token>" \
     -H "Content-Type: application/json" \
     -d '{"title":"Done task","status":"OPEN","completedAt":"2015-06-15T09:00:00Z"}' \
     http://localhost:8080/api/tasks/<id>
```

The response has no `completedAt` field — discarded by the DTO whitelist.

### Step 4 — Verify completedAt is set by the server on DONE

```bash
curl -X PUT \
     -b "JSESSIONID=<session>; XSRF-TOKEN=<token>" \
     -H "X-XSRF-TOKEN: <token>" \
     -H "Content-Type: application/json" \
     -d '{"title":"Done task","status":"DONE"}' \
     http://localhost:8080/api/tasks/<id>
```

The response now contains `"completedAt": "<current server time>"` — the server set it, not the client.

Update the task to `IN_PROGRESS` and `completedAt` disappears from the response.

### Step 5 — Try to pin as a regular user (403)

```bash
curl -X PATCH \
     -b "JSESSIONID=<session>; XSRF-TOKEN=<token>" \
     -H "X-XSRF-TOKEN: <token>" \
     -H "Content-Type: application/json" \
     -d '{"pinned":true}' \
     http://localhost:8080/api/tasks/<id>/pin
```

Returns **403 Forbidden** — the endpoint requires `ADMIN` role.

### Step 6 — Pin as admin (200)

Log in as an admin user (use the admin panel to promote an account to `ADMIN`), then repeat the pin request. Returns **200** with `"pinned": true`. Check `GET /api/tasks` — the pinned task appears first in the list.

---

## Manual test checklist

- [ ] POST with `pinned:true` → `pinned:false` in response
- [ ] POST with `completedAt` → field absent in response
- [ ] PUT with `completedAt` → field absent in response
- [ ] PUT with `pinned:true` → `pinned:false` in response
- [ ] PUT with `status:DONE` → `completedAt` set to current server time
- [ ] PUT from DONE to IN_PROGRESS → `completedAt` cleared
- [ ] `PATCH /pin` as VIEWER → 403
- [ ] `PATCH /pin` as ADMIN → 200, `pinned:true`
- [ ] `PATCH /pin` for non-existent task → 404
- [ ] `GET /tasks` with one pinned task → pinned task first
- [ ] Run `./gradlew test` → all tests pass

---

## Common mistakes

**1. Using the JPA entity as `@RequestBody`.**  
Every field on the entity becomes settable. This is the most dangerous form of mass assignment: attackers can set `id` (overwrite records), `owner` (take over another user's data), and any audit field. Always use a dedicated input DTO.

**2. Adding server-managed fields to input DTOs "for convenience".**  
`completedAt` was added to `TaskUpdateRequest` so developers could test the feature easily. This is the typical origin of the vulnerability — not malice but laziness. The rule is: if the server controls it, it must not be in the input DTO.

**3. Adding a privileged flag to a general-purpose DTO without a role check.**  
`pinned` in `TaskCreateRequest` meant "any user can pin". The correct pattern is a separate endpoint with an explicit role guard. A field in a shared DTO cannot have per-field role enforcement — the whole request is accepted or rejected together.

**4. Relying on frontend validation.**  
A form that hides the `pinned` checkbox for non-admins provides no security. Any user can craft a raw HTTP request. Server-side enforcement is the only control that matters.

**5. Confusing output DTOs with input DTOs.**  
`TaskResponse` exposes `completedAt` and `pinned` — that is fine. Exposing a field in a response does not mean it should be accepted in a request. They are different objects with different trust levels.

---

## Discussions

**Q1: Jackson ignores unknown fields by default — doesn't that already protect against mass assignment?**

It protects against fields that are not in the DTO at all. But mass assignment does not require the DTO to be the entity. If the developer puts `completedAt` in `TaskUpdateRequest` and the service calls `task.setCompletedAt(request.getCompletedAt())`, the "unknown field" protection never activates — `completedAt` is a known field in the DTO. The danger is fields that are in the DTO but should not be.

**Q2: Why is a dedicated endpoint for `pin` better than a role check inside the update handler?**

A role check inside `update()` would look like:
```java
if (request.isPinned() && !isAdmin(caller)) {
    throw new AccessDeniedException(...);
}
```
This works but has two problems: (a) the field is still in the DTO, so it is visible and tempting in API documentation and client code; (b) a future developer may remove the check thinking it is unnecessary. A dedicated endpoint with `@PreAuthorize("hasRole('ADMIN')")` is self-documenting — the HTTP verb, path, and annotation all signal "this is an admin operation". The field never appears in any general-purpose DTO, so there is no temptation to accidentally use it.

**Q3: The `pin()` service method uses `findById` instead of `findByIdAndOwner`. Is that safe for an admin endpoint?**

Yes. The admin pin operation is intentionally cross-user: an admin promotes any task, not just their own. Using `findByIdAndOwner` would silently return 404 for tasks the admin does not own, making the endpoint confusing and broken. The `@PreAuthorize("hasRole('ADMIN')")` guard is the relevant control here — any authenticated admin can pin any task. This is an intentional design choice, not a missing ownership check.

**Q4: Could an attacker abuse `PATCH /api/tasks/{id}/pin` to enumerate task IDs?**

Only if they are already an ADMIN. Non-admins receive 403 before any database lookup occurs — Spring Security blocks them at the method level. An admin enumerating task IDs is not a threat model this application needs to defend against (admins already have full access via `AdminController`).

**Q5: What is the difference between mass assignment and IDOR?**

IDOR (Insecure Direct Object Reference) is about accessing another user's *existing* resource by guessing its ID — e.g., `GET /api/tasks/42` when you own task 43. Mass assignment is about writing *fields you should not control* on a resource you do own. They are orthogonal: this lab demonstrates mass assignment on the caller's own tasks. The IDOR fix (`findByIdAndOwner`) would not prevent mass assignment, and the mass assignment fix (DTO whitelist) would not prevent IDOR.
