import type { NodeDto } from '../api/types';

const PATHS = {
  folder: 'M3 6.5A2.5 2.5 0 0 1 5.5 4h3.6l2 2h7.4A2.5 2.5 0 0 1 21 8.5v9a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5z',
  file: 'M6 3h8l5 5v13H6zM14 3v5h5',
  image: 'M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15.5 8.5h.01',
  video: 'M4 6h12v12H4zM16 10l5-3v10l-5-3',
  audio: 'M9 18V6l11-2v12M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0zM20 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0z',
  pdf: 'M6 3h8l5 5v13H6zM14 3v5h5M9 13h6M9 17h4',
  code: 'M8 8l-4 4 4 4M16 8l4 4-4 4M13 6l-2 12',
  archive: 'M4 4h16v4H4zM5 8h14v12H5zM10 12h4',
  upload: 'M12 16V4M7 9l5-5 5 5M4 20h16',
  plus: 'M12 5v14M5 12h14',
  trash: 'M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13',
  home: 'M3 11l9-8 9 8M5 10v10h14V10',
  chevron: 'M9 6l6 6-6 6',
  close: 'M6 6l12 12M18 6L6 18',
  pause: 'M8 5v14M16 5v14',
  play: 'M7 4l13 8-13 8z',
  retry: 'M4 12a8 8 0 1 0 2.3-5.7M4 4v5h5',
  check: 'M5 12l5 5L20 7',
  download: 'M12 4v12M7 11l5 5 5-5M4 20h16',
  restore: 'M4 12a8 8 0 1 0 2.3-5.7M4 4v5h5',
  logout: 'M15 4h4v16h-4M10 8l-4 4 4 4M6 12h11',
  folderPlus: 'M3 6.5A2.5 2.5 0 0 1 5.5 4h3.6l2 2h7.4A2.5 2.5 0 0 1 21 8.5v9a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5zM12 10v6M9 13h6',
  edit: 'M4 20h4L19 9l-4-4L4 16zM14 6l4 4',
  search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4',
} as const;

export type IconName = keyof typeof PATHS;

export function Icon({ name, size = 18, className }: { name: IconName; size?: number; className?: string }) {
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={PATHS[name]} />
    </svg>
  );
}

export function iconFor(node: Pick<NodeDto, 'kind' | 'mime'>): IconName {
  if (node.kind === 'folder') return 'folder';
  const mime = node.mime ?? '';
  if (mime.startsWith('image/')) return 'image';
  if (mime.startsWith('video/')) return 'video';
  if (mime.startsWith('audio/')) return 'audio';
  if (mime === 'application/pdf') return 'pdf';
  if (/zip|tar|gzip|7z/.test(mime)) return 'archive';
  if (mime.startsWith('text/') || mime.includes('json') || mime.includes('xml')) return 'code';
  return 'file';
}
