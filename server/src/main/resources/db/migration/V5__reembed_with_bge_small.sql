-- The embedding model changed (all-MiniLM-L6-v2 → bge-small-en-v1.5, same 384 dimensions).
-- Vectors from different models can't be compared, so drop the old ones and re-index every file
-- that has text; until a file is re-indexed it simply has no related-by-meaning matches.
DELETE FROM blob_chunks;
UPDATE blob_text SET status = 'pending', claimed_at = NULL, attempts = 0 WHERE status = 'indexed';
