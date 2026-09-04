import type { ApiResponse } from '@/types/api';
import { ApiError, networkError, toApiError } from './errors';
import {
  clearTokens, getAccessToken, getRefreshToken, setAccessToken, setRefreshToken,
} from '@/lib/auth/tokenStorage';

/**
 * The one place that talks to the backend.
 *
 * Everything above this file — hooks, features, components — works in terms of typed
 * models and `ApiError`. Nothing else in the application calls `fetch`, reads a token, or
 * knows what the response envelope looks like.
 */

/**
 * Empty by default, so every request is a *relative* path and goes to whatever origin
 * served the page.
 *
 * That is what a deployment behind a reverse proxy wants, and in development it is the
 * Vite dev server's `/api` proxy (see `vite.config.ts`) that forwards those paths to
 * Spring Boot. Empty here therefore means "someone in front of me routes /api", not "the
 * backend is on this origin" — with neither a proxy nor this variable set, `/api/...`
 * reaches the dev server, which answers 404.
 *
 * Set `VITE_API_BASE_URL` for a frontend deployed on a different origin from the API.
 */
const BASE_URL = (import.meta.env['VITE_API_BASE_URL'] ?? '').replace(/\/$/, '');

type Query = Record<string, string | number | boolean | null | undefined>;

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  query?: Query;
  /** Skip the Authorization header and the refresh dance. Used by the auth endpoints. */
  anonymous?: boolean;
  signal?: AbortSignal;
}

function buildUrl(path: string, query?: Query): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value === null || value === undefined || value === '') continue;
    search.append(key, String(value));
  }
  const qs = search.toString();
  return `${BASE_URL}${path}${qs ? `?${qs}` : ''}`;
}

// -------------------------------------------------------------- session end

type SessionEndedListener = () => void;
const sessionEndedListeners = new Set<SessionEndedListener>();

/**
 * Notified when the backend has definitively rejected the session — a 401 that a refresh
 * could not rescue. The auth store listens and clears itself; the router then sends the
 * user to the sign-in page. Done through a listener rather than a direct import because
 * the HTTP layer must not depend on React state.
 */
export function onSessionEnded(listener: SessionEndedListener): () => void {
  sessionEndedListeners.add(listener);
  return () => sessionEndedListeners.delete(listener);
}

function endSession(): void {
  clearTokens();
  for (const listener of sessionEndedListeners) listener();
}

// ------------------------------------------------------------ token refresh

let refreshInFlight: Promise<string | null> | null = null;

/**
 * Exchanges the refresh token for a new pair, at most once at a time.
 *
 * The single-flight promise is the whole point. A dashboard fires six queries at once; if
 * the access token has expired, all six get a 401 within milliseconds of each other. Six
 * parallel refreshes would rotate the refresh token six times, and the backend revokes
 * the one it replaces — so five of them would fail and the user would be signed out
 * mid-session. Sharing one promise means one rotation and five waiters.
 */
async function refreshAccessToken(): Promise<string | null> {
  if (refreshInFlight) return refreshInFlight;

  const refreshToken = getRefreshToken();
  if (!refreshToken) return null;

  refreshInFlight = (async () => {
    try {
      const response = await fetch(buildUrl('/api/v1/auth/refresh'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      });
      if (!response.ok) return null;
      const payload = (await response.json()) as ApiResponse<{
        accessToken: string; refreshToken: string;
      }>;
      setAccessToken(payload.data.accessToken);
      setRefreshToken(payload.data.refreshToken);
      return payload.data.accessToken;
    } catch {
      return null;
    } finally {
      // Cleared in `finally` so a failed refresh does not wedge every later request
      // behind a permanently rejected promise.
      refreshInFlight = null;
    }
  })();

  return refreshInFlight;
}

// ---------------------------------------------------------------- requests

async function send(path: string, options: RequestOptions, token: string | null): Promise<Response> {
  const headers: Record<string, string> = {};
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers['Authorization'] = `Bearer ${token}`;

  return fetch(buildUrl(path, options.query), {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal,
  });
}

/**
 * Performs a request and unwraps the `{ success, data }` envelope.
 *
 * On a 401 for an authenticated call it refreshes once and replays the request. If the
 * refresh fails, the session is ended rather than the caller being handed a 401 to
 * interpret — "am I signed out?" is not a question every call site should have to answer.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let response: Response;
  try {
    response = await send(path, options, options.anonymous ? null : getAccessToken());
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
    throw networkError();
  }

  if (response.status === 401 && !options.anonymous) {
    const token = await refreshAccessToken();
    if (!token) {
      endSession();
      throw new ApiError({
        status: 401,
        code: 'SESSION_EXPIRED',
        message: 'Your session has ended. Please sign in again.',
      });
    }
    try {
      response = await send(path, options, token);
    } catch {
      throw networkError();
    }
    if (response.status === 401) {
      endSession();
      throw new ApiError({
        status: 401,
        code: 'SESSION_EXPIRED',
        message: 'Your session has ended. Please sign in again.',
      });
    }
  }

  if (!response.ok) throw await toApiError(response);

  if (response.status === 204) return undefined as T;

  const text = await response.text();
  if (!text) return undefined as T;

  const payload = JSON.parse(text) as ApiResponse<T>;
  return payload.data;
}

export const api = {
  get: <T>(path: string, query?: Query, signal?: AbortSignal) =>
    request<T>(path, { method: 'GET', query, signal }),
  post: <T>(path: string, body?: unknown, query?: Query) =>
    request<T>(path, { method: 'POST', body, query }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown, query?: Query) =>
    request<T>(path, { method: 'PATCH', body, query }),
  delete: <T>(path: string, query?: Query) => request<T>(path, { method: 'DELETE', query }),
  anonymousPost: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body, anonymous: true }),
};

// ------------------------------------------------------- presigned storage

/**
 * Puts bytes on a presigned storage URL, with progress.
 *
 * Deliberately not `fetch` and deliberately not `api`: this request goes to object
 * storage, not to JurisCore, so it must carry **no** Authorization header — sending one
 * to S3 alongside a presigned signature is how a request gets rejected as
 * double-authenticated. `XMLHttpRequest` is used because it is the only browser API that
 * reports upload progress, which a lawyer waiting on a 40 MB filing wants to see.
 */
export function uploadToPresignedUrl(
  url: string,
  method: string,
  file: File,
  contentType: string,
  onProgress?: (percent: number) => void,
  signal?: AbortSignal,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open(method, url, true);
    // The backend signs the content type into the link; anything else is refused.
    xhr.setRequestHeader('Content-Type', contentType);

    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && onProgress) {
        onProgress(Math.round((event.loaded / event.total) * 100));
      }
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve();
        return;
      }
      reject(new ApiError({
        status: xhr.status,
        code: xhr.status === 403 ? 'UPLOAD_LINK_EXPIRED' : 'UPLOAD_FAILED',
        message: xhr.status === 403
          ? 'That upload link has expired. Start the upload again.'
          : 'The file could not be uploaded to storage.',
      }));
    };
    xhr.onerror = () => reject(networkError());
    xhr.onabort = () => reject(new DOMException('Aborted', 'AbortError'));
    signal?.addEventListener('abort', () => xhr.abort(), { once: true });
    xhr.send(file);
  });
}
