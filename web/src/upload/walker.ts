export interface DroppedFile {
  file: File;
  /** Path relative to the drop, e.g. "Photos/2024/a.jpg". */
  path: string;
}

export interface DroppedTree {
  files: DroppedFile[];
  /** Every directory in the drop, including empty ones, e.g. ["Photos", "Photos/2024"]. */
  dirs: string[];
}

/**
 * Collects the files and folders from a drop. Entries must be taken synchronously inside
 * the drop handler: the DataTransfer is emptied once the handler returns.
 */
export function entriesFromDrop(dataTransfer: DataTransfer): FileSystemEntry[] | File[] {
  const items = [...dataTransfer.items].filter((i) => i.kind === 'file');
  const entries = items.map((i) => i.webkitGetAsEntry?.()).filter((e): e is FileSystemEntry => !!e);
  // Browsers without the Entries API still give us plain files (no folders).
  return entries.length === items.length ? entries : [...dataTransfer.files];
}

export async function walkEntries(entries: FileSystemEntry[] | File[]): Promise<DroppedTree> {
  const tree: DroppedTree = { files: [], dirs: [] };
  for (const entry of entries) {
    if (entry instanceof File) tree.files.push({ file: entry, path: entry.name });
    else await walk(entry, '', tree);
  }
  return tree;
}

async function walk(entry: FileSystemEntry, prefix: string, tree: DroppedTree): Promise<void> {
  const path = prefix ? `${prefix}/${entry.name}` : entry.name;
  if (isFile(entry)) {
    const file = await new Promise<File>((resolve, reject) => entry.file(resolve, reject));
    tree.files.push({ file, path });
    return;
  }
  if (!isDirectory(entry)) return;
  tree.dirs.push(path);
  for (const child of await readAll(entry)) await walk(child, path, tree);
}

/** readEntries() returns at most ~100 entries per call; keep calling until it returns none. */
async function readAll(dir: FileSystemDirectoryEntry): Promise<FileSystemEntry[]> {
  const reader = dir.createReader();
  const all: FileSystemEntry[] = [];
  for (;;) {
    const batch = await new Promise<FileSystemEntry[]>((resolve, reject) => reader.readEntries(resolve, reject));
    if (batch.length === 0) return all;
    all.push(...batch);
  }
}

const isFile = (e: FileSystemEntry): e is FileSystemFileEntry => e.isFile;
const isDirectory = (e: FileSystemEntry): e is FileSystemDirectoryEntry => e.isDirectory;

/** Files from <input webkitdirectory>, which carry their path in webkitRelativePath. */
export function treeFromInput(files: FileList): DroppedTree {
  const tree: DroppedTree = { files: [], dirs: [] };
  const dirs = new Set<string>();
  for (const file of files) {
    const path = file.webkitRelativePath || file.name;
    tree.files.push({ file, path });
    const parts = path.split('/').slice(0, -1);
    for (let i = 1; i <= parts.length; i++) dirs.add(parts.slice(0, i).join('/'));
  }
  tree.dirs = [...dirs];
  return tree;
}

/** Ignored when uploading folders: OS metadata nobody means to upload. */
const JUNK = new Set(['.DS_Store', 'Thumbs.db', 'desktop.ini']);
export const isJunk = (file: File) => JUNK.has(file.name);
