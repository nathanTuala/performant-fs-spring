import { api } from '../api/client';
import { uploads } from './queue';
import { isJunk, type DroppedTree } from './walker';

/**
 * Creates the folder structure of a drop in one request, then queues every file into its
 * folder. Plain files dropped at the top level go straight into `parentId`.
 */
export async function uploadTree(parentId: string, tree: DroppedTree): Promise<void> {
  const files = tree.files.filter((f) => !isJunk(f.file));
  let folderIds: Record<string, string> = {};
  if (tree.dirs.length > 0) folderIds = (await api.createFolders(parentId, tree.dirs)).folders;
  uploads.enqueue(
    files.map(({ file, path }) => {
      const dir = path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : '';
      return { file, path, parentId: dir ? (folderIds[dir] ?? parentId) : parentId };
    }),
  );
}
