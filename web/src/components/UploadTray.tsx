import { useMemo, useState, useSyncExternalStore } from 'react';
import { formatBytes, formatDuration } from '../lib/format';
import { uploads, type UploadView } from '../upload/queue';
import { Icon } from './Icon';

/** Rows rendered at most; a 10k-file drop shows the active ones first. */
const MAX_ROWS = 200;
const ORDER: Record<UploadView['state'], number> = { uploading: 0, error: 1, paused: 2, queued: 3, done: 4, cancelled: 5 };

export function UploadTray() {
  const items = useSyncExternalStore(uploads.subscribe, uploads.getSnapshot);
  const [collapsed, setCollapsed] = useState(false);

  const summary = useMemo(() => {
    let total = 0, sent = 0, bps = 0, active = 0, done = 0, failed = 0;
    for (const item of items) {
      if (item.state === 'cancelled') continue;
      total += item.size;
      sent += item.sentBytes;
      bps += item.bytesPerSecond;
      if (item.state === 'uploading' || item.state === 'queued') active++;
      if (item.state === 'done') done++;
      if (item.state === 'error') failed++;
    }
    return { total, sent, bps, active, done, failed };
  }, [items]);

  const rows = useMemo(
    () => [...items].sort((a, b) => ORDER[a.state] - ORDER[b.state]).slice(0, MAX_ROWS),
    [items],
  );

  if (items.length === 0) return null;
  const pct = summary.total ? Math.round((summary.sent / summary.total) * 100) : 100;
  const title = summary.active > 0
    ? `Uploading ${summary.active} item${summary.active === 1 ? '' : 's'} · ${pct}%`
    : summary.failed > 0
      ? `${summary.failed} upload${summary.failed === 1 ? '' : 's'} failed`
      : `${summary.done} upload${summary.done === 1 ? '' : 's'} complete`;

  return (
    <section className="tray" aria-label="Uploads">
      <header className="tray-head">
        <button className="tray-title" onClick={() => setCollapsed(!collapsed)} aria-expanded={!collapsed}>
          {title}
        </button>
        {summary.active > 0 && summary.bps > 0 && (
          <span className="muted small">
            {formatBytes(summary.bps)}/s · {formatDuration((summary.total - summary.sent) / summary.bps)}
          </span>
        )}
        {summary.active === 0 ? (
          <button className="icon-btn" aria-label="Close upload list" onClick={() => uploads.clearFinished()}>
            <Icon name="close" size={16} />
          </button>
        ) : (
          <button className="link small" onClick={() => uploads.cancelAll()}>Cancel all</button>
        )}
      </header>
      {summary.active > 0 && <div className="progress"><div style={{ width: `${pct}%` }} /></div>}
      {!collapsed && (
        <ul className="tray-list">
          {rows.map((item) => <TrayRow key={item.id} item={item} />)}
          {items.length > MAX_ROWS && <li className="muted small tray-more">and {items.length - MAX_ROWS} more…</li>}
        </ul>
      )}
    </section>
  );
}

function TrayRow({ item }: { item: UploadView }) {
  const pct = item.size ? (item.sentBytes / item.size) * 100 : item.state === 'done' ? 100 : 0;
  const status =
    item.state === 'error' ? item.error
    : item.state === 'uploading' ? `${formatBytes(item.sentBytes)} of ${formatBytes(item.size)}${item.bytesPerSecond ? ` · ${formatBytes(item.bytesPerSecond)}/s` : ''}`
    : item.state === 'done' ? formatBytes(item.size)
    : item.state;
  return (
    <li className={`tray-row ${item.state}`}>
      <div className="tray-row-main">
        <span className="ellipsis" title={item.path}>{item.name}</span>
        <span className={`small ${item.state === 'error' ? 'error-text' : 'muted'}`}>{status}</span>
        {(item.state === 'uploading' || item.state === 'paused') && (
          <div className="progress thin"><div style={{ width: `${pct}%` }} /></div>
        )}
      </div>
      <div className="tray-actions">
        {item.state === 'done' && <Icon name="check" className="ok" />}
        {item.state === 'uploading' && (
          <button className="icon-btn" aria-label={`Pause ${item.name}`} onClick={() => uploads.pause(item.id)}><Icon name="pause" size={16} /></button>
        )}
        {item.state === 'paused' && (
          <button className="icon-btn" aria-label={`Resume ${item.name}`} onClick={() => uploads.resume(item.id)}><Icon name="play" size={16} /></button>
        )}
        {item.state === 'error' && (
          <button className="icon-btn" aria-label={`Retry ${item.name}`} onClick={() => uploads.resume(item.id)}><Icon name="retry" size={16} /></button>
        )}
        {item.state !== 'done' && item.state !== 'cancelled' && (
          <button className="icon-btn" aria-label={`Cancel ${item.name}`} onClick={() => uploads.cancel(item.id)}><Icon name="close" size={16} /></button>
        )}
      </div>
    </li>
  );
}
