import type { NodeDto, SortDir, SortKey } from '../api/types';
import { useCallback, useRef, useState } from 'react';
import { api, ApiError } from '../api/client';

const PAGE = 100;
/** A re-fetch never asks for more than this many rows of one folder at once. */
const MAX_REFRESH = 500;
/** Deeper than this is almost certainly a stale cache describing a cycle; stop walking. */
const MAX_DEPTH = 32;

/** One line of the flattened tree: an item, or a status line under an expanded folder. */
export type TreeRow =
  | { type: 'node'; node: NodeDto; depth: number; parentId: string | null; expanded: boolean }
  | { type: 'status'; parentId: string; depth: number; status: 'loading' | 'empty' | 'error' | 'more' };

interface Group {
  items: NodeDto[];
  cursor: string | null;
  loading: boolean;
  error: string | null;
}

export interface Expansion {
  isExpanded: (id: string) => boolean;
  toggle: (id: string) => void;
  expand: (id: string) => void;
  collapse: (id: string) => void;
  loadMore: (id: string) => void;
  /** Re-fetches expanded folders (all of them, or just `ids`) after something changed. */
  refresh: (ids?: string[]) => void;
  /** Optimistic edits to the cached contents, mirroring what the server is being asked to do. */
  remove: (ids: string[]) => void;
  rename: (id: string, name: string) => void;
  /** The top-level items with every expanded folder's contents spliced in below it. */
  flatten: (top: NodeDto[]) => TreeRow[];
}

/**
 * Finder-style disclosure state for a file list: which folders are open and what they
 * contain. Contents load on first expand (one keyset page, more on demand) and stay
 * cached while collapsed, so re-opening is instant. Sorted like the list it belongs to.
 */
export function useExpansion(sort: SortKey, dir: SortDir): Expansion {
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());
  const [groups, setGroups] = useState<ReadonlyMap<string, Group>>(new Map());
  const groupsRef = useRef(groups);
  groupsRef.current = groups;
  // The latest request per folder; an older response for the same folder is dropped.
  const tokens = useRef(new Map<string, number>());
  const expandedRef = useRef(expanded);
  expandedRef.current = expanded;

  const setGroup = (id: string, fn: (g: Group | undefined) => Group | undefined) =>
    setGroups((prev) => {
      const next = new Map(prev);
      const value = fn(prev.get(id));
      if (value) next.set(id, value);
      else next.delete(id);
      return next;
    });

  const fetchGroup = useCallback(
    async (id: string, mode: 'replace' | 'append', limit = PAGE) => {
      const token = (tokens.current.get(id) ?? 0) + 1;
      tokens.current.set(id, token);
      const from = mode === 'append' ? (groupsRef.current.get(id)?.cursor ?? null) : null;
      setGroup(id, (g) => ({ items: g?.items ?? [], cursor: g?.cursor ?? null, error: null, loading: true }));
      try {
        const page = await api.children(id, { cursor: from, limit, sort, dir });
        if (tokens.current.get(id) !== token) return;
        setGroup(id, (g) => ({
          items: mode === 'append' ? [...(g?.items ?? []), ...page.items] : page.items,
          cursor: page.nextCursor,
          loading: false,
          error: null,
        }));
      } catch (err) {
        if (tokens.current.get(id) !== token) return;
        if (err instanceof ApiError && err.status === 404) {
          // The folder was deleted or moved out of reach: forget it.
          setExpanded((prev) => without(prev, id));
          setGroup(id, () => undefined);
          return;
        }
        setGroup(id, (g) => ({ items: g?.items ?? [], cursor: g?.cursor ?? null, loading: false, error: 'failed' }));
      }
    },
    [sort, dir],
  );

  const expand = useCallback(
    (id: string) => {
      setExpanded((prev) => (prev.has(id) ? prev : new Set(prev).add(id)));
      if (!groupsRef.current.has(id)) void fetchGroup(id, 'replace');
    },
    [fetchGroup],
  );

  const collapse = useCallback((id: string) => setExpanded((prev) => without(prev, id)), []);

  const toggle = useCallback(
    (id: string) => (expandedRef.current.has(id) ? collapse(id) : expand(id)),
    [collapse, expand],
  );

  const loadMore = useCallback(
    (id: string) => {
      const g = groupsRef.current.get(id);
      if (g?.cursor && !g.loading) void fetchGroup(id, 'append');
    },
    [fetchGroup],
  );

  const refresh = useCallback(
    (ids?: string[]) => {
      for (const id of ids ?? [...expandedRef.current]) {
        const cached = groupsRef.current.get(id);
        if (!cached) continue;
        void fetchGroup(id, 'replace', Math.min(MAX_REFRESH, Math.max(PAGE, cached.items.length)));
      }
    },
    [fetchGroup],
  );

  const remove = useCallback((ids: string[]) => {
    const gone = new Set(ids);
    setGroups((prev) => {
      const next = new Map<string, Group>();
      for (const [id, g] of prev) next.set(id, { ...g, items: g.items.filter((n) => !gone.has(n.id)) });
      return next;
    });
  }, []);

  const rename = useCallback((id: string, name: string) => {
    setGroups((prev) => {
      const next = new Map<string, Group>();
      for (const [key, g] of prev) next.set(key, { ...g, items: g.items.map((n) => (n.id === id ? { ...n, name } : n)) });
      return next;
    });
  }, []);

  const flatten = useCallback(
    (top: NodeDto[]): TreeRow[] => {
      const rows: TreeRow[] = [];
      const walk = (items: NodeDto[], depth: number, parentId: string | null, ancestors: ReadonlySet<string>) => {
        for (const node of items) {
          const open = node.kind === 'folder' && expanded.has(node.id) && !ancestors.has(node.id) && depth < MAX_DEPTH;
          rows.push({ type: 'node', node, depth, parentId, expanded: open });
          if (!open) continue;
          const g = groups.get(node.id);
          const childDepth = depth + 1;
          if (!g || (g.loading && g.items.length === 0)) {
            rows.push({ type: 'status', parentId: node.id, depth: childDepth, status: 'loading' });
            continue;
          }
          walk(g.items, childDepth, node.id, new Set(ancestors).add(node.id));
          if (g.error) rows.push({ type: 'status', parentId: node.id, depth: childDepth, status: 'error' });
          else if (g.items.length === 0) rows.push({ type: 'status', parentId: node.id, depth: childDepth, status: 'empty' });
          else if (g.cursor) rows.push({ type: 'status', parentId: node.id, depth: childDepth, status: 'more' });
        }
      };
      walk(top, 0, null, new Set());
      return rows;
    },
    [expanded, groups],
  );

  return {
    isExpanded: (id) => expanded.has(id),
    toggle,
    expand,
    collapse,
    loadMore,
    refresh,
    remove,
    rename,
    flatten,
  };
}

function without(set: ReadonlySet<string>, id: string): ReadonlySet<string> {
  if (!set.has(id)) return set;
  const next = new Set(set);
  next.delete(id);
  return next;
}
