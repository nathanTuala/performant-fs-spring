import { useSyncExternalStore } from 'react';

/** Every view has its own URL, so back/forward and refresh work. */
export type Route =
  | { view: 'folder'; id: string | null }
  | { view: 'trash' }
  | { view: 'search'; q: string };

function parse(pathname: string, search: string): Route {
  if (pathname === '/trash') return { view: 'trash' };
  if (pathname === '/search') return { view: 'search', q: new URLSearchParams(search).get('q') ?? '' };
  const match = /^\/folder\/([0-9a-f-]{36})$/i.exec(pathname);
  return { view: 'folder', id: match?.[1] ?? null };
}

function toUrl(route: Route): string {
  switch (route.view) {
    case 'trash': return '/trash';
    case 'search': return `/search?${new URLSearchParams({ q: route.q })}`;
    case 'folder': return route.id ? `/folder/${route.id}` : '/';
  }
}

const subscribe = (cb: () => void) => {
  window.addEventListener('popstate', cb);
  return () => window.removeEventListener('popstate', cb);
};

export function useRoute(): Route {
  // A string snapshot, so React only re-renders when the URL actually changes.
  const url = useSyncExternalStore(subscribe, () => window.location.pathname + window.location.search);
  const [pathname = '/', search = ''] = url.split(/(?=\?)/);
  return parse(pathname, search);
}

const currentUrl = () => window.location.pathname + window.location.search;

/** Where a search was started from, kept in the search entry's history state. */
interface SearchState {
  searchOrigin?: string;
}

/** `replace` swaps the current history entry, e.g. while the user refines a search. */
export function navigate(route: Route, opts: { replace?: boolean } = {}): void {
  const url = toUrl(route);
  if (url === currentUrl()) return;
  const prev = (window.history.state ?? {}) as SearchState;
  // Entering search remembers where from; refining it keeps that origin.
  const state: SearchState | null =
    route.view !== 'search' ? null : prev.searchOrigin ? prev : { searchOrigin: currentUrl() };
  if (opts.replace) window.history.replaceState(state, '', url);
  else window.history.pushState(state, '', url);
  window.dispatchEvent(new PopStateEvent('popstate'));
}

/**
 * Leaves the search results for wherever the search started. When we pushed the search
 * entry ourselves, going back keeps history clean; a search opened directly from a URL
 * falls back to My Files.
 */
export function exitSearch(): void {
  const { searchOrigin } = (window.history.state ?? {}) as SearchState;
  if (searchOrigin) {
    window.history.back();
    return;
  }
  window.history.replaceState(null, '', '/');
  window.dispatchEvent(new PopStateEvent('popstate'));
}
