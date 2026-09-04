/**
 * Where the two tokens live, and why they live in different places.
 *
 * The **access token is held in memory only**. It is short-lived (five minutes by the
 * backend's default) and never written to `localStorage`, so a cross-site scripting bug
 * cannot read a usable credential out of storage after the tab is gone.
 *
 * The **refresh token is persisted**, because the backend offers no cookie-based session
 * and a page reload would otherwise sign the user out. This is a real trade-off, not an
 * oversight: a persisted refresh token is readable by any script running on the origin.
 * The properly hardened answer is an httpOnly, SameSite cookie issued by the backend —
 * that is a backend change, it was not made here, and it is recorded as a limitation
 * rather than worked around with something that merely looks safer.
 *
 * What this module does guarantee: neither token is ever logged, neither is ever put in a
 * URL, and the access token never touches persistent storage.
 */

const REFRESH_TOKEN_KEY = 'juriscore.refreshToken';

let accessToken: string | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function getRefreshToken(): string | null {
  try {
    return window.localStorage.getItem(REFRESH_TOKEN_KEY);
  } catch {
    // Private browsing, or storage disabled. The session simply will not survive a
    // reload; it must not crash the application.
    return null;
  }
}

export function setRefreshToken(token: string | null): void {
  try {
    if (token === null) window.localStorage.removeItem(REFRESH_TOKEN_KEY);
    else window.localStorage.setItem(REFRESH_TOKEN_KEY, token);
  } catch {
    /* see above */
  }
}

export function clearTokens(): void {
  accessToken = null;
  setRefreshToken(null);
}
