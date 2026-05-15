# Lab 06 — Secure File Upload

## Learning goals

By the end of this lab you will be able to:

1. Explain why using the original filename as a storage path enables path traversal.
2. Validate file content with magic bytes instead of trusting `Content-Type` headers or file extensions.
3. Store uploaded files outside the webroot and serve them only through an authenticated controller endpoint.
4. Force file downloads with `Content-Disposition: attachment` to prevent inline execution.
5. Apply the same IDOR ownership check pattern (404-for-both) to attachment download and delete endpoints.
6. Configure a file size limit to prevent resource exhaustion.
7. Explain why a private S3 bucket is a required complement to UUID storage keys.
8. Explain why IAM least-privilege is a separate, required layer on top of Block Public Access.

---

## Overview

This lab covers file upload security in two distinct parts:

| Part | Feature | Storage | Security topics |
|------|---------|---------|----------------|
| **Part A** | Task attachments | Local filesystem | Path traversal, magic bytes, webroot access, Content-Disposition, IDOR, size limit |
| **Part B** | Profile avatar | S3 (LocalStack) | Bucket public access, IAM least-privilege, credential isolation |

The two backends run simultaneously. `AttachmentService` is wired to `LocalFileStorageService`; `ProfileService` is wired to `S3FileStorageService`. Part A teaches the fundamentals that apply to any storage backend. Part B adds the cloud-specific risks that only arise with S3.

---

## Part A — Local file upload (task attachments)

### Background

#### Path traversal via malicious filenames

When a server stores a file using the name the client provides, an attacker can submit a filename like `../../etc/passwd` or `../../../webroot/malicious.jsp`. The server resolves the path relative to the upload directory and writes the file to an arbitrary location.

**Fix:** generate a UUID as the storage key; keep the original filename only in the database for display. The storage layer never sees the user-supplied name.

#### Unrestricted file types

Checking the `Content-Type` header (`multipartFile.getContentType()`) is not a security control — the header is supplied by the client and can be set to any string. Checking the file extension is similarly trivial to bypass: rename `malicious.jsp` to `harmless.jpg`.

**Fix:** read the first bytes of the file content and compare them against known magic byte sequences. A `.jsp` file renamed to `.jpg` will not have JPEG magic bytes (`FF D8 FF`), so it is rejected.

#### Direct webroot access

If uploaded files are placed under `src/main/resources/static/`, Spring Boot serves them at a public URL with no authentication. An attacker who knows (or guesses) the filename can download any uploaded file directly.

**Fix:** store files outside the webroot (local `uploads/` directory). Serve them only through an authenticated controller that verifies ownership before returning the file.

#### Missing Content-Disposition

When a browser receives a file with `Content-Type: text/html` and no `Content-Disposition` header, it renders the file inline — executing any JavaScript in the HTML. This allows stored XSS via an uploaded HTML or SVG file even when the file type check is correct.

**Fix:** always set `Content-Disposition: attachment` on the download response. The browser will save the file to disk rather than render it.

#### IDOR on download/delete

If the download or delete endpoint fetches any attachment by ID without checking that it belongs to the requesting user's task, an attacker can access or delete any attachment by guessing or iterating IDs.

**Fix:** `findByIdAndTask(id, task)` — where `task` is already verified to belong to the current user. Non-owner gets 404 for both "not found" and "wrong owner", preventing ID enumeration.

#### No file size limit

Without a size limit, a single upload can exhaust disk space or heap memory. Spring Boot's multipart limit and an application-level pre-check together cap the upload at 5 MB.

---

### Relevant files (Part A)

| File | Role |
|------|------|
| `entity/Attachment.java` | `id`, `task`, `originalFilename`, `storageKey`, `contentType`, `fileSize`, `uploadedAt` |
| `repository/AttachmentRepository.java` | `findByTask`, `findByIdAndTask` |
| `dto/AttachmentResponse.java` | Exposed fields — **no `storageKey`** |
| `service/FileTypeValidator.java` | Magic bytes check — shared by both parts |
| `service/FileStorageService.java` | Interface: `store`, `load`, `delete` |
| `service/LocalFileStorageService.java` | Local filesystem backend — used by task attachments |
| `service/AttachmentService.java` | Business logic, ownership checks |
| `controller/AttachmentController.java` | `POST/GET/GET/{id}/DELETE /api/tasks/{taskId}/attachments` |
| `src/main/resources/static/js/attachments.js` | Attachment UI (upload, list, delete, download link) |

---

### Vulnerable patterns (do not use)

#### (a) Original filename as storage path

```java
// VULNERABLE — path traversal
String filename = file.getOriginalFilename();     // attacker controls this
Path dest = uploadDir.resolve(filename);          // resolves ../../etc/passwd
Files.copy(file.getInputStream(), dest);
```

#### (b) Trusting the Content-Type header

```java
// VULNERABLE — client-controlled, not a security boundary
String type = file.getContentType();   // returns whatever the client sent
if (!type.startsWith("image/")) {
    throw new BadTypeException(...);
}
// A .jsp file with Content-Type: image/jpeg passes this check.
```

#### (c) Storing files in the webroot

```java
// VULNERABLE — every uploaded file is publicly accessible at /uploads/<filename>
Path dest = Paths.get("src/main/resources/static/uploads").resolve(storageKey);
```

#### (d) Serving without Content-Disposition

```java
// VULNERABLE — browser renders HTML/SVG inline, executing any embedded scripts
return ResponseEntity.ok()
    .contentType(MediaType.TEXT_HTML)    // no Content-Disposition
    .body(new InputStreamResource(stream));
```

---

### How the fix works (Part A)

#### UUID storage key

```java
// AttachmentService.upload()
String storageKey = UUID.randomUUID().toString();
String safeName = Paths.get(rawName).getFileName().toString(); // display only
fileStorageService.store(new ByteArrayInputStream(bytes), storageKey, detectedType, bytes.length);
```

- `storageKey` is a random UUID — no path components, no file extension.
- `safeName` strips path separators but is only stored in the database for display; it never influences where the file is written.

#### Magic bytes validation

```java
// FileTypeValidator.validate(byte[])
// PNG: 89 50 4E 47 0D 0A 1A 0A
if (bytes.length >= 8
        && (bytes[0] & 0xFF) == 0x89
        && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47 ...) {
    return "image/png";
}
// ... JPEG, GIF, WebP, PDF ...
throw new InvalidFileTypeException("File type not allowed. Accepted: PNG, JPEG, GIF, WebP, PDF");
```

`MultipartFile.getBytes()` buffers the full file content before the check. A `.jsp` file renamed to `.jpg` does not start with `FF D8 FF`, so it is rejected regardless of file extension or `Content-Type` header.

#### Outside webroot + authenticated download

`LocalFileStorageService` writes to `${upload.local.directory:uploads}` (relative to the working directory, outside the static resource path). There is no URL that maps to that directory.

Files are only accessible via `GET /api/tasks/{taskId}/attachments/{id}`, which:
1. Requires authentication.
2. Verifies task ownership before loading the file.
3. Sets `Content-Disposition: attachment`.

#### Content-Disposition

```java
return ResponseEntity.ok()
    .contentType(MediaType.parseMediaType(dl.contentType()))
    .contentLength(dl.fileSize())
    .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + safeFilename + "\"")
    .body(new InputStreamResource(dl.inputStream()));
```

`attachment` forces a download; the browser never renders the file inline.

---

### Step-by-step guide (Part A)

#### Step 1 — Upload a valid PNG

Start the app (see README). Log in and open Tasks. Click **Attachments** on any task row.

Select a `.png` file and click **Upload**. You should see the attachment appear in the list.

In `uploads/` (the local storage directory), verify the file is stored with a UUID name and no extension:

```
uploads/
  3f4a1b2c-...  ← UUID, no extension
```

#### Step 2 — Attempt path traversal via filename

Use curl to submit a file with a malicious name:

```bash
curl -b "JSESSIONID=<your-session>; XSRF-TOKEN=<token>" \
     -H "X-XSRF-TOKEN: <token>" \
     -F "file=@photo.png;filename=../../evil.txt" \
     http://localhost:8080/api/tasks/<taskId>/attachments
```

Inspect the `uploads/` directory — the file is stored as a UUID, not as `../../evil.txt`. The original filename is sanitized and stored only in the database for display.

#### Step 3 — Attempt to upload a disallowed file type

Rename a text file or `.jsp` file to `.jpg`:

```bash
echo '<%@ page %><% Runtime.getRuntime().exec("id"); %>' > payload.jsp
mv payload.jsp payload.jpg
```

Try to upload `payload.jpg`. The server reads the magic bytes, finds no JPEG signature (`FF D8 FF`), and returns **422**:

```json
{"error": "File type not allowed. Accepted: PNG, JPEG, GIF, WebP, PDF"}
```

The `Content-Type: image/jpeg` header from the browser's form submission is ignored.

#### Step 4 — Confirm the magic bytes check catches a renamed Java class

A Java `.class` file starts with `CA FE BA BE`. Rename one:

```bash
cp MyClass.class fake.png
```

Try to upload `fake.png`. The server reads `CA FE BA BE`, finds no PNG signature, and rejects it with 422.

#### Step 5 — Verify download requires authentication and ownership

Download one of Alice's attachments while logged in as Alice — succeeds, file is downloaded.

Try the same URL while unauthenticated — returns **401**.

Log in as Bob and request Alice's attachment URL — returns **404** (same response for "not found" and "wrong owner").

#### Step 6 — Confirm Content-Disposition: attachment

Download an attachment with curl:

```bash
curl -I -b "JSESSIONID=..." \
     http://localhost:8080/api/tasks/<taskId>/attachments/<attachId>
```

The response includes:

```
Content-Disposition: attachment; filename="photo.png"
```

Without this header, a browser would render an uploaded HTML or SVG file inline.

---

## Part B — Cloud file upload (profile avatar, S3)

### Background

#### Why S3 instead of local storage

Profile avatars are stored in S3 rather than the local filesystem. This introduces a new class of risk: the storage layer is now a network service with its own access control model, separate from the application's authentication. Fixing the application is not enough — the bucket itself must also be configured securely.

#### S3 bucket public access

Storing a file behind an authenticated endpoint protects it from direct HTTP access — **but only if the S3 bucket itself is also private**. If the bucket allows public reads, any object can be fetched directly using the storage URL, completely bypassing the application's authentication and ownership checks:

```
# Anyone can fetch this if the bucket is public:
http://s3.amazonaws.com/securetask-uploads/4d032540-e454-424b-afdc-23c7eef6195e
```

The UUID key makes the URL hard to *guess*, but it is not a secret: it appears in server access logs, browser history, `Referer` headers, and CDN logs. Obscurity is not access control.

**Fix:** enable Block Public Access on the bucket. All reads must go through the application endpoint (`/api/profile/avatar`) which enforces authentication. LocalStack does not enforce bucket ACLs by default, so direct URL access works locally — this is intentional for Step 8.

#### IAM least-privilege and credential isolation

Block Public Access stops anonymous requests. It does not stop someone who has valid AWS credentials with `s3:GetObject` permission — they can call the S3 API directly using the SDK and bypass the application's ownership checks entirely.

The defences at this layer are:

- **Credential isolation:** AWS credentials (or an IAM role) live only on the application server. End users never receive them. The browser communicates with the app API, not with S3 directly.
- **Least-privilege policy:** only the app's IAM identity has `s3:PutObject`, `s3:GetObject`, and `s3:DeleteObject` on `securetask-uploads/*`. No other user, role, or service has access.
- **IAM roles over static keys:** in production the app runs on EC2/ECS/Lambda and receives temporary credentials automatically via the instance metadata service. There is no long-lived access key to steal from a config file or environment variable.

LocalStack supports IAM but does not enforce it by default. Setting `ENFORCE_IAM=1` enables policy evaluation so this can be simulated locally (see Step 9).

---

### Relevant files (Part B)

| File | Role |
|------|------|
| `entity/User.java` | `avatarKey` (UUID), `avatarContentType` (magic-detected) |
| `dto/ProfileResponse.java` | `hasAvatar`, `storageBackend` — no `avatarKey` |
| `service/FileTypeValidator.java` | Magic bytes check — shared with Part A |
| `service/S3FileStorageService.java` | S3 backend — used by profile avatar |
| `service/ProfileService.java` | Avatar upload, download, delete |
| `controller/ProfileController.java` | `GET/POST /api/profile/avatar`, `DELETE /api/profile/avatar` |
| `config/S3ClientConfig.java` | `S3Client` bean — endpoint override for LocalStack |
| `localstack/init/02-create-s3-bucket.sh` | Creates `securetask-uploads` bucket on LocalStack startup |
| `src/main/resources/static/profile.html` | Profile page with avatar upload UI |

---

### Step-by-step guide (Part B)

#### Step 7 — Upload a profile avatar to S3

Make sure LocalStack is running (`docker compose up -d localstack`) and start the app with:

```bash
AWS_REGION=us-east-1 \
AWS_ACCESS_KEY_ID=test \
AWS_SECRET_ACCESS_KEY=test \
SECRETS_MANAGER_ENDPOINT=http://localhost:4566 \
S3_ENDPOINT=http://localhost:4566 \
DB_SECRET_NAME=securetask/db \
./gradlew bootRun
```

Log in and open the Profile page. Upload a PNG image as your avatar.

List the bucket contents to see the stored object:

```bash
docker exec securetask_java-localstack-1 awslocal s3 ls s3://securetask-uploads --recursive
```

The file appears as a UUID with no extension — same principle as local storage, different backend.

#### Step 8 — Observe direct S3 access (LocalStack only)

Find the UUID from the listing above, then fetch it directly with no session cookie:

```
http://localhost:4566/securetask-uploads/<uuid>
```

The file downloads immediately. LocalStack does not enforce bucket ACLs, so this works locally. On real AWS with a public bucket, the same URL would work from any browser, anywhere.

**Key observation:** the UUID is random and long, but it is not a secret. It appears in:
- The application's server access log (request path)
- The browser's download history
- Any `Referer` header sent when the avatar image is embedded on a page

A private bucket (`Block Public Access` enabled) is the only control that closes this. With a private bucket, the direct URL returns 403 — the only way to get the file is through `/api/profile/avatar`, which requires authentication.

#### Step 9 — Simulate IAM enforcement (LocalStack)

This step demonstrates that Block Public Access + IAM policy together prevent SDK-level access from unauthorised credentials.

**9a. Enable IAM enforcement**

Add `ENFORCE_IAM: "1"` to the `localstack` service in `docker-compose.yml`:

```yaml
environment:
  SERVICES: secretsmanager,s3
  ENFORCE_IAM: "1"
```

Recreate the container so the setting takes effect:

```bash
docker compose stop localstack && docker compose up -d localstack
```

**9b. Enable Block Public Access and create a least-privilege user**

Add these lines to `localstack/init/02-create-s3-bucket.sh` after the `s3 mb` command:

```bash
# Block all public access
awslocal s3api put-public-access-block \
  --bucket securetask-uploads \
  --public-access-block-configuration \
    "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true"

# Create a dedicated IAM user for the app
awslocal iam create-user --user-name securetask-app

# Grant only the three operations the app needs, on this bucket only
awslocal iam put-user-policy \
  --user-name securetask-app \
  --policy-name securetask-s3 \
  --policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::securetask-uploads/*"
    }]
  }'

# Create access key for the app user — note the output
awslocal iam create-access-key --user-name securetask-app
```

Use the printed `AccessKeyId` and `SecretAccessKey` when running the app instead of `test/test`:

```bash
AWS_ACCESS_KEY_ID=<printed-key> AWS_SECRET_ACCESS_KEY=<printed-secret> \
  SECRETS_MANAGER_ENDPOINT=http://localhost:4566 \
  S3_ENDPOINT=http://localhost:4566 \
  DB_SECRET_NAME=securetask/db \
  ./gradlew bootRun
```

**9c. Verify the controls**

Direct URL — blocked by Block Public Access:
```bash
curl -v http://localhost:4566/securetask-uploads/<uuid>
# → 403 Forbidden
```

SDK call with the wrong credentials — blocked by IAM:
```bash
AWS_ACCESS_KEY_ID=wrong AWS_SECRET_ACCESS_KEY=wrong \
  aws --endpoint-url=http://localhost:4566 \
  s3 cp s3://securetask-uploads/<uuid> /tmp/stolen
# → 403 Access Denied
```

SDK call with the correct app credentials — allowed:
```bash
AWS_ACCESS_KEY_ID=<app-key> AWS_SECRET_ACCESS_KEY=<app-secret> \
  aws --endpoint-url=http://localhost:4566 \
  s3 cp s3://securetask-uploads/<uuid> /tmp/file
# → download: s3://securetask-uploads/<uuid> to /tmp/file
```

App API call — still works end-to-end:
```bash
curl -b "JSESSIONID=<session>" http://localhost:8080/api/profile/avatar
# → 200 OK with avatar bytes
```

---

## Manual test checklist

**Part A — Local (task attachments)**
- [ ] Upload valid PNG → 201, stored as UUID in `uploads/`
- [ ] Upload renamed `.jsp` as `.jpg` → 422 (magic bytes mismatch)
- [ ] Upload with path-traversal filename → 201, storage key is UUID (no traversal)
- [ ] Download own attachment → 200 + `Content-Disposition: attachment`
- [ ] Download another user's attachment → 404
- [ ] Delete own attachment → 204
- [ ] Delete another user's attachment → 404
- [ ] Unauthenticated upload → 401

**Part B — S3 (profile avatar)**
- [ ] Upload PNG avatar → 200, object appears in `securetask-uploads` bucket as UUID
- [ ] Fetch object directly via LocalStack URL → downloads without auth (bucket is public by default)
- [ ] Enable Block Public Access → direct URL returns 403
- [ ] Enable `ENFORCE_IAM=1` + wrong credentials → SDK call returns 403
- [ ] App API (`/api/profile/avatar`) still works after IAM enforcement enabled

- [ ] Run `./gradlew test` → all tests pass

---

## Expected results

| Action | HTTP status | Reason |
|--------|-------------|--------|
| Upload valid PNG to task (authenticated, own task) | 201 | Normal |
| Upload renamed JSP as JPG | 422 | Magic bytes mismatch |
| Upload with `../../evil` filename | 201 | Storage key is UUID — traversal defused |
| Upload to another user's task | 404 | Task not found for this owner |
| Upload with no file | 422 | Empty file rejected |
| Download own attachment | 200 + `Content-Disposition: attachment` | Normal |
| Download another user's attachment | 404 | Ownership check |
| Unauthenticated download | 401 | Spring Security |
| Delete own attachment | 204 | Normal |
| Delete another user's attachment | 404 | Ownership check |
| Direct S3 URL (public bucket) | 200 | No auth required — bucket is open |
| Direct S3 URL (Block Public Access on) | 403 | Bucket-level control |
| SDK call with wrong credentials (IAM enforced) | 403 | IAM policy |

---

## Common mistakes

**1. Using the original filename as the storage path.**  
`multipartFile.getOriginalFilename()` is attacker-controlled. A filename of `../../etc/passwd` writes outside the upload directory. Always generate a UUID for the storage key.

**2. Trusting `multipartFile.getContentType()`.**  
This value comes from the HTTP `Content-Type` header, which the browser (or attacker) supplies. It is not derived from the file content. Always validate magic bytes.

**3. Storing uploaded files under `src/main/resources/static/`.**  
Spring Boot serves everything in `static/` at a public URL, bypassing authentication. Store files outside the webroot and serve them through an authenticated endpoint.

**4. Omitting `Content-Disposition: attachment`.**  
Without this header, a browser will render an uploaded HTML or SVG file inline in the same origin, making any embedded JavaScript execute as a stored XSS payload.

**5. Skipping the size limit.**  
Even with a valid file type, an attacker can upload a 2 GB file. Both the Spring multipart limit (`spring.servlet.multipart.max-file-size`) and `file.isEmpty()` / `file.getBytes()` length check defend against this.

**6. Exposing `storageKey` in the API response.**  
The UUID storage key is an internal detail. If leaked, it reveals the internal storage structure and could be used to enumerate files if the storage layer is misconfigured. `AttachmentResponse` and `ProfileResponse` both omit the storage key deliberately.

**7. Assuming the UUID storage key is a secret.**  
A UUID key is unguessable, but it is not secret: it is logged, cached, and referenced in browser history every time the file is accessed. A public S3 bucket combined with a leaked UUID URL exposes the file to anyone. The UUID defends against *enumeration*; it does not defend against *disclosure* after the URL is observed. A private bucket is required for confidentiality.

**8. Not cleaning up storage files when a task is deleted.**  
The `@OneToMany(cascade=ALL, orphanRemoval=true)` on `Task.attachments` cleans up `Attachment` records from the database when a task is deleted, but does not clean up the physical files from local storage or S3. In production, `TaskService.delete()` would need to call `fileStorageService.delete()` for each attachment's storage key before deleting the task.

---

## Discussions

**Q1: What is the difference between a path traversal attack and a filename injection attack? Can they both be fixed by the same countermeasure?**

Path traversal exploits `../` sequences in a user-supplied filename to escape the intended storage directory and write (or read) arbitrary files on the filesystem. Filename injection is broader — it includes cases where the original filename is used in a command, log entry, or HTTP header where special characters cause unintended behaviour (e.g., a newline in a `Content-Disposition` header injects a second header). The UUID storage key fixes path traversal completely because the filename never reaches the filesystem. For the download header, stripping `"`, `\n`, and `\r` from `safeFilename` addresses the header injection variant.

**Q2: Why is checking the file extension not sufficient even if you maintain a strict allowlist?**

File extensions are metadata attached to the filename by the client. A user (or attacker) can rename any file to have an allowed extension. Extensions only describe intent, not content. Magic bytes describe content: they are the first bytes the file format specification mandates, and legitimate files produced by any standard tool will have them. A malicious file with the wrong content will not have the correct magic bytes regardless of its extension.

**Q3: The magic bytes check looks at the first 8–12 bytes. Could an attacker craft a file that passes the check but executes malicious code?**

A polyglot file starts with valid magic bytes and contains a secondary payload that a different parser interprets as executable code. A classic example is a valid JPEG that is also valid JavaScript (a JPEG/JS polyglot). However, the two defences in this lab together prevent execution: (a) the file is stored outside the webroot and served with `Content-Disposition: attachment`, so the browser will not execute it; (b) even if the file reaches a user's disk and they open it, it executes as a JPEG (or PDF), not as JavaScript. The primary risk scenario — inline execution in the browser — is closed by `Content-Disposition`.

**Q4: Why does the download endpoint use `Content-Disposition: attachment` even for image files? Won't that make a poor user experience for inline preview?**

For images, inline rendering is low-risk because browsers apply their image renderer, not the HTML/JS engine. However, maintaining a uniform `attachment` policy for all types is simpler and safer: you don't have to reason about which content types are safe to render inline across every browser version, and a future allowed type (e.g., SVG, which supports embedded scripts) won't accidentally be served inline. A production app that needs inline preview should either serve images from a separate isolated origin (so any XSS is confined to a throwaway origin) or use a strict `Content-Security-Policy` on the download endpoint.

**Q5: The `@OneToMany(cascade=ALL)` on `Task.attachments` removes `Attachment` records from the database when a task is deleted. Why doesn't it also clean up the files in local storage or S3?**

JPA cascades operate on entity state managed by the persistence context. They call `attachmentRepository.delete()` for each child, which removes the database row. The `fileStorageService.delete()` call is application-level logic that JPA has no knowledge of. To clean up physical files on task deletion, `TaskService.delete()` would need to first list the task's attachments, call `fileStorageService.delete()` for each storage key, and then delete the task (which cascades the DB deletes). This is left as a known limitation in this lab — the focus is on upload security, not full lifecycle management.

**Q6: The app runs two `FileStorageService` implementations simultaneously — one local, one S3. How does Spring know which one to inject where?**

Both `LocalFileStorageService` and `S3FileStorageService` implement `FileStorageService`. Spring cannot resolve the dependency by type alone when two beans of the same type exist. Each service is declared with an explicit bean name (`@Service("localFileStorageService")` and `@Service("s3FileStorageService")`), and each injection site uses `@Qualifier` to name the one it needs: `AttachmentService` requests `"localFileStorageService"` and `ProfileService` requests `"s3FileStorageService"`. This pattern deliberately keeps both backends active at the same time so that task attachments (local) and profile avatars (S3) demonstrate different storage characteristics in the same running application — a teaching contrast that a single-backend design would not provide.

**Q7: In the test suite, `@MockBean FileStorageService` replaces the real storage implementation. What would break if you instead tested with the real `LocalFileStorageService`?**

Integration tests run in parallel and the upload directory would be shared between test classes and test methods. Files written by one test could collide with or outlive another test, causing false positives or failures on re-run. Tests would also leave state on the filesystem after the suite finishes, which requires cleanup logic. `@MockBean` gives you a completely controlled in-memory stub: `store()` does nothing, `load()` returns whatever you configure, and `delete()` does nothing — no disk I/O, no cleanup, no ordering dependency between tests.

**Q8: The UUID key prevents attackers from *guessing* a file URL. Is that enough to keep uploaded files confidential?**

No. Unguessability prevents *enumeration* — an attacker cannot iterate IDs to harvest files. But once a URL is observed (server log, browser history, a `Referer` header sent when an avatar is embedded on a page, a CDN access log), anyone who has it can fetch the file if the bucket is public. The UUID provides no confidentiality guarantee after first access.

The correct model is defence in depth:

| Layer | Control | What it prevents |
|-------|---------|-----------------|
| Storage key | UUID (random, no extension) | Enumeration, path traversal |
| Bucket ACL | Block Public Access enabled | Direct URL access regardless of key knowledge |
| Application endpoint | `isAuthenticated()` + ownership check | Authenticated-but-unauthorised access |

In this lab the bucket is left public so Step 8 is observable. In production all three layers are required.

**Q9: Block Public Access stops anonymous requests. Can someone with the AWS SDK still bypass the app and access files directly?**

Yes — if they have valid AWS credentials with `s3:GetObject` on the bucket they can call the S3 API directly, fetch any object by UUID, and completely bypass the app's ownership checks. The app never runs; Spring Security never runs; the `findByIdAndTask` check never runs.

This is why IAM is a separate, required layer:

- **Block Public Access** closes the anonymous threat: no credentials = no access.
- **IAM least-privilege** closes the authenticated-SDK threat: the only identity with `s3:GetObject` is the app's own IAM role/user. An attacker would need to compromise the server to obtain those credentials.
- **No static keys in production** closes the credential-theft threat: an EC2/ECS instance role issues temporary credentials via the metadata service; there is no long-lived secret key stored in a file or environment variable that could leak.
- **App ownership check** closes the authenticated-app-user threat: even a legitimate logged-in user cannot read another user's file through the API.

Each layer addresses a different attacker capability. Removing any one of them leaves a gap that the others do not cover.
