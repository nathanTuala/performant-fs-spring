# Performant Filesystem

A Google Drive-like file system built for the Grand Charter take-home: a Java/Spring Boot backend, PostgreSQL, a content-addressed blob store, and a React web app.

What it does:
- **Files:** upload files and whole folder trees (drag-and-drop or picker), with large uploads resuming after a dropped connection.
- **Organising:** move by drag-and-drop with undo, rename, expand folders in place, trash and restore.
- **Finding and downloading:** search names and the text inside files as you type, find files by meaning even when no words match, and download with resumable Range requests.

**Stack:** Java 25, Spring Boot 4.2 (MVC, Security, JDBC, Flyway) and PostgreSQL 16 with pgvector for metadata and search, a content-addressed blob store on disk, and a React + Vite web app.

## Design decisions at a glance

| Decision | Why | Trade-off accepted |
| --- | --- | --- |
| **Metadata in Postgres, bytes on disk by SHA-256** | Moves and renames are one-row updates whatever the folder size; identical bytes are stored once; downloads can be cached forever because a hash never changes | Bytes on one server's disk. `BlobStore` is an interface, so S3 could replace it without touching the protocol |
| **Own chunked, resumable upload protocol** (no tus/Uppy, per the brief) | Parallel, out-of-order and resumable 8 MB chunks, each verified by checksum; quota reserved up front so parallel uploads can't overshoot | More code to own, covered by integration tests (corruption, resume, races) |
| **Plain SQL with `JdbcClient`, not JPA** | The hard parts are SQL features: recursive CTEs, keyset pagination, `ON CONFLICT`, advisory locks, `SKIP LOCKED` | No entity mapping; queries are written by hand |
| **Keyset pagination and a virtualised list** | A 100k-file folder lists in about 5 ms per page, and the browser renders about 33 rows whatever the size | No "jump to page 37" |
| **Correctness under concurrency first** | A file system must never lose or duplicate files: crossing moves, parallel uploads of the same bytes and GC are all guarded (table below) | Per-user locks serialise a single user's structural changes |
| **Search in three layers**: names (prefix and typo-tolerant), contents (PDF and text), meaning (embeddings) | Each answers a different question; keyword results stay exact and paginated, and "related by meaning" is a separate, labelled group, so it's always clear why a file appears | Office files and EPUB aren't read yet |
| **Embedding model runs in-process** (bge-small via ONNX Runtime) | No API key or cost, works offline, and file contents never leave the server. Chosen by measurement: the first model (MiniLM) couldn't separate related from unrelated files | Smaller model than hosted APIs; the 0.42 cut-off was tuned on a small sample |
| **Indexing in the background, queued in the upload's own transaction** | Uploads never wait for PDF parsing; a crash can't lose a queued file; identical files are read once | Search inside a new file lags its upload by a second or two |
| **HTTP caching, no Redis** | Downloads already get ETag and `immutable` caching; hot queries are single-digit milliseconds on indexes. A server cache would add invalidation bugs to moves, trash and undo | Usage stats are computed per request; running totals would be the next step at scale |
| **OpenTelemetry for observability** | The industry standard: traces show each search stage and what it returned, the dashboard shows system health, logs carry trace ids. Export is off unless enabled | The Grafana image is large (2.6 GB, development only) |
| **Sign-in switched off for the demo** | Opens straight to the files. Accounts, sessions and per-user isolation are intact and tested; one setting turns sign-in back on | Everyone shares one local account while it's off |

## Run it

```bash
docker compose up --build            # UI + API on http://localhost:8091
```

Development:

```bash
docker compose up -d db              # Postgres on localhost:5434
cd server && ./gradlew bootRun       # API on :8090 (downloads Java 25 and the 133 MB embedding model on first run)
cd web && npm install && npm run dev # UI on :5173, proxies /api to :8090
cd server && ./gradlew test          # unit + integration tests against a real Postgres (Testcontainers)
```

**Sign-in is off for now:** the app opens straight to the files, and everyone shares one local account. Set `pfs.sign-in-required=true` (or `PFS_SIGN_IN_REQUIRED=true`) to require accounts again. The account system, sessions and per-user isolation are unchanged and still covered by the tests, which run with sign-in on.

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

**Search** (`search/`) parses `quarterly report "exact phrase" type:pdf size:>10mb modified:<2026-01-01 in:"Tax returns"`. Words match as prefixes through a tsvector GIN index, or fuzzily through a trigram index (typos like "quartrly" work). Results are ranked, keyset-paginated, and shown with their folder path.

**Content search** finds words inside PDFs and text files (Markdown, CSV, JSON, source code):
- *What a file is* comes from its bytes, not its name: a `%PDF-` header is read with PDFBox; valid UTF-8 with no NUL bytes is read as text; everything else is skipped.
- *Text is stored once per blob* (`blob_text`, with a GIN-indexed tsvector), so duplicate files are read once. Each upload queues its blob in the same transaction that stores it.
- *A background worker* (`ContentIndexer`) is woken after each upload commits, with a 30 s poll as backup. It claims rows with a short `SKIP LOCKED` statement and extracts with no lock held, so a slow PDF never blocks an upload or garbage collection. A crashed worker's claim is retried; a file that fails three times is marked `failed`.
- *Files are untrusted*, so extraction is capped: 64 MB per PDF, 1,000 pages, 500,000 characters kept, 20 s.
- *Querying:* every word must be in the name, or every word in the contents. The two are separate index lookups, unioned, so both stay indexed. Name matches rank first. Results found by contents carry a snippet built from a ~300-character window around the first match, for the returned page only. Highlights are character ranges, never markup.

**Semantic search** finds files by meaning: "what if my internet cuts out while sending a big file" finds the design doc's section on resumable uploads, though they share no words.
- *Model:* BAAI/bge-small-en-v1.5 (384 dimensions), run in-process with ONNX Runtime on the CPU. No API key, no cost, and file contents never leave the server. `./gradlew downloadModel` fetches it and checks a pinned SHA-256. The WordPiece tokenizer is ours, tested id-for-id against Hugging Face's. Queries carry bge's retrieval instruction; passages don't. It replaced all-MiniLM-L6-v2 after "energy transformation" missed notes on photosynthesis: MiniLM scored some on-topic pairs worse than unrelated ones, so no threshold worked.
- *Indexing:* the same background worker splits each file's text into 120-word passages overlapping by 30 (at most 100 per file) and embeds them. Passages go into `blob_chunks` (pgvector, HNSW cosine index), keyed by blob like the text, and are saved in the same transaction as the text.
- *Querying:* the query is embedded (a few ms) and matched to the nearest passages. The scan keeps only the searcher's own live files, and pgvector's iterative scan keeps going so that filter can't starve the results. The same `type:` / `size:` / `modified:` / `in:` filters apply. A file counts as related when its best passage is within cosine distance 0.42, a cut measured on real files: ten on-topic queries scored 0.15–0.38, unrelated ones 0.46 and up.
- *Results:* keyword results stay exact and paginated. The first page also carries up to 8 files **related by meaning** that keyword search didn't already find, each with the passage that matched. They're a separate group, not blended into the ranking, so it's always clear why a file is there.

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

## Observability

Every request is traced with OpenTelemetry (Spring Boot's starter), and the trace id comes back in an `X-Trace-Id` header. Nothing is exported unless you turn it on, so the app runs fine without a collector.

```bash
docker compose --profile observability up -d lgtm   # Grafana + Prometheus + Tempo + Loki (2.6 GB image, dev only)
cd server && SPRING_PROFILES_ACTIVE=otel ./gradlew bootRun
open http://localhost:3001                          # Dashboards → Performant FS
```

**Look up a search you just made.** Take the `X-Trace-Id` from the browser's network tab and paste it into Grafana → Explore → Tempo. Or search by what was typed, with TraceQL `{ span.query = "energy transformation" }`. Or click a row in the dashboard's *Recent searches* table. The trace shows each stage with its time and what it returned:

```
http get /api/search          19 ms
├─ pfs.search.keyword          5 ms   query="how do plants make food" results=0
└─ pfs.search.semantic        11 ms   results=1 result.ids=90d8…
   ├─ pfs.search.embed         5 ms
   └─ pfs.search.vector        4 ms   distances=90d8…=0.239
```

**The dashboard** shows:
- searches per minute, and p95 latency per stage (keyword, semantic, query embedding, vector query);
- requests, p95 latency and errors per endpoint;
- uploads (single or chunked, deduplicated or new), bytes uploaded, storage after deduplication;
- the indexing queue, files indexed by outcome, and indexing time;
- recent search traces and the logs.

**Logs** carry the trace id and go to Loki with the `otel` profile:
- one access line per API request: method, path, status, time, user;
- file stored, items moved, trashed, restored or deleted forever;
- failed sign-ins;
- maintenance runs;
- a startup line with the configuration.

The compose app logs JSON (ECS). Logs never include file names, emails or the query string. Search text goes on traces only where `pfs.trace-search-text=true`, which is set in `application.properties` for development and off by default.

**Metrics** are pushed over OTLP every 10 s, not scraped, so the only actuator endpoint is `/actuator/health`. Metric tags hold bounded values only (page, filters, path, status). Ids and text stay on traces.

## Security

- **Sign-in switch:** `pfs.sign-in-required` defaults to `true` in code, so a deployment is never open by accident. Only `application.properties` turns it off for now.
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
| ONNX Runtime | Runs the embedding model for semantic search on the CPU. Passages, storage, querying and the tokenizer are ours. |
| bge-small-en-v1.5 (model weights, MIT) | Embeds text for semantic search |
| pgvector (Postgres extension) | Vector column and nearest-neighbour index |
| Spring Boot Actuator, OpenTelemetry starter | Traces, metrics and log export over OTLP |
| OpenTelemetry Logback appender | Sends log lines to the OTLP log exporter |
| grafana/otel-lgtm (Docker image, development only) | Collector, Prometheus, Tempo, Loki and Grafana in one container |
| Apache PDFBox | Reads the text out of PDFs for content search. A parser for one format; indexing, queueing and querying are ours. |
| PostgreSQL JDBC driver | Database driver |
| Testcontainers, JUnit 5, AssertJ | Tests against a real Postgres |
| React, Vite, TypeScript | Web app and build |

Everything that does file-system work is written in this repo:
- the chunked upload protocol and positional writes
- content addressing, deduplication and garbage collection
- Range and ETag handling (by hand, not Spring's `ResourceRegion`)
- tree operations and cycle checks
- content indexing: the queue, format detection, limits, snippets
- semantic search: tokenizer, passage splitting, pooling, the owner-filtered vector query
- the folder-drop walker and the virtual list in the web app

## Known limits

| Limit | Value |
| --- | --- |
| Largest file / storage per account | 10 GB / 2 GB (`pfs.max-file-size`, `pfs.default-quota`) |
| Upload resume | Survives dropped connections, not a page reload (the browser loses the file handles) |
| Plays in the browser | mp4/webm video, common audio and images, PDF. mkv and HEIC download instead (browser limits) |
| Searchable inside | PDFs and UTF-8 text. Keyword: first 1,000 pages or 500k characters. By meaning: first ~9,000 words. Scanned PDFs need OCR (not built) |
| Storage | One server's disk; nothing stops the disk itself from filling |

## Status

- **Done:** auth, uploads (chunked, resumable, parallel, single-request, folder trees), listing, move/rename/bulk move with undo, trash and restore, downloads with Range, quota, background maintenance, web app, Docker image, search by name (as-you-type, prefixes, typos, filters, `in:` scope), content search inside PDFs and text files with snippets, semantic search (related by meaning), observability (traces, metrics, logs, Grafana dashboard).
- **Next:** resuming uploads after a page reload, running totals for usage stats. Office documents (Word, Excel, PowerPoint) could be added to content search with Apache POI.

## How it was built

Built in 18 small pull requests, each with focused commits and green CI, so the history reads as a story ([all PRs](https://github.com/nathanTuala/performant-fs-spring/pulls?q=is%3Apr)):

| PRs | What |
| --- | --- |
| #1–#4 | Scaffold, schema and security baseline, auth, folders and listing |
| #5–#7 | Blob store and deduplication, downloads with Range, chunked resumable uploads |
| #8–#9 | Move, rename, trash, restore; background maintenance |
| #10–#11 | React web app; serving it, Docker image |
| #12–#13 | Search as you type; content search inside PDFs and text files |
| #14 | Sign-in switch (off for the demo) |
| #15 | Semantic search (local model + pgvector) |
| #16 | Drag items out of an expanded folder |
| #17 | Observability: OpenTelemetry + Grafana |
| #18 | This README |

## How I used AI tools

I built this with **Claude Code** (Anthropic's coding agent) as a pair programmer. Most code was typed by the agent; the direction, decisions and verification were mine.

**What I decided and drove**
- **Scope and stack.** A first version was written in Node/TypeScript. I switched it to **Java 25 and Spring Boot**, the stack I want to be evaluated on, and had it rebuilt as this repo in small PRs.
- **Process.** I asked for small, focused commits and stacked PRs, so every change could be reviewed and followed.
- **Interpreting the brief.** I asked what "search and query through the files" means, which led to searching file contents and then meaning, not just names. I checked that each library fits the "no premade file-management libraries" rule; PDFBox, ONNX Runtime and pgvector are parsers and infrastructure, and the file-system logic is ours.
- **Product calls.** I chose a local embedding model over a hosted API (privacy, no keys), OpenTelemetry + Grafana for observability, Finder-style folders, and turning sign-in off for the demo.
- **Testing it as a user, and reporting what was wrong.** Each of these was fixed with a regression test where it applies:
  - the search view could trap you, with no way back to your files;
  - the empty state showed syntax tips instead of a plain message;
  - the usage figure ignored deletions;
  - a file couldn't be dragged back out of an expanded folder;
  - "energy transformation" didn't find notes on photosynthesis. That led to measuring two embedding models and switching to the one that separated related from unrelated files.

**What the agent did**
- Wrote the code, tests, migrations and PR descriptions, in small commits.
- Researched options and trade-offs on request: Tika vs PDFBox, embedding models, caching, observability tooling.
- Measured instead of guessing:
  - search latency on a 100k-file folder;
  - query-to-passage distances for the two embedding models;
  - p95 latencies in Grafana.

**How the output was checked**
- **Every PR:** 220 unit and end-to-end tests (the real server, over HTTP, against a real Postgres), run on each commit and in CI.
- **In a real browser:** each UI change was exercised with Playwright (drag and drop, live search, related results), and I tried the features myself.
- **Correctness first:** the concurrency hazards in the table above each have a test that races real requests.
- **Honest reporting:** the PR descriptions note what wasn't measured, such as content search at large scale.
