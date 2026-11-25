-- Semantic search: each file's text is split into overlapping passages, and each passage gets
-- an embedding (a 384-dimension vector from all-MiniLM-L6-v2) that places it by meaning.
-- Keyed by blob like blob_text, so identical bytes are embedded once.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE blob_chunks (
  hash      char(64) NOT NULL REFERENCES blobs(hash) ON DELETE CASCADE,
  seq       int NOT NULL,          -- passage order within the file
  text      text NOT NULL,         -- shown as the snippet when this passage matches
  embedding vector(384) NOT NULL,  -- unit length, so cosine distance ranks by meaning
  PRIMARY KEY (hash, seq)
);

-- Approximate nearest neighbours by cosine distance.
CREATE INDEX blob_chunks_embedding ON blob_chunks USING hnsw (embedding vector_cosine_ops);

-- Re-read files indexed before this migration, so they get passages too.
UPDATE blob_text SET status = 'pending', claimed_at = NULL, attempts = 0 WHERE status = 'indexed';
