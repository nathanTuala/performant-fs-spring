import type { SearchHit, Snippet } from '../api/types';
import { type ReactNode, useEffect, useRef, useState } from 'react';
import { api, isAbort } from '../api/client';
import { formatBytes, formatDate } from '../lib/format';
import { navigate } from '../lib/router';
import { Icon, iconFor } from './Icon';

interface Results {
  /** The query these results answer; may lag behind `q` while a new search is running. */
  query: string;
  items: SearchHit[];
  cursor: string | null;
  error: string | null;
}

const NONE: Results = { query: '', items: [], cursor: null, error: null };

/**
 * Results for `q`, which changes as the user types. The previous results stay on screen
 * until the new ones arrive, so nothing flickers, and each new query cancels the request
 * for the one before it.
 */
export function SearchView({ q }: { q: string }) {
  const [results, setResults] = useState<Results>(NONE);
  const [loading, setLoading] = useState(false);
  const inFlight = useRef<AbortController | null>(null);

  async function load(query: string, from: string | null) {
    inFlight.current?.abort();
    const controller = new AbortController();
    inFlight.current = controller;
    setLoading(true);
    try {
      const page = await api.search(query, from, controller.signal);
      setResults((prev) => ({
        query,
        items: from ? [...prev.items, ...page.items] : page.items,
        cursor: page.nextCursor,
        error: null,
      }));
    } catch (err) {
      if (isAbort(err)) return;
      setResults({ query, items: [], cursor: null, error: err instanceof Error ? err.message : 'Search failed' });
    } finally {
      if (inFlight.current === controller) {
        inFlight.current = null;
        setLoading(false);
      }
    }
  }

  useEffect(() => {
    document.title = `${q} – Search – Files`;
    if (q.trim()) void load(q, null);
    else setResults(NONE);
    return () => inFlight.current?.abort();
  }, [q]); // eslint-disable-line react-hooks/exhaustive-deps

  const { items, cursor, error } = results;
  const settled = !loading && results.query === q;

  function open(node: SearchHit) {
    if (node.kind === 'folder') navigate({ view: 'folder', id: node.id });
    else window.open(api.contentUrl(node.id), '_blank', 'noopener');
  }

  return (
    <section className="folder-view">
      <header className="toolbar">
        <nav className="crumbs">
          <h1 className="crumb current">{q.trim() ? <>Results for <q>{q}</q></> : 'Search'}</h1>
        </nav>
      </header>
      <div className={`file-list${loading ? ' is-loading' : ''}`} aria-busy={loading}>
        <div className="search-progress" aria-hidden="true" />
        {error && <p className="search-error" role="alert">{error}</p>}
        {!error && q.trim() && settled && items.length === 0 && (
          <p className="search-empty" role="status">No files or folders match <q>{q}</q>.</p>
        )}
        {items.length > 0 && (
          <ul className="rows plain search-results" aria-label="Search results">
            {items.map((node) => (
              <li key={node.id} className={`row${node.snippet ? ' has-snippet' : ''}`}>
                <div className="col name">
                  <Icon name={iconFor(node)} className={node.kind === 'folder' ? 'folder-icon' : 'file-icon'} />
                  <div className="result-main">
                    <button className="result-name ellipsis" onClick={() => open(node)} title={`Open ${node.name}`}>
                      {node.name}
                    </button>
                    {node.snippet && <Excerpt snippet={node.snippet} />}
                  </div>
                </div>
                <nav className="col location small muted ellipsis" aria-label={`Location of ${node.name}`}>
                  {node.path.map((crumb, i) => (
                    <span key={crumb.id}>
                      {i > 0 && ' / '}
                      <button className="link-muted" onClick={() => navigate({ view: 'folder', id: crumb.id })}>{crumb.name}</button>
                    </span>
                  ))}
                </nav>
                <div className="col date muted">{formatDate(node.updatedAt)}</div>
                <div className="col size muted">{node.kind === 'folder' ? '—' : formatBytes(node.size)}</div>
              </li>
            ))}
          </ul>
        )}
        {cursor && (
          <div className="load-more">
            <button className="btn" disabled={loading} onClick={() => void load(q, cursor)}>
              {loading ? 'Loading…' : 'Show more results'}
            </button>
          </div>
        )}
      </div>
    </section>
  );
}

/** The words found inside the file, in context. React escapes the text; marks are added by range. */
function Excerpt({ snippet }: { snippet: Snippet }) {
  const parts: ReactNode[] = [];
  let at = 0;
  for (const { start, end } of snippet.highlights) {
    if (start < at || end > snippet.text.length) continue; // overlapping or out of range: skip
    parts.push(snippet.text.slice(at, start), <mark key={start}>{snippet.text.slice(start, end)}</mark>);
    at = end;
  }
  parts.push(snippet.text.slice(at));
  return <p className="snippet small muted" title="Found inside the file">{parts}</p>;
}
