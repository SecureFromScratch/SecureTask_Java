# Lab 05 — Input Validation and XSS Prevention

## Learning goals

By the end of this lab you should be able to:

1. Explain what stored XSS is and how it differs from reflected XSS and DOM-based XSS.
2. Identify code that renders user-controlled data unsafely with `innerHTML`.
3. Explain why client-side validation is not a security control.
4. Apply `@NotBlank`, `@Size`, and `@NotNull` Jakarta constraints on request DTOs.
5. Trace how Spring's `@Valid` annotation triggers bean validation and how `GlobalExceptionHandler` formats the 400 response.
6. Explain the difference between `textContent` (safe) and `innerHTML` (dangerous for user data).
7. Describe what OWASP A03:2021 — Injection covers beyond SQL injection.

---

## Background

### What is XSS?

Cross-Site Scripting (XSS) is a vulnerability where an attacker injects malicious scripts into content that other users' browsers execute.

There are three types:

| Type | How it works | Example |
|------|-------------|---------|
| **Stored XSS** | Payload is saved in the database and served to every user who views the content | Task title containing `<script>` stored and later rendered with `innerHTML` |
| **Reflected XSS** | Payload is in the request and immediately echoed back in the response | Search term `?q=<script>` included unsanitized in the HTML response |
| **DOM-based XSS** | Payload is processed and inserted into the DOM entirely in the browser without a server round-trip | `location.hash` read and passed directly to `innerHTML` |

This lab focuses on **stored XSS** — the most impactful variant, because a single successful injection affects every user who views the page.

### Why XSS matters

A script running in the context of your application can:
- Steal session cookies (if not `HttpOnly`)
- Make authenticated API requests on behalf of the victim
- Redirect users to phishing pages
- Harvest credentials typed into forms

Lab 01 set `JSESSIONID` to `HttpOnly=true`, which prevents XSS from stealing the session cookie directly — but XSS can still make authenticated fetch requests, since the browser includes the cookie automatically.

### Input validation as the first boundary

Server-side input validation rejects data that violates structural rules before it reaches the database:
- Empty or whitespace-only titles
- Strings exceeding the column's maximum length
- Missing required fields
- Wrong types (a string where an enum is expected)

Validation does not prevent XSS — HTML tags are structurally valid text. The XSS fix is **output encoding**, not input filtering. Both are necessary.

### Output encoding as the second boundary

When displaying user-controlled data in a browser, the choice of API determines whether a script payload executes:

```javascript
// VULNERABLE — browser parses the string as HTML
element.innerHTML = task.title;
// If title = '<img src=x onerror="alert(1)">', the onerror handler fires.

// SAFE — browser displays the string as literal text
element.textContent = task.title;
// If title = '<img src=x onerror="alert(1)">', the text appears literally.
```

`textContent` treats the value as a plain string. There is no parsing step, so there is nothing to exploit.

### OWASP A03:2021 — Injection

Injection covers any vulnerability where untrusted input is interpreted as code or a command by an interpreter. This includes:

- XSS (HTML/JavaScript interpreted by the browser)
- SQL injection (SQL interpreted by the database)
- OS command injection
- LDAP injection
- Template injection

The unifying principle: **data and code must be kept separate**. `textContent` keeps data out of the HTML parser. Parameterized queries keep data out of the SQL parser.

---

## Relevant files

| File | Role |
|------|------|
| `api/src/main/java/com/securetask/entity/Task.java` | JPA entity: id, owner, title, description, status, createdAt, updatedAt |
| `api/src/main/java/com/securetask/entity/TaskStatus.java` | Enum: OPEN, IN_PROGRESS, DONE |
| `api/src/main/java/com/securetask/repository/TaskRepository.java` | `findByOwner`, `findByIdAndOwner` |
| `api/src/main/java/com/securetask/dto/TaskCreateRequest.java` | title `@NotBlank @Size(max=200)`, description `@Size(max=2000)` |
| `api/src/main/java/com/securetask/dto/TaskUpdateRequest.java` | title `@NotBlank @Size(max=200)`, description `@Size(max=2000)`, status `@NotNull` |
| `api/src/main/java/com/securetask/dto/TaskResponse.java` | Safe response — no internal fields |
| `api/src/main/java/com/securetask/service/TaskService.java` | Business logic; `@PreAuthorize` on every method |
| `api/src/main/java/com/securetask/controller/TaskController.java` | REST endpoints; `@Valid` on every request body |
| `api/src/main/java/com/securetask/controller/GlobalExceptionHandler.java` | Maps `MethodArgumentNotValidException` → 400 `{"errors":[…]}` |
| `frontend/tasks.html` | Task management page |
| `frontend/js/tasks.js` | Page logic — `textContent` throughout |
| `frontend/js/api.js` | `api.tasks.{list, create, update, delete}` namespace |

---

## The vulnerable patterns

### 1. No server-side validation

Without `@Valid`, the controller accepts anything:

```java
// WRONG — title can be null, empty, or megabytes of text
@PostMapping
public ResponseEntity<TaskResponse> create(
        @RequestBody TaskCreateRequest request,
        Authentication authentication) {
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(taskService.create(request, authentication.getName()));
}
```

A client can send `{"title": ""}` or `{"title": "x".repeat(10_000_000)}` and both will be accepted and saved.

### 2. Rendering user data with `innerHTML`

In the browser, the dangerous pattern looks like this:

```javascript
// WRONG — renders HTML tags in task.title
function buildRow(task) {
    const tr = document.createElement("tr");
    tr.innerHTML = `<td>${task.title}</td><td>${task.description}</td>`;
    return tr;
}
```

If a task title contains `<img src=x onerror="alert(document.cookie)">`, the browser parses the `onerror` attribute as JavaScript and executes it. An attacker who can create tasks can inject scripts that run in every other user's browser.

---

## How the fix works

### `@Valid` + Jakarta Bean Validation

Spring calls the validator before the method body runs:

```java
@PostMapping
public ResponseEntity<TaskResponse> create(
        @Valid @RequestBody TaskCreateRequest request,  // ← @Valid triggers validation
        Authentication authentication) { … }
```

The constraints live on the DTO:

```java
public class TaskCreateRequest {
    @NotBlank               // rejects null AND whitespace-only strings
    @Size(max = 200)        // rejects strings longer than 200 characters
    private String title;

    @Size(max = 2000)
    private String description;   // nullable — no @NotBlank, description is optional
}
```

If any constraint fails, Spring throws `MethodArgumentNotValidException` before the method body executes.

### `GlobalExceptionHandler` — clean 400 responses

`GlobalExceptionHandler` catches the validation exception and formats a consistent error body:

```java
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors()
                .stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest().body(Map.of("errors", errors));
    }
}
```

Response for a blank title:
```json
HTTP/1.1 400 Bad Request
{"errors": ["title: must not be blank"]}
```

### `textContent` in the browser

`tasks.js` uses `textContent` for every user-controlled string:

```javascript
// SAFE — browser displays text literally, no parsing
const titleTd = document.createElement("td");
titleTd.textContent = task.title;
```

The comment in `tasks.js` marks the vulnerable alternative:
```javascript
// VULNERABLE pattern (never use): titleTd.innerHTML = task.title
```

---

## Step-by-step guide

### Step 1 — Trace the create flow

Open `TaskController.java`. Find `@Valid @RequestBody TaskCreateRequest request` on the `create()` method. The `@Valid` annotation is what activates bean validation.

Open `TaskCreateRequest.java`. Note the constraint annotations on `title` and `description`.

**Question:** What constraints are on `TaskUpdateRequest.status` and why?

### Step 2 — See validation fire: blank title

```bash
# Get a token:
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"password123"}' | jq -r .accessToken)

# Send a blank title:
curl -s -i -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/tasks \
  -d '{"title":"   "}'
```

Expected:
```json
HTTP/1.1 400 Bad Request
{"errors": ["title: must not be blank"]}
```

The method body never executes — no task is created.

### Step 3 — See size validation fire

```bash
# Title with 201 characters:
curl -s -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/tasks \
  -d "{\"title\":\"$(python3 -c 'print("x"*201)')\"}"
# → 400 {"errors":["title: size must be between 0 and 200"]}
```

### Step 4 — Store an XSS payload

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/tasks \
  -d '{"title":"<img src=x onerror=\"alert(1)\">"}'
# → 201 {"id":1,"title":"<img src=x onerror=\"alert(1)\">","status":"OPEN",...}
```

The server stores the payload as plain text. The JSON response contains it as a string. Open `http://localhost:8081/tasks.html` — the task title appears literally in the table cell. No alert fires. The `textContent` assignment in `tasks.js` treated the angle brackets as text, not HTML.

### Step 5 — "Break it": switch to innerHTML

Open `frontend/js/tasks.js`. Find the line:

```javascript
titleTd.textContent = task.title;
```

Change it to:

```javascript
titleTd.innerHTML = task.title;  // VULNERABLE — do not leave this in place
```

Reload `http://localhost:8081/tasks.html`. The alert fires. The `<img>` tag was parsed as HTML, and the `onerror` attribute executed as JavaScript.

This is stored XSS: one task created by an attacker executes code in every user's browser that views the page.

### Step 6 — Restore `textContent`

Revert the change:

```javascript
titleTd.textContent = task.title;
```

Reload `http://localhost:8081/tasks.html`. The payload is displayed as literal text again. No alert fires.

### Step 7 — Bypass client-side validation with curl

The title input in `tasks.html` has `maxlength="200"` and the Add button checks for an empty title. These are UX conveniences only — they are trivially bypassed by sending a raw HTTP request:

```bash
# No browser involved — HTML maxlength has no effect
curl -s -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/tasks \
  -d '{"title":""}'
# → 400 — server validation still fires
```

The server returns 400 regardless of what the browser form would have allowed.

### Step 8 — Confirm horizontal isolation

Alice and Bob each create tasks. Log in as Alice and call `GET /api/tasks` — Bob's tasks do not appear. `TaskService.listOwn()` uses `findByOwner(owner)` where `owner` is resolved from the JWT token, not from any client-supplied parameter. You can verify with:

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/tasks
```

---

## Manual test checklist

| Test | Expected |
|------|---------|
| Create task (authenticated, valid title) | 201 Created |
| Create task (unauthenticated) | 401 |
| Create task (blank title) | 400 + `{"errors":["title: must not be blank"]}` |
| Create task (title > 200 chars) | 400 |
| Create task (description > 2000 chars) | 400 |
| Create task (XSS payload in title) | 201 — payload stored as text |
| Open `/tasks.html` after XSS payload stored | Payload displayed as literal text, no alert |
| List tasks (authenticated) | 200, only own tasks |
| Update task to IN_PROGRESS | 200, status changed |
| Update another user's task | 404 |
| Delete own task | 204 |
| Delete another user's task | 404 |

---

## Expected results table

| Request | Input | Expected |
|---------|-------|---------|
| `POST /api/tasks` | `{"title":"Fix bug"}` | 201, `status:"OPEN"` |
| `POST /api/tasks` | `{"title":"   "}` | 400, `errors:["title: must not be blank"]` |
| `POST /api/tasks` | `{"title":"x"×201}` | 400, `errors:["title: size must be between 0 and 200"]` |
| `POST /api/tasks` | `{"title":"<script>alert(1)</script>"}` | 201, title stored verbatim |
| `GET /api/tasks` | — | 200, only own tasks |
| `PUT /api/tasks/{id}` | `{"title":"New","status":"DONE"}` | 200, updated response |
| `PUT /api/tasks/{other-id}` | any | 404 |
| `DELETE /api/tasks/{id}` | — | 204 |
| `DELETE /api/tasks/{other-id}` | — | 404 |

---

## Common mistakes

### Trusting client-side validation

HTML attributes like `maxlength`, `required`, and JavaScript form checks prevent accidental invalid input from legitimate users. They do not prevent malicious users from sending raw HTTP requests with `curl` or Burp Suite. Server-side validation with `@Valid` is the only reliable gate.

### Putting `@Valid` only on the service, not on the DTO

`@Valid` on the controller method parameter triggers the constraint annotations declared on the DTO class. Moving validation logic into the service (manually calling `if (request.getTitle() == null || request.getTitle().isBlank())`) works, but it duplicates effort and is easily forgotten. Annotations on the DTO are declarative and checked automatically.

### Using `innerHTML` with user-controlled data

`innerHTML` is appropriate for trusted, developer-authored HTML (e.g., loading a static template). It must never be used with user-supplied strings. Use `textContent` for text, `createElement`/`appendChild` for structure, and `createTextNode` for dynamic text nodes.

### Missing `@NotNull` on enum fields

Without `@NotNull`, a missing or misspelled `status` field in the JSON body deserializes to `null` silently. `@NotNull` on `TaskUpdateRequest.status` causes a 400 before the service runs, rather than a `NullPointerException` or a database constraint violation at commit time.

### Relying on HTML encoding in the backend

Some developers add server-side HTML escaping to stored strings (e.g., converting `<` to `&lt;`). This couples the storage layer to the presentation layer: the same data might need to be rendered in a mobile app, a PDF, or an API response where HTML encoding is wrong. The correct approach is to store raw data and encode at render time — which is exactly what `textContent` does in the browser.

---

## Discussions

**Q1: What is the difference between stored XSS, reflected XSS, and DOM-based XSS?**

In stored XSS the payload is persisted (in a database, a log, or a file) and served to users later. Every user who views the affected content executes the script. The attacker does not need to target individual users — the exploit spreads passively.

In reflected XSS the payload is in the request (a URL parameter, a form field) and is echoed back in the immediate response. The attacker must trick a specific user into clicking a crafted link. The payload is not stored.

In DOM-based XSS the payload travels from the URL or another browser-accessible source (like `location.hash` or `document.referrer`) directly into a DOM manipulation call, without ever reaching the server. The server's response is not involved.

Stored XSS is generally the most impactful because one successful injection can affect all users of a page without further attacker interaction.

**Q2: Why is client-side validation not a security control?**

Client-side validation runs in the user's browser, which the user controls. Any check the browser performs can be bypassed by:
- Sending a raw HTTP request directly (curl, Postman, Burp Suite)
- Disabling JavaScript
- Modifying the form's HTML in DevTools before submitting

Client-side validation is a UX improvement — it gives immediate feedback without a round trip. It is not a security boundary. The server must validate every request independently.

**Q3: Why is `textContent` safe when `innerHTML` is dangerous?**

`innerHTML` passes the assigned string to the browser's HTML parser, which recognizes tags, attributes, and event handlers like `onerror`. The browser executes any JavaScript it finds.

`textContent` sets the text content of the node directly, bypassing the HTML parser entirely. The string is treated as data, not markup. Angle brackets, quotes, and all other special characters are displayed literally. There is nothing for the browser to execute.

**Q4: Why put validation annotations on the DTO rather than checking manually in the service?**

Annotations on the DTO are declarative — they express the contract in one place, co-located with the data class. Spring applies them automatically wherever the DTO is used with `@Valid`. Manual checks in the service method are imperative code that must be written, tested, and maintained separately. They are also easier to forget when adding a new field or a new service method that accepts the same DTO.

**Q5: What does `@NotBlank` check that `@NotNull` does not?**

`@NotNull` only rejects `null`. It accepts an empty string `""` and a whitespace-only string `"   "`.

`@NotBlank` rejects all three: `null`, `""`, and `"   "`. It is the correct annotation for text fields that must contain meaningful content, because a title of `"   "` is semantically empty even though it is not technically null.

**Q6: Why does the server return 400 for a validation failure rather than 422?**

400 Bad Request means the server could not understand the request due to a client error — the input does not conform to what the endpoint expects. A missing or blank required field is a structural error in the request.

422 Unprocessable Entity means the request is syntactically valid but semantically rejected by business rules — as in Lab 04, where a URL like `https://127.0.0.1/` is a valid URL but is rejected by the SSRF guard's policy.

Bean validation failures are structural (the DTO does not satisfy the declared contract), so 400 is appropriate. A business rule rejection (e.g., a task with a duplicate title under a rule that titles must be unique) would be 422.

**Q7: What does OWASP A03:2021 — Injection cover beyond XSS and SQL injection?**

A03:2021 covers any case where untrusted data is sent to an interpreter as part of a command or query. Examples:

| Interpreter | Attack |
|-------------|--------|
| Browser (HTML parser) | XSS |
| Database (SQL parser) | SQL injection |
| OS shell | OS command injection |
| LDAP server | LDAP injection |
| Template engine | Server-Side Template Injection (SSTI) |
| XML parser | XML injection / XXE |
| Expression evaluator | Expression Language Injection |

The common thread is that data supplied by the user is treated as executable code by some interpreter downstream. The defence is always the same: keep data and code structurally separate. `textContent` does this for the browser. Parameterized queries do this for the database.
