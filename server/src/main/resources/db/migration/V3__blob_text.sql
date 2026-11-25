-- Text extracted from file contents, for searching inside files. Keyed by blob, not by node:
-- identical bytes are stored once, so they are read and indexed once, however many copies exist.
CREATE TABLE blob_text (
  hash       char(64) PRIMARY KEY REFERENCES blobs(hash) ON DELETE CASCADE,
  -- pending: queued.  indexed: content holds the text (possibly empty, e.g. a scanned PDF).
  -- unsupported: not a format we read.  failed: a reader gave up (corrupt, too slow, encrypted).
  status     text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'indexed', 'unsupported', 'failed')),
  content    text,
  search     tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED,
  -- A worker stamps claimed_at when it takes a row; a stale stamp means it died mid-way.
  claimed_at timestamptz,
  attempts   int NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX blob_text_search  ON blob_text USING gin (search);
CREATE INDEX blob_text_pending ON blob_text (updated_at) WHERE status = 'pending';

-- Files stored before this migration join the queue.
INSERT INTO blob_text (hash) SELECT hash FROM blobs;
