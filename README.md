# Performant Filesystem (Spring Boot)

A hosted file system in the spirit of Google Drive:
- **Files:** upload files and whole folder trees (drag-and-drop or picker), with large uploads resuming after a dropped connection.
- **Organising:** move by drag-and-drop with undo, rename, expand folders in place, trash and restore.
- **Finding and downloading:** search as you type, and download with resumable Range requests.

**Stack:** Java 25, Spring Boot 4.2 (MVC, Security, JDBC, Flyway) and PostgreSQL 16 for metadata, a content-addressed blob store on disk, and a React + Vite web app.

## Run it

```bash
docker compose up --build            # UI + API on http://localhost:8091
```

Development:

```bash
docker compose up -d db              # Postgres on localhost:5434
cd server && ./gradlew bootRun       # API on :8090 (downloads Java 25 on first run)
cd web && npm install && npm run dev # UI on :5173, proxies /api to :8090
cd server && ./gradlew test          # unit + integration tests against a real Postgres (Testcontainers)
```

## How it works

**Metadata and bytes take separate paths.**
- **The folder tree** lives in Postgres as an adjacency list (`nodes.parent_id`), so moving or renaming a folder of any size is a one-row update.
- **File bytes** live on disk under `blobs/ab/cd/<sha256>`: immutable, and deduplicated by content hash with a reference count.

**Uploads** (`uploads/`) use the same protocol for every large file:
1. `POST /api/uploads` reserves quota atomically and preallocates a sparse temp file.
2. `PUT /api/uploads/{id}/chunks/{n}` writes each 8 MB chunk at its offset through a positional `FileChannel`.
   - Chunks can arrive in any order and in parallel.
   - Each needs an exact `Content-Length`, and an optional `Content-Digest: sha-256=:…:` is verified.
   - A failed or corrupted resend un-marks the chunk, so a bad retry can never silently replace good bytes.
3. `GET /api/uploads/{id}` returns the chunks already received, so a dropped connection resumes with only the missing ones.
4. `POST /api/uploads/{id}/complete` hashes the file, then in one transaction:
   - bumps or creates the blob row
   - inserts the node, renaming to `name (1).ext` on a clash
   - turns the reservation into usage
   - moves the temp file into place, or drops it when those bytes are already stored

   It is idempotent.

Files up to 8 MB use `POST /api/files`, which hashes while streaming. Neither path ever buffers a body; with virtual threads on, many concurrent uploads don't exhaust Tomcat's pool.

**Downloads** (`files/`):
- **Range and caching:** single Range requests (206/416), `If-Range`, and ETag/304 with `immutable` caching.
- **Safety:** responses carry `nosniff` and a sandboxing CSP. HTML, SVG and XML are only ever served as `text/plain` or as attachments, so an uploaded file can't run script on our origin.

**Data access** uses Spring's `JdbcClient` with plain SQL and Flyway migrations, not JPA. The tree relies on recursive CTEs, keyset row comparisons, partial unique indexes with `ON CONFLICT`, data-modifying CTEs and advisory locks, all first-class in SQL and awkward in an ORM.

### Concurrency decisions

| Hazard | Guard |
| --- | --- |
| Two crossing moves (A into B, B into A) each pass their cycle check | Per-user advisory lock: structural changes take it exclusively, inserts take it shared |
| A rename hits the sibling-name index mid-transaction | The retry runs in a nested transaction (a savepoint), so the failed UPDATE doesn't abort the rest |
| Two uploads of the same new bytes | `INSERT … ON CONFLICT (hash) DO UPDATE refcount + 1` |
| GC deletes a blob just as an upload starts referencing it | GC unlinks while holding the row lock (`FOR UPDATE SKIP LOCKED`); the upload re-checks the file under the same lock |
| Parallel upload inits overshoot the quota | Space is reserved atomically at init (`used + reserved + size <= quota`) |
| An upload completes into a folder trashed mid-upload | Commit re-validates the parent; the session is cancelled and its reservation released |
| `é` (NFC) vs `é` (NFD, from macOS) | Names are normalised to NFC; a CHECK constraint enforces it |

Background maintenance (`maintenance/`, every minute) does the following; each step is idempotent:
- expires idle uploads
- removes orphaned temp files
- purges trash older than 30 days
- garbage-collects unreferenced blobs

## Security

- **Sessions:** our own, behind Spring Security. The cookie holds a random 256-bit token and the database stores only its SHA-256. The cookie is `HttpOnly`, `SameSite=Lax`, and `Secure` behind HTTPS.
- **Passwords:** Spring Security's delegating encoder (bcrypt, stored with an upgradeable `{bcrypt}` prefix). Login runs the hash even for unknown emails, so timing doesn't reveal which emails have accounts.
- **Ownership:** every lookup is scoped to the owner, so another user's id is a 404, never a 403.

## Tests

`./gradlew test` runs unit tests plus end-to-end tests: the real server on a random port, over real HTTP, against a real Postgres in Testcontainers. Highlights:
- out-of-order, parallel, resumed and corrupted chunk uploads
- concurrent dedup, and the quota race on parallel inits
- ten rounds of concurrent crossing moves, after which every node must still reach the root
- Range, ETag and XSS headers
- GC ordering

CI runs the server build and the web build on every pull request.

## Libraries

Rule from Grand Charter: high-level libraries that ship file-management functions prebuilt are out. Small utility libraries are allowed if listed here with a reason.

| Library | Why |
| --- | --- |
| Spring Boot (Web MVC, Security, JDBC, Validation) | HTTP framework, auth plumbing, database access, request validation |
| Flyway | Database migrations |
| PostgreSQL JDBC driver | Database driver |
| Testcontainers, JUnit 5, AssertJ | Tests against a real Postgres |
| React, Vite, TypeScript | Web app and build |

Everything that does file-system work is written in this repo:
- the chunked upload protocol and positional writes
- content addressing, deduplication and garbage collection
- Range and ETag handling (by hand, not Spring's `ResourceRegion`)
- tree operations and cycle checks
- the folder-drop walker and the virtual list in the web app

## Status

- **Done:** auth, uploads (chunked, resumable, parallel, single-request, folder trees), listing, move/rename/bulk move with undo, trash and restore, downloads with Range, quota, background maintenance, web app, Docker image.
- **Next:** search (ported from the Node version), then content search with Apache Tika, then semantic search with pgvector.
