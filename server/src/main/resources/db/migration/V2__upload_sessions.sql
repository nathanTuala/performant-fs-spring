CREATE TABLE upload_sessions (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  parent_id      uuid NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
  name           text NOT NULL,
  mime           text,
  size           bigint NOT NULL CHECK (size >= 0),
  chunk_size     int NOT NULL CHECK (chunk_size > 0),
  chunk_count    int NOT NULL CHECK (chunk_count >= 0),
  received       bit varying NOT NULL,     -- bit i set once chunk i is on disk and verified
  received_count int NOT NULL DEFAULT 0,
  status         text NOT NULL DEFAULT 'open'
                   CHECK (status IN ('open', 'completing', 'done', 'cancelled')),
  node_id        uuid REFERENCES nodes(id) ON DELETE SET NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  expires_at     timestamptz NOT NULL
);
CREATE INDEX upload_sessions_expiry ON upload_sessions (expires_at) WHERE status IN ('open', 'completing');
