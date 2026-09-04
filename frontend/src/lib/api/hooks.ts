import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';

/**
 * A value that lags behind its input — used to keep a search box from firing a request
 * on every keystroke.
 */
export function useDebounced<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delay);
    return () => window.clearTimeout(timer);
  }, [value, delay]);
  return debounced;
}

/**
 * List state kept in the URL rather than in a component.
 *
 * The point is that a filtered list survives being navigated away from and back to, and
 * can be shared or bookmarked — "the overdue invoices for this client" is a URL, not a
 * state somebody has to reconstruct. It also means the browser's back button does what a
 * user expects after they page forward.
 */
export function useListParams<T extends Record<string, string>>(defaults: T) {
  const [searchParams, setSearchParams] = useSearchParams();

  const params = { ...defaults } as T;
  for (const key of Object.keys(defaults) as (keyof T & string)[]) {
    const value = searchParams.get(key);
    if (value !== null) params[key] = value as T[keyof T & string];
  }
  const page = Math.max(0, Number(searchParams.get('page') ?? '0') || 0);

  const update = (next: Partial<T>, options?: { resetPage?: boolean }) => {
    const merged = new URLSearchParams(searchParams);
    for (const [key, value] of Object.entries(next)) {
      if (value === undefined || value === '' || value === defaults[key]) merged.delete(key);
      else merged.set(key, String(value));
    }
    // Changing a filter while on page 4 of the old result set is almost never what
    // somebody means, so a filter change goes back to the first page.
    if (options?.resetPage !== false) merged.delete('page');
    setSearchParams(merged, { replace: true });
  };

  const setPage = (nextPage: number) => {
    const merged = new URLSearchParams(searchParams);
    if (nextPage <= 0) merged.delete('page');
    else merged.set('page', String(nextPage));
    setSearchParams(merged, { replace: true });
  };

  return { params, page, update, setPage };
}

/**
 * Warns before leaving a form with unsaved edits.
 *
 * Only the browser-level guard: React Router 6's in-app blocker requires a data router,
 * and this application uses the component router. Closing the tab or reloading is where
 * work is actually lost, and that is what this covers.
 */
export function useUnsavedChangesWarning(enabled: boolean) {
  const enabledRef = useRef(enabled);
  enabledRef.current = enabled;

  useEffect(() => {
    const handler = (event: BeforeUnloadEvent) => {
      if (!enabledRef.current) return;
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, []);
}
