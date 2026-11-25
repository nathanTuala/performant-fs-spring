// The JSON contract of the Spring Boot API (server/), as the web app sees it.
// Types only: every import of this file is `import type`, so nothing here exists at runtime.

export type NodeKind = 'file' | 'folder';

export interface NodeDto {
  id: string;
  parentId: string | null;
  kind: NodeKind;
  name: string;
  size: number;
  mime: string | null;
  /** SHA-256 of the content; doubles as the download ETag. Null for folders. */
  hash: string | null;
  createdAt: string;
  updatedAt: string;
  deletedAt: string | null;
}

export interface Crumb {
  id: string;
  name: string;
}

export interface NodeWithPath extends NodeDto {
  /** Ancestors from the root down to (not including) this node. */
  path: Crumb[];
}

/** An excerpt of a file's text around a search match. */
export interface Snippet {
  /** Plain text from inside the file: render it as text, never as HTML. */
  text: string;
  /** Character ranges of `text` that matched the query. */
  highlights: { start: number; end: number }[];
}

export interface SearchHit extends NodeWithPath {
  /** Present when the query matched the file's contents. */
  snippet?: Snippet;
}

export type SortKey = 'name' | 'size' | 'modified';
export type SortDir = 'asc' | 'desc';

export interface Page<T> {
  items: T[];
  /** Opaque keyset cursor; null when there are no more items. */
  nextCursor: string | null;
}

export interface User {
  id: string;
  email: string;
  rootId: string;
  /** From /auth/me: false while the server runs without sign-in (one shared local account). */
  signInRequired?: boolean;
}

export interface Usage {
  /** Bytes in the user's files, not counting Trash. What the UI shows as "used". */
  fileBytes: number;
  /** Bytes in Trash: still stored, and still counted against the quota until emptied. */
  trashBytes: number;
  /** Bytes counted against the quota: files plus Trash. */
  usedBytes: number;
  reservedBytes: number;
  quotaBytes: number;
  fileCount: number;
  folderCount: number;
  /** Logical bytes across all users minus physical blob bytes on disk. */
  dedupSavedBytes: number;
}

export type ConflictPolicy = 'error' | 'rename';

export interface UploadInitRequest {
  parentId: string;
  name: string;
  size: number;
  mime?: string;
}

export type UploadStatus = 'open' | 'completing' | 'done' | 'cancelled';

export interface UploadSession {
  uploadId: string;
  chunkSize: number;
  chunkCount: number;
  /** Indexes of chunks the server already holds. */
  received: number[];
  status: UploadStatus;
  /** Set once status is 'done'. */
  node: NodeDto | null;
}

export interface FolderBatchRequest {
  parentId: string;
  /** Relative folder paths such as "a", "a/b", "a/b/c". Parents are created implicitly. */
  paths: string[];
}

export interface FolderBatchResponse {
  /** Maps each requested path (and implied parent) to the created folder id. */
  folders: Record<string, string>;
}

export type ErrorCode =
  | 'bad_request'
  | 'unauthorized'
  | 'forbidden'
  | 'not_found'
  | 'name_conflict'
  | 'cycle'
  | 'quota_exceeded'
  | 'chunk_invalid'
  | 'digest_mismatch'
  | 'upload_incomplete'
  | 'upload_closed'
  | 'range_not_satisfiable'
  | 'payload_too_large'
  | 'internal';

export interface ApiErrorBody {
  error: { code: ErrorCode; message: string };
}
