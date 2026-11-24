const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${UNITS[unit]}`;
}

const dateFmt = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
const timeFmt = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' });

export function formatDate(iso: string): string {
  const date = new Date(iso);
  const today = new Date();
  return date.toDateString() === today.toDateString() ? timeFmt.format(date) : dateFmt.format(date);
}

export function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds)) return '';
  if (seconds < 60) return `${Math.ceil(seconds)}s left`;
  if (seconds < 3600) return `${Math.ceil(seconds / 60)} min left`;
  return `${(seconds / 3600).toFixed(1)} h left`;
}
