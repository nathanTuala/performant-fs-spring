import { useSyncExternalStore } from 'react';

export interface Toast {
  id: number;
  message: string;
  tone: 'info' | 'error';
  action?: { label: string; run: () => void };
}

let toasts: Toast[] = [];
let nextId = 1;
const listeners = new Set<() => void>();
const emit = () => listeners.forEach((l) => l());

export function toast(message: string, opts: { tone?: Toast['tone']; action?: Toast['action']; ms?: number } = {}): void {
  const item: Toast = { id: nextId++, message, tone: opts.tone ?? 'info', action: opts.action };
  toasts = [...toasts.slice(-3), item];
  emit();
  setTimeout(() => dismiss(item.id), opts.ms ?? (opts.action ? 8000 : 4000));
}

export const toastError = (err: unknown) => toast(err instanceof Error ? err.message : 'Something went wrong', { tone: 'error' });

export function dismiss(id: number): void {
  toasts = toasts.filter((t) => t.id !== id);
  emit();
}

export function useToasts(): Toast[] {
  return useSyncExternalStore(
    (cb) => {
      listeners.add(cb);
      return () => listeners.delete(cb);
    },
    () => toasts,
  );
}
