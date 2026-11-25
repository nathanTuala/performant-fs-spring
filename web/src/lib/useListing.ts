import type { NodeDto, SortDir, SortKey } from '../api/types';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api/client';

const PAGE = 100;

export interface Listing {
  items: NodeDto[];
  loading: boolean;
  error: string | null;
  hasMore: boolean;
  loadMore: () => void;
  /** Re-fetches everything loaded so far (after uploads land, or to roll back an optimistic change). */
  reload: () => void;
  /** Bumped by every reload(), so views showing more than this folder can refresh too. */
  reloads: number;
  /** Optimistic local edit. */
  update: (fn: (items: NodeDto[]) => NodeDto[]) => void;
}

/** Keyset-paginated folder listing. Pages are fetched as the user scrolls. */
export function useListing(folderId: string | null, sort: SortKey, dir: SortDir): Listing {
  const [items, setItems] = useState<NodeDto[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Responses from an older folder/sort, or overtaken by a reload, are dropped.
  const generation = useRef(0);
  const inFlight = useRef(false);
  // A ref, not state: it must be current the instant a page lands. With state, a scroll
  // render that is already queued would call loadMore with the old cursor and fetch the
  // same page twice.
  const cursor = useRef<string | null>(null);

  const fetchPage = useCallback(
    async (from: string | null, limit: number, replace: boolean) => {
      if (!folderId) return;
      const gen = generation.current;
      inFlight.current = true;
      setLoading(true);
      try {
        const page = await api.children(folderId, { cursor: from, limit, sort, dir });
        if (gen !== generation.current) return;
        cursor.current = page.nextCursor;
        setItems((prev) => (replace ? page.items : [...prev, ...page.items]));
        setHasMore(page.nextCursor !== null);
        setError(null);
      } catch (err) {
        if (gen === generation.current) setError(err instanceof Error ? err.message : 'Failed to load');
      } finally {
        if (gen === generation.current) {
          inFlight.current = false;
          setLoading(false);
        }
      }
    },
    [folderId, sort, dir],
  );

  useEffect(() => {
    generation.current++;
    setItems([]);
    cursor.current = null;
    setHasMore(false);
    void fetchPage(null, PAGE, true);
  }, [fetchPage]);

  const loadMore = useCallback(() => {
    if (!inFlight.current && cursor.current) void fetchPage(cursor.current, PAGE, false);
  }, [fetchPage]);

  const itemCount = useRef(0);
  itemCount.current = items.length;
  const [reloads, setReloads] = useState(0);
  const reload = useCallback(() => {
    generation.current++;
    setReloads((n) => n + 1);
    void fetchPage(null, Math.min(500, Math.max(PAGE, itemCount.current)), true);
  }, [fetchPage]);

  return { items, loading, error, hasMore, loadMore, reload, reloads, update: setItems };
}
