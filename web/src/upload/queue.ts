import type { NodeDto, UploadSession } from '../api/types';
import { api, ApiError } from '../api/client';

/** Files at or under this size go up in one request (the server's single-request fast path). */
const FAST_PATH_MAX = 8 * 1024 * 1024;
/** Chunk requests in flight for one file. */
const PER_FILE_CONCURRENCY = 4;
/** Requests in flight across every file. */
const GLOBAL_CONCURRENCY = 6;
const MAX_ATTEMPTS = 6;

export type UploadState = 'queued' | 'uploading' | 'paused' | 'done' | 'error' | 'cancelled';

/** Immutable snapshot of one upload, for rendering. */
export interface UploadView {
  id: string;
  name: string;
  path: string;
  size: number;
  parentId: string;
  state: UploadState;
  sentBytes: number;
  bytesPerSecond: number;
  error: string | null;
}

interface Job {
  id: string;
  file: File;
  path: string;
  parentId: string;
  state: UploadState;
  error: string | null;
  session: UploadSession | null;
  /** Chunk indexes still to send. */
  pending: number[];
  /** Chunks on the wire, with bytes sent so far. */
  inflight: Map<number, { xhr: XMLHttpRequest | null; loaded: number }>;
  /** Chunks waiting out a retry backoff. */
  backoff: Set<number>;
  attempts: Map<number, number>;
  /** Bytes in chunks the server has confirmed. */
  confirmedBytes: number;
  /** A control request (init, status, complete, fast-path PUT) is running. */
  busy: boolean;
  /** Abort handle for the fast-path request. */
  smallXhr: XMLHttpRequest | null;
  /**
   * Bumped on pause, cancel and failure. Work started under an older generation is
   * discarded when it finishes; resuming re-reads the server's chunk list instead.
   */
  generation: number;
  speed: { lastBytes: number; lastAt: number; bps: number };
  view: UploadView;
}

type Listener = () => void;

export class UploadQueue {
  private jobs: Job[] = [];
  private requestsInFlight = 0;
  private snapshot: UploadView[] = [];
  private listeners = new Set<Listener>();
  private completedListeners = new Set<(node: NodeDto) => void>();
  private notifyScheduled = false;

  constructor() {
    window.addEventListener('online', () => this.pump());
    // A navigation would drop every in-flight upload.
    window.addEventListener('beforeunload', (e) => {
      if (this.jobs.some((j) => j.state === 'uploading' || j.state === 'queued')) e.preventDefault();
    });
  }

  // ---------------------------------------------------------------- public API

  enqueue(files: { file: File; path?: string; parentId: string }[]): void {
    for (const { file, path, parentId } of files) {
      const job: Job = {
        id: crypto.randomUUID(),
        file,
        path: path ?? file.name,
        parentId,
        state: 'queued',
        error: null,
        session: null,
        pending: [],
        inflight: new Map(),
        backoff: new Set(),
        attempts: new Map(),
        confirmedBytes: 0,
        busy: false,
        smallXhr: null,
        generation: 0,
        speed: { lastBytes: 0, lastAt: performance.now(), bps: 0 },
        view: undefined as unknown as UploadView,
      };
      job.view = this.viewOf(job);
      this.jobs.push(job);
    }
    this.changed();
    this.pump();
  }

  pause(id: string): void {
    const job = this.find(id);
    if (!job || (job.state !== 'uploading' && job.state !== 'queued')) return;
    job.state = 'paused';
    this.abortInflight(job);
    this.changed(job);
  }

  resume(id: string): void {
    const job = this.find(id);
    if (!job || (job.state !== 'paused' && job.state !== 'error')) return;
    job.state = 'queued';
    job.error = null;
    job.attempts.clear();
    this.changed(job);
    this.pump();
  }

  cancel(id: string): void {
    const job = this.find(id);
    if (!job || job.state === 'done' || job.state === 'cancelled') return;
    job.state = 'cancelled';
    this.abortInflight(job);
    if (job.session) void fetch(`/api/uploads/${job.session.uploadId}`, { method: 'DELETE' }).catch(() => {});
    this.changed(job);
    this.pump();
  }

  /** Removes finished, failed and cancelled items from the list. */
  clearFinished(): void {
    this.jobs = this.jobs.filter((j) => j.state === 'uploading' || j.state === 'queued' || j.state === 'paused');
    this.changed();
  }

  cancelAll(): void {
    for (const job of [...this.jobs]) this.cancel(job.id);
  }

  subscribe = (listener: Listener): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = (): UploadView[] => this.snapshot;

  onCompleted(listener: (node: NodeDto) => void): () => void {
    this.completedListeners.add(listener);
    return () => this.completedListeners.delete(listener);
  }

  // ---------------------------------------------------------------- scheduler

  /** Starts as much work as the concurrency limits allow, oldest files first. */
  private pump(): void {
    for (const job of this.jobs) {
      if (this.requestsInFlight >= GLOBAL_CONCURRENCY) return;
      if (job.state === 'queued') {
        job.state = 'uploading';
        this.changed(job);
        void this.start(job);
        continue;
      }
      if (job.state !== 'uploading' || job.busy || !job.session) continue;
      while (job.pending.length > 0 && job.inflight.size < PER_FILE_CONCURRENCY && this.requestsInFlight < GLOBAL_CONCURRENCY) {
        void this.sendChunk(job, job.pending.shift()!);
      }
    }
  }

  private async start(job: Job): Promise<void> {
    if (job.file.size <= FAST_PATH_MAX && !job.session) return this.uploadSmall(job);
    await this.control(job, async () => {
      if (job.session) {
        // Resuming: ask the server what it already has, so only missing chunks are sent.
        job.session = await this.request<UploadSession>('GET', `/api/uploads/${job.session.uploadId}`);
      } else {
        job.session = await this.request<UploadSession>('POST', '/api/uploads', {
          parentId: job.parentId,
          name: job.file.name,
          size: job.file.size,
          mime: job.file.type || undefined,
        });
      }
      this.syncFromSession(job);
    });
    if (job.state === 'uploading') this.maybeComplete(job);
    this.pump();
  }

  private syncFromSession(job: Job): void {
    const session = job.session!;
    const received = new Set(session.received);
    job.pending = [];
    job.confirmedBytes = 0;
    for (let i = 0; i < session.chunkCount; i++) {
      if (received.has(i)) job.confirmedBytes += this.chunkLength(job, i);
      else job.pending.push(i);
    }
    this.changed(job);
  }

  private chunkLength(job: Job, index: number): number {
    const size = job.session!.chunkSize;
    return Math.min(size, job.file.size - index * size);
  }

  // ---------------------------------------------------------------- chunk transfer

  private async sendChunk(job: Job, index: number): Promise<void> {
    const session = job.session!;
    const start = index * session.chunkSize;
    const blob = job.file.slice(start, start + this.chunkLength(job, index));
    const generation = job.generation;
    const placeholder: { xhr: XMLHttpRequest | null; loaded: number } = { xhr: null, loaded: 0 };
    job.inflight.set(index, placeholder); // reserve the slot while we hash
    this.requestsInFlight++;

    let result: { status: number; body: string };
    try {
      const digest = await sha256Base64(blob);
      if (job.generation !== generation) throw new Error('aborted');
      result = await this.xhr('PUT', `/api/uploads/${session.uploadId}/chunks/${index}`, blob, {
        headers: digest ? { 'content-digest': `sha-256=:${digest}:` } : {},
        onStart: (xhr) => (placeholder.xhr = xhr),
        onProgress: (loaded) => {
          placeholder.loaded = loaded;
          this.changed(job);
        },
      });
    } catch {
      result = { status: 0, body: '' };
    } finally {
      this.requestsInFlight--;
      if (job.inflight.get(index) === placeholder) job.inflight.delete(index);
    }

    if (job.generation !== generation) {
      this.changed(job);
      return this.pump();
    }

    if (result.status === 200) {
      job.confirmedBytes += blob.size;
      this.changed(job);
      this.maybeComplete(job);
    } else if (isRetryable(result)) {
      this.retryLater(job, index);
    } else {
      this.fail(job, errorMessage(result));
    }
    this.pump();
  }

  private retryLater(job: Job, index: number): void {
    const attempt = (job.attempts.get(index) ?? 0) + 1;
    job.attempts.set(index, attempt);
    if (attempt >= MAX_ATTEMPTS) return this.fail(job, 'Upload keeps failing — check your connection and retry');
    job.backoff.add(index);
    const generation = job.generation;
    setTimeout(() => {
      if (job.generation !== generation) return;
      job.backoff.delete(index);
      job.pending.unshift(index);
      this.pump();
    }, backoffMs(attempt));
  }

  private maybeComplete(job: Job): void {
    if (job.pending.length > 0 || job.inflight.size > 0 || job.backoff.size > 0 || job.busy) return;
    void this.control(job, async () => {
      try {
        const done = await this.request<UploadSession>('POST', `/api/uploads/${job.session!.uploadId}/complete`);
        if (done.status === 'done' && done.node) return this.succeed(job, done.node);
        // Another request is still committing it: poll until it lands.
        await sleep(1000);
        job.state = 'queued';
      } catch (err) {
        if (err instanceof ApiError && err.code === 'upload_incomplete') {
          // The server lost track of a chunk (e.g. a corrupted resend). Resync and continue.
          job.state = 'queued';
          return;
        }
        throw err;
      }
    }).then(() => this.pump());
  }

  // ---------------------------------------------------------------- fast path

  private async uploadSmall(job: Job): Promise<void> {
    const generation = job.generation;
    const q = new URLSearchParams({ parentId: job.parentId, name: job.file.name });
    if (job.file.type) q.set('mime', job.file.type);
    job.busy = true;
    try {
      for (let attempt = 1; ; attempt++) {
        this.requestsInFlight++;
        let result: { status: number; body: string };
        try {
          result = await this.xhr('POST', `/api/files?${q}`, job.file, {
            onStart: (xhr) => (job.smallXhr = xhr),
            onProgress: (loaded) => {
              job.confirmedBytes = loaded;
              this.changed(job);
            },
          });
        } finally {
          this.requestsInFlight--;
          job.smallXhr = null;
        }
        if (job.generation !== generation) return;
        if (result.status === 201) return this.succeed(job, JSON.parse(result.body) as NodeDto);
        job.confirmedBytes = 0;
        if (!isRetryable(result) || attempt >= MAX_ATTEMPTS) return this.fail(job, errorMessage(result));
        this.changed(job);
        await sleep(backoffMs(attempt));
        if (job.generation !== generation) return;
      }
    } finally {
      job.busy = false;
      if (job.generation !== generation) job.confirmedBytes = 0;
      this.changed(job);
      this.pump();
    }
  }

  // ---------------------------------------------------------------- plumbing

  /** Runs a control request (init/status/complete), retrying network errors with backoff. */
  private async control(job: Job, fn: () => Promise<void>): Promise<void> {
    job.busy = true;
    this.requestsInFlight++;
    try {
      for (let attempt = 1; ; attempt++) {
        try {
          await fn();
          return;
        } catch (err) {
          const retryable = err instanceof ApiError && (err.status === 0 || err.status >= 500);
          if (!retryable || attempt >= MAX_ATTEMPTS || job.state !== 'uploading') {
            if (job.state === 'uploading') this.fail(job, err instanceof Error ? err.message : 'Upload failed');
            return;
          }
          await sleep(backoffMs(attempt));
        }
      }
    } finally {
      this.requestsInFlight--;
      job.busy = false;
      this.changed(job);
    }
  }

  private async request<T>(method: string, url: string, body?: unknown): Promise<T> {
    let res: Response;
    try {
      res = await fetch(url, {
        method,
        headers: body ? { 'content-type': 'application/json' } : {},
        body: body ? JSON.stringify(body) : undefined,
      });
    } catch {
      throw new ApiError(0, 'network', 'Network error');
    }
    const data = await res.json().catch(() => null);
    if (!res.ok) throw new ApiError(res.status, data?.error?.code ?? 'internal', data?.error?.message ?? `HTTP ${res.status}`);
    return data as T;
  }

  /** XMLHttpRequest, because fetch() cannot report upload progress. */
  private xhr(
    method: string,
    url: string,
    body: Blob,
    opts: { headers?: Record<string, string>; onStart: (xhr: XMLHttpRequest) => void; onProgress: (loaded: number) => void },
  ): Promise<{ status: number; body: string }> {
    return new Promise((resolve) => {
      const xhr = new XMLHttpRequest();
      xhr.open(method, url);
      xhr.setRequestHeader('content-type', 'application/octet-stream');
      for (const [k, v] of Object.entries(opts.headers ?? {})) xhr.setRequestHeader(k, v);
      xhr.upload.onprogress = (e) => opts.onProgress(e.loaded);
      xhr.onload = () => resolve({ status: xhr.status, body: xhr.responseText });
      xhr.onerror = xhr.onabort = xhr.ontimeout = () => resolve({ status: 0, body: '' });
      opts.onStart(xhr);
      xhr.send(body);
    });
  }

  private abortInflight(job: Job): void {
    job.generation++;
    job.pending = [];
    job.backoff.clear();
    for (const { xhr } of job.inflight.values()) xhr?.abort();
    job.smallXhr?.abort();
  }

  private succeed(job: Job, node: NodeDto): void {
    job.state = 'done';
    job.confirmedBytes = job.file.size;
    this.changed(job);
    for (const listener of this.completedListeners) listener(node);
  }

  private fail(job: Job, message: string): void {
    job.state = 'error';
    job.error = message;
    this.abortInflight(job);
    this.changed(job);
  }

  private find(id: string): Job | undefined {
    return this.jobs.find((j) => j.id === id);
  }

  private viewOf(job: Job): UploadView {
    let sent = job.confirmedBytes;
    for (const { loaded } of job.inflight.values()) sent += loaded;
    sent = Math.min(sent, job.file.size);
    const now = performance.now();
    const dt = (now - job.speed.lastAt) / 1000;
    if (dt >= 0.5) {
      const instant = Math.max(0, (sent - job.speed.lastBytes) / dt);
      job.speed.bps = job.speed.bps === 0 ? instant : job.speed.bps * 0.7 + instant * 0.3;
      job.speed.lastBytes = sent;
      job.speed.lastAt = now;
    }
    return {
      id: job.id,
      name: job.file.name,
      path: job.path,
      size: job.file.size,
      parentId: job.parentId,
      state: job.state,
      sentBytes: sent,
      bytesPerSecond: job.state === 'uploading' ? job.speed.bps : 0,
      error: job.error,
    };
  }

  /** Marks a job (or the list) as changed; listeners are notified at most once per frame. */
  private changed(job?: Job): void {
    if (job) job.view = this.viewOf(job);
    if (this.notifyScheduled) return;
    this.notifyScheduled = true;
    requestAnimationFrame(() => {
      this.notifyScheduled = false;
      this.snapshot = this.jobs.map((j) => j.view);
      for (const listener of this.listeners) listener();
    });
  }
}

function isRetryable(result: { status: number; body: string }): boolean {
  if (result.status === 0 || result.status >= 500 || result.status === 429) return true;
  // A chunk corrupted in transit is worth sending again.
  return result.status === 400 && result.body.includes('digest_mismatch');
}

function errorMessage(result: { status: number; body: string }): string {
  try {
    return (JSON.parse(result.body) as { error: { message: string } }).error.message;
  } catch {
    return `Upload failed (HTTP ${result.status})`;
  }
}

async function sha256Base64(blob: Blob): Promise<string | null> {
  if (!crypto.subtle) return null; // insecure origin: the digest is optional
  const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer());
  let binary = '';
  for (const byte of new Uint8Array(digest)) binary += String.fromCharCode(byte);
  return btoa(binary);
}

/** Exponential backoff with jitter, capped at 30 s. */
const backoffMs = (attempt: number) => Math.min(30_000, 500 * 2 ** attempt) * (0.75 + Math.random() / 2);

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

export const uploads = new UploadQueue();
