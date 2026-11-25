import type { Crumb, NodeDto, SortDir, SortKey, Usage, User } from './api/types';
import { useCallback, useEffect, useRef, useState, type DragEvent } from 'react';
import { api } from './api/client';
import { useDialog } from './components/Dialog';
import { DRAG_MIME, FileList, readDragItems, type FileListActions, type MovedItem } from './components/FileList';
import { Icon } from './components/Icon';
import { Login } from './components/Login';
import { SearchBar } from './components/SearchBar';
import { SearchView } from './components/SearchView';
import { Toasts } from './components/Toasts';
import { UploadTray } from './components/UploadTray';
import { formatBytes, formatDate } from './lib/format';
import { navigate, useRoute } from './lib/router';
import { toast, toastError } from './lib/toast';
import { useListing } from './lib/useListing';
import { uploadTree } from './upload/enqueue';
import { uploads } from './upload/queue';
import { entriesFromDrop, treeFromInput, walkEntries } from './upload/walker';

export function App() {
  const [user, setUser] = useState<User | null | undefined>(undefined);
  useEffect(() => {
    api.me().then(setUser, () => setUser(null));
  }, []);
  if (user === undefined) return null;
  if (user === null) return <Login onSignedIn={setUser} />;
  return <Shell user={user} onSignOut={() => setUser(null)} />;
}

function Shell({ user, onSignOut }: { user: User; onSignOut: () => void }) {
  const route = useRoute();
  const [usage, setUsage] = useState<Usage | null>(null);
  const refreshUsage = useCallback(() => void api.stats().then(setUsage, () => {}), []);
  useEffect(refreshUsage, [refreshUsage]);

  // Uploads can land in any folder (a dropped tree fills many), so count them here,
  // once per burst, rather than only in the folder on screen.
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const off = uploads.onCompleted(() => {
      clearTimeout(timer);
      timer = setTimeout(refreshUsage, 400);
    });
    return () => { off(); clearTimeout(timer); };
  }, [refreshUsage]);

  async function signOut() {
    uploads.cancelAll();
    await api.logout().catch(() => {});
    onSignOut();
  }

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand"><Icon name="folder" size={22} /> Files</div>
        <nav aria-label="Main">
          <button className={`nav-item ${route.view === 'folder' ? 'active' : ''}`} onClick={() => navigate({ view: 'folder', id: null })}>
            <Icon name="home" /> My Files
          </button>
          <button className={`nav-item ${route.view === 'trash' ? 'active' : ''}`} onClick={() => navigate({ view: 'trash' })}>
            <Icon name="trash" /> Trash
          </button>
        </nav>
        {usage && (
          <div className="usage">
            <span className="small muted">{formatBytes(usage.fileBytes)} used</span>
          </div>
        )}
        {user.signInRequired !== false && (
          <div className="account">
            <span className="small muted ellipsis" title={user.email}>{user.email}</span>
            <button className="icon-btn" aria-label="Sign out" onClick={signOut}><Icon name="logout" size={16} /></button>
          </div>
        )}
      </aside>
      <main className="main">
        <div className="topbar">
          <SearchBar current={route.view === 'search' ? route.q : null} />
        </div>
        {route.view === 'trash' && <TrashView onChanged={refreshUsage} />}
        {route.view === 'search' && <SearchView q={route.q} />}
        {route.view === 'folder' && (
          <FolderView key={route.id ?? user.rootId} folderId={route.id ?? user.rootId} rootId={user.rootId} onChanged={refreshUsage} />
        )}
      </main>
      <UploadTray />
      <Toasts />
    </div>
  );
}

// ------------------------------------------------------------------ folder view

function FolderView({ folderId, rootId, onChanged }: { folderId: string; rootId: string; onChanged: () => void }) {
  const [sort, setSort] = useState<{ key: SortKey; dir: SortDir }>({ key: 'name', dir: 'asc' });
  const listing = useListing(folderId, sort.key, sort.dir);
  const [path, setPath] = useState<Crumb[]>([]);
  const [folderName, setFolderName] = useState('');
  const [dragging, setDragging] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const folderInput = useRef<HTMLInputElement>(null);
  const lastMove = useRef<MovedItem[] | null>(null);
  const dialog = useDialog();

  useEffect(() => {
    api.node(folderId).then(
      (node) => {
        setPath(node.path);
        setFolderName(node.name);
        document.title = `${node.name} – Files`;
      },
      () => navigate({ view: 'folder', id: null }),
    );
  }, [folderId]);

  // Finished uploads into this folder appear without a manual refresh (batched).
  const { reload } = listing;
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const off = uploads.onCompleted((node) => {
      if (node.parentId !== folderId) return;
      clearTimeout(timer);
      timer = setTimeout(reload, 300);
    });
    return () => { off(); clearTimeout(timer); };
  }, [folderId, reload]);

  /**
   * Puts the last moved items back where each one came from. With folders expanded in
   * place, one move can gather items from several folders, so they return per origin.
   */
  const undoMove = useCallback(async () => {
    const last = lastMove.current;
    if (!last) return;
    lastMove.current = null;
    const byOrigin = new Map<string, string[]>();
    for (const { id, parentId } of last) byOrigin.set(parentId, [...(byOrigin.get(parentId) ?? []), id]);
    try {
      for (const [parentId, ids] of byOrigin) await api.move(ids, parentId);
      toast('Move undone');
    } catch (err) {
      toastError(err);
    }
    listing.reload();
  }, [listing]);

  const move = useCallback(async (items: MovedItem[], targetId: string) => {
    items = items.filter((i) => i.parentId !== targetId); // already there
    if (items.length === 0) return;
    const ids = new Set(items.map((i) => i.id));
    listing.update((list) => list.filter((n) => !ids.has(n.id))); // optimistic
    try {
      await api.move([...ids], targetId);
      lastMove.current = items;
      // Dragged out of an expanded folder into this one: they belong in this listing now.
      if (targetId === folderId) listing.reload();
      toast(`Moved ${ids.size} item${ids.size === 1 ? '' : 's'}`, { action: { label: 'Undo', run: () => void undoMove() } });
    } catch (err) {
      listing.reload(); // roll back
      toastError(err);
    }
  }, [folderId, listing, undoMove]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'z' && !(e.target instanceof HTMLInputElement)) {
        e.preventDefault();
        void undoMove();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [undoMove]);

  const actions: FileListActions = {
    open: (node) => {
      if (node.kind === 'folder') navigate({ view: 'folder', id: node.id });
      else window.open(api.contentUrl(node.id), '_blank', 'noopener');
    },
    rename: async (node, name) => {
      listing.update((items) => items.map((n) => (n.id === node.id ? { ...n, name } : n)));
      try {
        await api.rename(node.id, name);
      } catch (err) {
        listing.reload();
        toastError(err);
      }
    },
    trash: async (nodes) => {
      const ids = new Set(nodes.map((n) => n.id));
      listing.update((items) => items.filter((n) => !ids.has(n.id)));
      const results = await Promise.allSettled(nodes.map((n) => api.trash(n.id)));
      onChanged(); // whatever made it to Trash no longer counts as used
      const failed = results.filter((r) => r.status === 'rejected');
      if (failed.length) {
        listing.reload();
        toastError((failed[0] as PromiseRejectedResult).reason);
        return;
      }
      toast(`Moved ${nodes.length} item${nodes.length === 1 ? '' : 's'} to trash`, {
        action: {
          label: 'Undo',
          run: async () => {
            await Promise.allSettled(nodes.map((n) => api.restore(n.id)));
            listing.reload();
            onChanged();
          },
        },
      });
    },
    move: (items, targetId) => move(items, targetId),
  };

  async function newFolder() {
    const name = await dialog.prompt('New folder', 'Untitled folder');
    if (!name) return;
    try {
      await api.createFolder(folderId, name, 'rename');
      listing.reload();
    } catch (err) {
      toastError(err);
    }
  }

  // Whole-page drop target for files and folders from the desktop.
  function isExternalDrag(e: DragEvent) {
    return e.dataTransfer.types.includes('Files') && !e.dataTransfer.types.includes(DRAG_MIME);
  }
  function onDrop(e: DragEvent) {
    if (!isExternalDrag(e)) return;
    e.preventDefault();
    setDragging(false);
    const entries = entriesFromDrop(e.dataTransfer); // must run before the handler returns
    void walkEntries(entries).then((tree) => uploadTree(folderId, tree)).then(reload, toastError);
  }

  return (
    <section
      className="folder-view"
      onDragOver={(e) => { if (isExternalDrag(e)) { e.preventDefault(); setDragging(true); } }}
      onDragLeave={(e) => { if (e.currentTarget === e.target || !e.currentTarget.contains(e.relatedTarget as Node)) setDragging(false); }}
      onDrop={onDrop}
    >
      <header className="toolbar">
        <Breadcrumbs path={path} current={folderName} rootId={rootId} onMove={(items, target) => void move(items, target)} />
        <div className="toolbar-actions">
          <button className="btn" onClick={newFolder}><Icon name="folderPlus" size={16} /> New folder</button>
          <button className="btn" onClick={() => folderInput.current?.click()}><Icon name="upload" size={16} /> Upload folder</button>
          <button className="btn primary" onClick={() => fileInput.current?.click()}><Icon name="upload" size={16} /> Upload files</button>
          <input ref={fileInput} type="file" multiple hidden onChange={(e) => {
            if (e.target.files) uploads.enqueue([...e.target.files].map((file) => ({ file, parentId: folderId })));
            e.target.value = '';
          }} />
          <input ref={folderInput} type="file" hidden
            {...({ webkitdirectory: '', directory: '' } as Record<string, string>)}
            onChange={(e) => {
              if (e.target.files) void uploadTree(folderId, treeFromInput(e.target.files)).then(reload, toastError);
              e.target.value = '';
            }} />
        </div>
      </header>
      <FileList
        key={`${folderId}:${sort.key}:${sort.dir}`}
        folderId={folderId}
        listing={listing}
        sort={sort.key}
        dir={sort.dir}
        onSort={(key, dir) => setSort({ key, dir })}
        actions={actions}
        emptyHint="This folder is empty. Drop files or folders here, or use Upload."
      />
      {dialog.element}
      {dragging && (
        <div className="drop-overlay" aria-hidden="true">
          <Icon name="upload" size={40} />
          <p>Drop to upload to <strong>{folderName}</strong></p>
        </div>
      )}
    </section>
  );
}

function Breadcrumbs({ path, current, rootId, onMove }: {
  path: Crumb[]; current: string; rootId: string; onMove: (items: MovedItem[], target: string) => void;
}) {
  const [over, setOver] = useState<string | null>(null);
  return (
    <nav className="crumbs" aria-label="Folder path">
      {path.map((crumb) => (
        <span key={crumb.id} className="crumb-wrap">
          <button
            className={`crumb ${over === crumb.id ? 'drop-target' : ''}`}
            onClick={() => navigate({ view: 'folder', id: crumb.id === rootId ? null : crumb.id })}
            onDragOver={(e) => { if (e.dataTransfer.types.includes(DRAG_MIME)) { e.preventDefault(); setOver(crumb.id); } }}
            onDragLeave={() => setOver(null)}
            onDrop={(e) => {
              setOver(null);
              if (!e.dataTransfer.types.includes(DRAG_MIME)) return;
              e.preventDefault();
              e.stopPropagation();
              onMove(readDragItems(e.dataTransfer), crumb.id);
            }}
          >
            {crumb.name}
          </button>
          <Icon name="chevron" size={14} className="muted" />
        </span>
      ))}
      <h1 className="crumb current">{current}</h1>
    </nav>
  );
}

// ------------------------------------------------------------------ trash

function TrashView({ onChanged }: { onChanged: () => void }) {
  const [items, setItems] = useState<NodeDto[] | null>(null);
  const dialog = useDialog();
  const load = useCallback(() => void api.listTrash().then((r) => setItems(r.items), toastError), []);
  useEffect(() => {
    document.title = 'Trash – Files';
    load();
  }, [load]);

  async function run(action: Promise<unknown>, message: string) {
    try {
      await action;
      toast(message);
    } catch (err) {
      toastError(err);
    }
    load();
    onChanged();
  }

  return (
    <section className="folder-view">
      <header className="toolbar">
        <nav className="crumbs"><h1 className="crumb current">Trash</h1></nav>
        <div className="toolbar-actions">
          <span className="small muted">Items are deleted forever after 30 days</span>
          <button className="btn" disabled={!items?.length} onClick={async () => {
            if (await dialog.confirm('Empty trash?', 'Everything in the trash will be deleted forever. This cannot be undone.', 'Empty trash')) {
              void run(api.emptyTrash(), 'Trash emptied');
            }
          }}>Empty trash</button>
        </div>
      </header>
      <div className="file-list">
        {items === null ? null : items.length === 0 ? (
          <div className="empty">Trash is empty</div>
        ) : (
          <ul className="rows plain" aria-label="Trashed items">
            {items.map((node) => (
              <li key={node.id} className="row">
                <div className="col name">
                  <Icon name={node.kind === 'folder' ? 'folder' : 'file'} className={node.kind === 'folder' ? 'folder-icon' : 'file-icon'} />
                  <span className="ellipsis">{node.name}</span>
                </div>
                <div className="col date muted">Deleted {formatDate(node.deletedAt!)}</div>
                <div className="col actions">
                  <button className="btn ghost" onClick={() => void run(api.restore(node.id), `Restored ${node.name}`)}>
                    <Icon name="restore" size={16} /> Restore
                  </button>
                  <button className="btn ghost danger" onClick={async () => {
                    if (await dialog.confirm(`Delete "${node.name}" forever?`, 'This cannot be undone.')) {
                      void run(api.deleteForever(node.id), 'Deleted forever');
                    }
                  }}>Delete forever</button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
      {dialog.element}
    </section>
  );
}
