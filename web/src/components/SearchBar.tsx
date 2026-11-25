import { useEffect, useRef, useState, type FormEvent } from 'react';
import { exitSearch, navigate } from '../lib/router';
import { Icon } from './Icon';

/** Pause after the last keystroke before searching, so each word costs one request. */
const TYPING_DELAY_MS = 250;

/**
 * Top-of-page search box. Results follow what you type; Enter searches immediately.
 * "/" focuses it from anywhere, like most web apps. On the results page, clearing the box
 * (typing it empty, the × button, Esc, or submitting it empty) closes search and returns
 * to where it started.
 */
export function SearchBar({ current }: { current: string | null }) {
  const [value, setValue] = useState(current ?? '');
  const input = useRef<HTMLInputElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  // The query this box last sent to the URL, so its own updates aren't echoed back.
  const sent = useRef<string | null>(current);
  const searching = current !== null;

  // Follow the URL only when something else changed it (back/forward, leaving search).
  // Echoing our own update would overwrite keystrokes typed since, such as the space
  // before a second word, which the URL drops when it trims the query.
  useEffect(() => {
    if (current === sent.current) return;
    sent.current = current;
    clearTimeout(timer.current);
    setValue(current ?? '');
  }, [current]);

  useEffect(() => () => clearTimeout(timer.current), []);

  function search(raw: string) {
    clearTimeout(timer.current);
    const q = raw.trim();
    if (!q || q === current) return;
    sent.current = q;
    // The first query leaves a history entry; refining it replaces that entry, so Back
    // returns to where the search started.
    navigate({ view: 'search', q }, { replace: searching });
  }

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const typing = e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement;
      if (e.key === '/' && !typing && !e.metaKey && !e.ctrlKey) {
        e.preventDefault();
        input.current?.focus();
        input.current?.select();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  function close() {
    clearTimeout(timer.current);
    setValue('');
    if (searching) exitSearch();
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!value.trim()) return close();
    search(value);
  }

  return (
    <form role="search" className="search-bar" onSubmit={submit}>
      <Icon name="search" size={18} className="muted" />
      <input
        ref={input}
        type="search"
        aria-label="Search files and folders"
        placeholder="Search files and folders  ( / )"
        value={value}
        onChange={(e) => {
          const next = e.target.value;
          setValue(next);
          clearTimeout(timer.current);
          if (!next.trim()) {
            if (searching) exitSearch();
            return;
          }
          timer.current = setTimeout(() => search(next), TYPING_DELAY_MS);
        }}
        onKeyDown={(e) => {
          if (e.key !== 'Escape') return;
          e.preventDefault(); // stop the browser's own clear, which would skip close()
          if (searching) close();
          else setValue('');
          e.currentTarget.blur();
        }}
      />
      {(value || searching) && (
        <button type="button" className="icon-btn" aria-label={searching ? 'Close search' : 'Clear search'}
          onClick={() => { close(); input.current?.focus(); }}>
          <Icon name="close" size={16} />
        </button>
      )}
    </form>
  );
}
