CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE users (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email          text NOT NULL,
  password_hash  text NOT NULL,
  root_id        uuid,
  quota_bytes    bigint NOT NULL DEFAULT 2147483648,
  used_bytes     bigint NOT NULL DEFAULT 0 CHECK (used_bytes >= 0),
  -- Bytes promised to open upload sessions. Reserving at init (not at complete)
  -- stops parallel inits from each passing the quota check.
  reserved_bytes bigint NOT NULL DEFAULT 0 CHECK (reserved_bytes >= 0),
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email ON users (lower(email));

CREATE TABLE sessions (
  token_hash bytea PRIMARY KEY,           -- sha256(cookie token); the raw token is never stored
  user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL
);
CREATE INDEX sessions_expiry ON sessions (expires_at);

-- Content-addressed, immutable file bytes. One row per distinct SHA-256.
CREATE TABLE blobs (
  hash       char(64) PRIMARY KEY,
  size       bigint NOT NULL,
  refcount   int NOT NULL DEFAULT 0 CHECK (refcount >= 0),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX blobs_unreferenced ON blobs (hash) WHERE refcount = 0;

CREATE TABLE nodes (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id   uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  parent_id  uuid REFERENCES nodes(id),   -- NULL only for a user's root
  is_folder  boolean NOT NULL,
  name       text NOT NULL CHECK (
               name <> '' AND name NOT IN ('.', '..') AND name !~ '/'
               AND length(name) <= 255
               -- macOS sends NFD; normalising on write keeps "é" and "é" from coexisting.
               AND name IS NFC NORMALIZED),
  size       bigint NOT NULL DEFAULT 0 CHECK (size >= 0),
  mime       text,
  blob_hash  char(64) REFERENCES blobs(hash),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz,
  trash_id   uuid,
  -- Split on _ - . so "quarterly_report.pdf" yields the words quarterly / report / pdf.
  search     tsvector GENERATED ALWAYS AS
               (to_tsvector('simple', regexp_replace(name, '[_.\-]+', ' ', 'g'))) STORED,
  CHECK (is_folder = (blob_hash IS NULL)),
  CHECK (parent_id IS NOT NULL OR is_folder)
);

ALTER TABLE users ADD CONSTRAINT users_root_fk FOREIGN KEY (root_id) REFERENCES nodes(id);

-- Case-insensitive sibling uniqueness among live items.
CREATE UNIQUE INDEX nodes_sibling_name ON nodes (parent_id, lower(name)) WHERE deleted_at IS NULL;

-- Listings are served folders-first as two keyset segments (is_folder = true, then false),
-- so each sort key needs one index usable in both directions.
CREATE INDEX nodes_list_name     ON nodes (parent_id, is_folder, lower(name), id) WHERE deleted_at IS NULL;
CREATE INDEX nodes_list_size     ON nodes (parent_id, is_folder, size, id)        WHERE deleted_at IS NULL;
CREATE INDEX nodes_list_modified ON nodes (parent_id, is_folder, updated_at, id)  WHERE deleted_at IS NULL;

CREATE INDEX nodes_search ON nodes USING gin (search);
CREATE INDEX nodes_trgm   ON nodes USING gin (lower(name) gin_trgm_ops);
CREATE INDEX nodes_owner_recent ON nodes (owner_id, updated_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX nodes_trash  ON nodes (owner_id, trash_id) WHERE deleted_at IS NOT NULL;
CREATE INDEX nodes_blob   ON nodes (blob_hash) WHERE blob_hash IS NOT NULL;
