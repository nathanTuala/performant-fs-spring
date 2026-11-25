import type {
  ApiErrorBody,
  ConflictPolicy,
  ErrorCode,
  FolderBatchResponse,
  NodeDto,
  NodeWithPath,
  SearchHit,
  Page,
  SortDir,
  SortKey,
  Usage,
  User,
} from './types';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: ErrorCode | 'network',
    message: string,
  ) {
    super(message);
  }
}

/** True for the rejection of a request cancelled through its AbortSignal. */
export const isAbort = (err: unknown) => err instanceof DOMException && err.name === 'AbortError';

async function request<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`/api${path}`, {
      method,
      credentials: 'same-origin',
      headers: body === undefined ? {} : { 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });
  } catch (err) {
    if (isAbort(err)) throw err; // the caller cancelled it; not a network problem
    throw new ApiError(0, 'network', 'Network error — check your connection');
  }
  if (res.status === 204) return undefined as T;
  const data = (await res.json().catch(() => null)) as T | ApiErrorBody | null;
  if (!res.ok) {
    const err = (data as ApiErrorBody | null)?.error;
    throw new ApiError(res.status, err?.code ?? 'internal', err?.message ?? `Request failed (${res.status})`);
  }
  return data as T;
}

export const api = {
  me: () => request<User>('GET', '/auth/me'),
  login: (email: string, password: string) => request<User>('POST', '/auth/login', { email, password }),
  register: (email: string, password: string) => request<User>('POST', '/auth/register', { email, password }),
  logout: () => request<void>('POST', '/auth/logout'),

  node: (id: string) => request<NodeWithPath>('GET', `/nodes/${id}`),
  children: (id: string, opts: { cursor?: string | null; limit?: number; sort: SortKey; dir: SortDir }) => {
    const q = new URLSearchParams({ sort: opts.sort, dir: opts.dir, limit: String(opts.limit ?? 100) });
    if (opts.cursor) q.set('cursor', opts.cursor);
    return request<Page<NodeDto>>('GET', `/nodes/${id}/children?${q}`);
  },
  createFolder: (parentId: string, name: string, onConflict: ConflictPolicy = 'error') =>
    request<NodeDto>('POST', '/folders', { parentId, name, onConflict }),
  createFolders: (parentId: string, paths: string[]) =>
    request<FolderBatchResponse>('POST', '/folders/batch', { parentId, paths }),
  rename: (id: string, name: string) => request<NodeDto>('PATCH', `/nodes/${id}`, { name }),
  move: (ids: string[], parentId: string) =>
    request<{ items: NodeDto[] }>('POST', '/nodes/move', { ids, parentId, onConflict: 'rename' }),
  trash: (id: string) => request<void>('DELETE', `/nodes/${id}`),
  restore: (id: string) => request<NodeDto>('POST', `/nodes/${id}/restore`),
  listTrash: () => request<{ items: NodeDto[] }>('GET', '/trash'),
  emptyTrash: () => request<{ deleted: number }>('DELETE', '/trash'),
  deleteForever: (id: string) => request<{ deleted: number }>('DELETE', `/trash/${id}`),
  stats: () => request<Usage>('GET', '/stats'),
  search: (q: string, cursor?: string | null, signal?: AbortSignal) => {
    const params = new URLSearchParams({ q });
    if (cursor) params.set('cursor', cursor);
    return request<Page<SearchHit>>('GET', `/search?${params}`, undefined, signal);
  },

  contentUrl: (id: string, download = false) => `/api/files/${id}/content${download ? '?download=1' : ''}`,
};
