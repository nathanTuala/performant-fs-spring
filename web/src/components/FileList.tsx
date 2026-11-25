import type { NodeDto, SortDir, SortKey } from '../api/types';
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type DragEvent, type KeyboardEvent, type MouseEvent } from 'react';
import { api } from '../api/client';
import { formatBytes, formatDate } from '../lib/format';
import { useExpansion, type Expansion, type TreeRow } from '../lib/useExpansion';
import { uploads } from '../upload/queue';
import type { Listing } from '../lib/useListing';
import { Icon, iconFor } from './Icon';

const ROW_HEIGHT = 44;
const OVERSCAN = 8;
/** Start fetching the next page this many rows before the end. */
const PREFETCH_ROWS = 60;
/** Indentation per level of an expanded folder. */
const INDENT = 22;
export const DRAG_MIME = 'application/x-pfs-items';

/** What a row drag carries: each item and the folder it came from, so a move can be undone. */
export interface MovedItem {
  id: string;
  parentId: string;
}

export const readDragItems = (dataTransfer: DataTransfer) => JSON.parse(dataTransfer.getData(DRAG_MIME)) as MovedItem[];

/** Each mutation resolves once the server has answered (success or not). */
export interface FileListActions {
  open: (node: NodeDto) => void;
  rename: (node: NodeDto, name: string) => Promise<void>;
  trash: (nodes: NodeDto[]) => Promise<void>;
  move: (items: MovedItem[], targetId: string) => Promise<void>;
}

interface Props {
  /** The folder being shown: where a drop on the list itself (not on a folder row) moves items. */
  folderId: string;
  listing: Listing;
  sort: SortKey;
  dir: SortDir;
  onSort: (sort: SortKey, dir: SortDir) => void;
  actions: FileListActions;
  emptyHint: string;
}

/**
 * Virtualised listing: only the rows in view (plus a little overscan) are in the DOM,
 * so a folder of 100k items scrolls like one of 10. Folders expand in place, Finder-style:
 * their contents are spliced in below them as indented rows of the same virtual list.
 * Render with a `key` per folder and sort so selection, scroll and expansion reset.
 */
export function FileList({ folderId, listing, sort, dir, onSort, actions, emptyHint }: Props) {
  const { items } = listing;
  const expansion = useExpansion(sort, dir);
  const { flatten } = expansion;
  const rows = useMemo(() => flatten(items), [flatten, items]);
  const nodeAt = (index: number) => {
    const row = rows[index];
    return row?.type === 'node' ? row.node : undefined;
  };
  const visibleNodes = useMemo(() => rows.flatMap((r) => (r.type === 'node' ? [r.node] : [])), [rows]);

  // Keep open folders in step with the rest of the list. The parent view edits and reloads
  // only the top level, so mirror its changes here: optimistically, then from the server.
  const { refresh } = expansion;
  const firstReload = useRef(listing.reloads);
  useEffect(() => {
    if (listing.reloads !== firstReload.current) refresh(); // undo, rollback, uploads landing
  }, [listing.reloads, refresh]);

  useEffect(() => {
    // Uploads finishing inside an open folder, batched so a big drop refreshes once.
    const pending = new Set<string>();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const off = uploads.onCompleted((node) => {
      if (!node.parentId) return;
      pending.add(node.parentId);
      clearTimeout(timer);
      timer = setTimeout(() => {
        refresh([...pending]);
        pending.clear();
      }, 300);
    });
    return () => { off(); clearTimeout(timer); };
  }, [refresh]);

  const trash = (nodes: NodeDto[]) => {
    const ids = nodes.map((n) => n.id);
    expansion.remove(ids);
    expansion.forget(ids); // a deleted folder's contents can't be listed any more
    void actions.trash(nodes).finally(() => refresh());
  };
  const move = (items: MovedItem[], targetId: string) => {
    expansion.remove(items.map((i) => i.id));
    void actions.move(items, targetId).finally(() => refresh());
  };
  const rename = async (node: NodeDto, name: string) => {
    expansion.rename(node.id, name);
    await actions.rename(node, name);
    refresh(); // the new name may sort elsewhere, or have been refused
  };
  const scroller = useRef<HTMLDivElement>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [viewport, setViewport] = useState(600);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [anchor, setAnchor] = useState<number | null>(null);
  const [focus, setFocus] = useState(0);
  const [renaming, setRenaming] = useState<string | null>(null);
  const [dropTarget, setDropTarget] = useState<string | null>(null);
  const [menu, setMenu] = useState<{ x: number; y: number; node: NodeDto } | null>(null);

  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const observer = new ResizeObserver(() => setViewport(el.clientHeight));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  const start = Math.max(0, Math.floor(scrollTop / ROW_HEIGHT) - OVERSCAN);
  const end = Math.min(rows.length, Math.ceil((scrollTop + viewport) / ROW_HEIGHT) + OVERSCAN);

  useEffect(() => {
    if (listing.hasMore && end >= rows.length - PREFETCH_ROWS) listing.loadMore();
  }, [end, rows.length, listing]);

  /**
   * The selection to act on, minus anything inside a selected folder: trashing or moving
   * the folder already takes its contents along, and acting on both would fail.
   */
  const selectedNodes = () => {
    const parentOf = new Map(rows.flatMap((r) => (r.type === 'node' ? [[r.node.id, r.parentId] as const] : [])));
    const insideSelection = (id: string) => {
      for (let p = parentOf.get(id); p; p = parentOf.get(p)) if (selected.has(p)) return true;
      return false;
    };
    return visibleNodes.filter((n) => selected.has(n.id) && !insideSelection(n.id));
  };

  function scrollIntoView(index: number) {
    const el = scroller.current;
    if (!el) return;
    const top = index * ROW_HEIGHT;
    if (top < el.scrollTop) el.scrollTop = top;
    else if (top + ROW_HEIGHT > el.scrollTop + el.clientHeight) el.scrollTop = top + ROW_HEIGHT - el.clientHeight;
  }

  function select(index: number, e: { shiftKey: boolean; metaKey: boolean; ctrlKey: boolean }) {
    const node = nodeAt(index);
    if (!node) return;
    if (e.shiftKey && anchor !== null) {
      const [a, b] = anchor < index ? [anchor, index] : [index, anchor];
      setSelected(new Set(rows.slice(a, b + 1).flatMap((r) => (r.type === 'node' ? [r.node.id] : []))));
    } else if (e.metaKey || e.ctrlKey) {
      const next = new Set(selected);
      if (next.has(node.id)) next.delete(node.id);
      else next.add(node.id);
      setSelected(next);
      setAnchor(index);
    } else {
      setSelected(new Set([node.id]));
      setAnchor(index);
    }
    setFocus(index);
  }

  /** Selects just the item at `index` and keeps it in view. */
  function moveFocus(index: number) {
    select(index, { shiftKey: false, metaKey: false, ctrlKey: false });
    scrollIntoView(index);
  }

  function onKeyDown(e: KeyboardEvent) {
    if (renaming) return;
    const node = nodeAt(focus);
    const row = rows[focus];
    switch (e.key) {
      case 'ArrowDown':
      case 'ArrowUp': {
        e.preventDefault();
        const step = e.key === 'ArrowDown' ? 1 : -1;
        // Skip status lines ("Loading…", "Empty folder"): only items take focus.
        let next = focus + step;
        while (next >= 0 && next < rows.length && !nodeAt(next)) next += step;
        if (next < 0 || next >= rows.length) break;
        if (e.shiftKey) {
          select(next, { shiftKey: true, metaKey: false, ctrlKey: false });
          scrollIntoView(next);
        } else {
          moveFocus(next);
        }
        break;
      }
      case 'ArrowRight':
        // Open a closed folder; on an open one, step into its first item.
        if (node?.kind === 'folder' && row?.type === 'node') {
          e.preventDefault();
          if (!row.expanded) expansion.expand(node.id);
          else if (nodeAt(focus + 1)) moveFocus(focus + 1);
        }
        break;
      case 'ArrowLeft': {
        // Close an open folder; otherwise jump to the folder this item sits in.
        if (row?.type !== 'node') break;
        e.preventDefault();
        if (row.expanded) {
          expansion.collapse(row.node.id);
        } else if (row.parentId) {
          const parentIndex = rows.findIndex((r) => r.type === 'node' && r.node.id === row.parentId);
          if (parentIndex >= 0) moveFocus(parentIndex);
        }
        break;
      }
      case 'Enter':
        if (node) actions.open(node);
        break;
      case 'F2':
        if (node) setRenaming(node.id);
        break;
      case 'Delete':
      case 'Backspace': {
        const nodes = selectedNodes();
        if (nodes.length) {
          e.preventDefault();
          trash(nodes);
          setSelected(new Set());
        }
        break;
      }
      case 'a':
        if (e.metaKey || e.ctrlKey) {
          e.preventDefault();
          setSelected(new Set(visibleNodes.map((n) => n.id)));
        }
        break;
      case 'Escape':
        setSelected(new Set());
        break;
    }
  }

  // ------------------------------------------------------------ drag to move

  // What's being dragged from this list. The browser hides drag data until the drop, so this
  // is how a drag-over knows whether a target would actually change anything.
  const dragged = useRef<MovedItem[]>([]);

  function onDragStart(e: DragEvent, node: NodeDto) {
    const nodes = selected.has(node.id) ? selectedNodes() : [node];
    if (!selected.has(node.id)) setSelected(new Set([node.id]));
    const items: MovedItem[] = nodes.map((n) => ({ id: n.id, parentId: n.parentId! }));
    dragged.current = items;
    e.dataTransfer.setData(DRAG_MIME, JSON.stringify(items));
    e.dataTransfer.effectAllowed = 'move';
  }

  /**
   * Where a drop on a row goes, as in Finder: onto a folder → into it; onto a file → into the
   * folder that file is in, which for a file inside an expanded folder means dragging it out
   * to the level it's dropped at. A top-level file stands for the folder being shown.
   */
  const dropFolderFor = (row: Extract<TreeRow, { type: 'node' }>) =>
    row.node.kind === 'folder' ? row.node.id : (row.parentId ?? folderId);

  function canDropInto(e: DragEvent, targetId: string): boolean {
    if (!e.dataTransfer.types.includes(DRAG_MIME)) return false;
    const items = dragged.current;
    // Not into itself (deeper cycles are refused by the server), and not where everything already is.
    if (items.some((i) => i.id === targetId)) return false;
    return items.length === 0 || items.some((i) => i.parentId !== targetId);
  }

  function onDragOver(e: DragEvent, targetId: string) {
    if (!e.dataTransfer.types.includes(DRAG_MIME)) return;
    e.preventDefault();
    e.stopPropagation();
    const ok = canDropInto(e, targetId);
    e.dataTransfer.dropEffect = ok ? 'move' : 'none';
    setDropTarget(ok ? targetId : null);
  }

  function onDrop(e: DragEvent, targetId: string) {
    setDropTarget(null);
    if (!canDropInto(e, targetId)) return;
    e.preventDefault();
    e.stopPropagation();
    const items = readDragItems(e.dataTransfer).filter((i) => i.parentId !== targetId);
    if (items.length > 0) move(items, targetId);
    setSelected(new Set());
  }

  function onContextMenu(e: MouseEvent, index: number) {
    e.preventDefault();
    const node = nodeAt(index);
    if (!node) return;
    if (!selected.has(node.id)) select(index, { shiftKey: false, metaKey: false, ctrlKey: false });
    setMenu({ x: e.clientX, y: e.clientY, node });
  }

  const allSelected = visibleNodes.length > 0 && selected.size === visibleNodes.length;
  const header = (key: SortKey, label: string, className: string) => (
    <button
      className={`col ${className} sort ${sort === key ? 'active' : ''}`}
      onClick={() => onSort(key, sort === key && dir === 'asc' ? 'desc' : 'asc')}
      aria-sort={sort === key ? (dir === 'asc' ? 'ascending' : 'descending') : 'none'}
    >
      {label}
      {sort === key && <span aria-hidden="true">{dir === 'asc' ? ' ↑' : ' ↓'}</span>}
    </button>
  );

  return (
    <div className="file-list" onClick={() => setMenu(null)}>
      <div className="list-head">
        <div className="row head" role="row">
          <label className="col check">
            <input type="checkbox" aria-label="Select all" checked={allSelected}
              onChange={() => setSelected(allSelected ? new Set() : new Set(visibleNodes.map((n) => n.id)))} />
          </label>
          {header('name', 'Name', 'name')}
          {header('modified', 'Modified', 'date')}
          {header('size', 'Size', 'size')}
        </div>
        {/* Overlays the column header, so selecting never shifts the rows under the pointer. */}
        {selected.size > 0 && (
          <div className="bulk-bar" role="toolbar" aria-label="Selection actions">
            <span>{selected.size} selected</span>
            {selected.size === 1 && selectedNodes()[0]?.kind === 'file' && (
              <a className="btn ghost" href={api.contentUrl(selectedNodes()[0]!.id, true)}>
                <Icon name="download" size={16} /> Download
              </a>
            )}
            <button className="btn ghost" onClick={() => { trash(selectedNodes()); setSelected(new Set()); }}>
              <Icon name="trash" size={16} /> Delete
            </button>
            <button className="btn ghost" onClick={() => setSelected(new Set())}>Clear</button>
          </div>
        )}
      </div>

      <div
        ref={scroller}
        className={`rows${dropTarget === folderId ? ' drop-target' : ''}`}
        role="treegrid"
        aria-rowcount={rows.length}
        aria-label="Files"
        tabIndex={0}
        onKeyDown={onKeyDown}
        onScroll={(e) => setScrollTop(e.currentTarget.scrollTop)}
        // Empty space in the list (and anything not handled by a row) means "this folder".
        onDragOver={(e) => onDragOver(e, folderId)}
        onDragLeave={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node)) setDropTarget(null); }}
        onDrop={(e) => onDrop(e, folderId)}
        onDragEnd={() => { dragged.current = []; setDropTarget(null); }}
      >
        {items.length === 0 && !listing.loading && <div className="empty">{listing.error ?? emptyHint}</div>}
        {items.length === 0 && listing.loading && <SkeletonRows />}
        <div style={{ height: rows.length * ROW_HEIGHT, position: 'relative' }}>
          {rows.slice(start, end).map((row, i) => {
            const index = start + i;
            const style = { position: 'absolute', top: index * ROW_HEIGHT, height: ROW_HEIGHT, left: 0, right: 0 } as const;
            if (row.type === 'status') {
              return <StatusRow key={`${row.parentId}:${row.status}`} row={row} style={style} expansion={expansion} />;
            }
            const { node, depth } = row;
            const isSelected = selected.has(node.id);
            return (
              <div
                key={`${row.parentId}:${node.id}`}
                role="row"
                aria-rowindex={index + 1}
                aria-level={depth + 1}
                aria-expanded={node.kind === 'folder' ? row.expanded : undefined}
                aria-selected={isSelected}
                className={`row${isSelected ? ' selected' : ''}${focus === index ? ' focused' : ''}${dropTarget === node.id ? ' drop-target' : ''}`}
                style={style}
                draggable={renaming !== node.id}
                onDragStart={(e) => onDragStart(e, node)}
                onDragOver={(e) => onDragOver(e, dropFolderFor(row))}
                onDrop={(e) => onDrop(e, dropFolderFor(row))}
                onClick={(e) => select(index, e)}
                onDoubleClick={() => actions.open(node)}
                onContextMenu={(e) => onContextMenu(e, index)}
              >
                <label className="col check" onClick={(e) => e.stopPropagation()}>
                  <input type="checkbox" aria-label={`Select ${node.name}`} checked={isSelected}
                    onChange={() => select(index, { shiftKey: false, metaKey: true, ctrlKey: false })} />
                </label>
                <div className="col name" style={{ paddingLeft: depth * INDENT }}>
                  {node.kind === 'folder' ? (
                    <button
                      className={`disclosure${row.expanded ? ' open' : ''}`}
                      aria-label={`${row.expanded ? 'Collapse' : 'Expand'} ${node.name}`}
                      tabIndex={-1}
                      onClick={(e) => { e.stopPropagation(); expansion.toggle(node.id); }}
                      onDoubleClick={(e) => e.stopPropagation()}
                    >
                      <Icon name="chevron" size={14} />
                    </button>
                  ) : (
                    <span className="disclosure-spacer" aria-hidden="true" />
                  )}
                  <Icon name={iconFor(node)} className={node.kind === 'folder' ? 'folder-icon' : 'file-icon'} />
                  {renaming === node.id ? (
                    <RenameInput node={node} onDone={async (name) => {
                      setRenaming(null);
                      if (name && name !== node.name) await rename(node, name);
                      scroller.current?.focus();
                    }} />
                  ) : (
                    <span className="ellipsis">{node.name}</span>
                  )}
                </div>
                <div className="col date muted">{formatDate(node.updatedAt)}</div>
                <div className="col size muted">{node.kind === 'folder' ? '—' : formatBytes(node.size)}</div>
              </div>
            );
          })}
        </div>
      </div>

      {menu && (
        <ContextMenu x={menu.x} y={menu.y} onClose={() => setMenu(null)} items={[
          { label: 'Open', run: () => actions.open(menu.node) },
          ...(menu.node.kind === 'file'
            ? [{ label: 'Download', run: () => { window.location.href = api.contentUrl(menu.node.id, true); } }]
            : []),
          { label: 'Rename', run: () => setRenaming(menu.node.id) },
          { label: 'Delete', run: () => { trash(selectedNodes().length ? selectedNodes() : [menu.node]); setSelected(new Set()); } },
        ]} />
      )}
    </div>
  );
}

const STATUS_TEXT: Record<Exclude<Extract<TreeRow, { type: 'status' }>['status'], 'more' | 'error'>, string> = {
  loading: 'Loading…',
  empty: 'Empty folder',
};

/** The line under an expanded folder that isn't an item: loading, empty, failed, or more. */
function StatusRow({ row, style, expansion }: {
  row: Extract<TreeRow, { type: 'status' }>;
  style: CSSProperties;
  expansion: Expansion;
}) {
  return (
    <div role="row" aria-level={row.depth + 1} className="row status-row" style={style}>
      <span className="col check" />
      <div className="col name small muted" style={{ paddingLeft: row.depth * INDENT + 26 }}>
        {row.status === 'more' && (
          <button className="link" onClick={() => expansion.loadMore(row.parentId)}>Show more</button>
        )}
        {row.status === 'error' && (
          <>Couldn't load this folder. <button className="link" onClick={() => expansion.refresh([row.parentId])}>Retry</button></>
        )}
        {(row.status === 'loading' || row.status === 'empty') && STATUS_TEXT[row.status]}
      </div>
    </div>
  );
}

function RenameInput({ node, onDone }: { node: NodeDto; onDone: (name: string | null) => void }) {
  const ref = useRef<HTMLInputElement>(null);
  const finished = useRef(false);
  // Enter, Escape and blur can all fire for one edit; only the first one counts.
  const finish = (name: string | null) => {
    if (finished.current) return;
    finished.current = true;
    onDone(name);
  };
  useEffect(() => {
    const input = ref.current!;
    input.focus();
    // Select the stem, not the extension, like every desktop file manager.
    const dot = node.kind === 'file' ? node.name.lastIndexOf('.') : -1;
    input.setSelectionRange(0, dot > 0 ? dot : node.name.length);
  }, [node]);
  return (
    <input
      ref={ref}
      className="rename"
      defaultValue={node.name}
      aria-label="New name"
      onClick={(e) => e.stopPropagation()}
      onKeyDown={(e) => {
        e.stopPropagation();
        if (e.key === 'Enter') finish(e.currentTarget.value.trim());
        if (e.key === 'Escape') finish(null);
      }}
      onBlur={(e) => finish(e.currentTarget.value.trim())}
    />
  );
}

function ContextMenu({ x, y, items, onClose }: { x: number; y: number; items: { label: string; run: () => void }[]; onClose: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    ref.current?.querySelector('button')?.focus();
    const close = () => onClose();
    window.addEventListener('blur', close);
    window.addEventListener('resize', close);
    return () => {
      window.removeEventListener('blur', close);
      window.removeEventListener('resize', close);
    };
  }, [onClose]);
  return (
    <div ref={ref} className="menu" role="menu" style={{ left: x, top: y }}
      onKeyDown={(e) => e.key === 'Escape' && onClose()}>
      {items.map((item) => (
        <button key={item.label} role="menuitem" onClick={() => { onClose(); item.run(); }}>{item.label}</button>
      ))}
    </div>
  );
}

function SkeletonRows() {
  return (
    <div aria-hidden="true">
      {Array.from({ length: 8 }, (_, i) => (
        <div key={i} className="row skeleton"><div className="bar" style={{ width: `${30 + ((i * 37) % 40)}%` }} /></div>
      ))}
    </div>
  );
}
