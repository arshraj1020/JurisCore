import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api, onSessionEnded, refreshSession } from '@/lib/api/client';
import {
  clearTokens, getRefreshToken, setAccessToken, setRefreshToken,
} from '@/lib/auth/tokenStorage';
import type { AuthTokens, LoginRequest, RegisterRequest, User } from '@/types/api';

interface AuthState {
  user: User | null;
  /** True until the initial "do we already have a session?" check has finished. */
  initialising: boolean;
  login: (credentials: LoginRequest) => Promise<User>;
  register: (details: RegisterRequest) => Promise<User>;
  logout: () => Promise<void>;
  setUser: (user: User) => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUserState] = useState<User | null>(null);
  const [initialising, setInitialising] = useState(true);
  const queryClient = useQueryClient();

  /**
   * Forgets everything the previous session fetched.
   *
   * <p>Clearing tokens ends a session's *authority*; it does nothing about the data that
   * session already pulled into memory. The `QueryClient` is created once, above this
   * provider and above the router, so it survives sign-out: nothing unmounts it, and its
   * entries live for `gcTime` (five minutes by default) regardless of who is signed in.
   *
   * <p>That is a confidentiality bug in a product where two people share a machine. A firm
   * administrator signs out after looking at matters, invoices, the audit trail and the
   * member list; a clerk signs in on the same tab a minute later; every one of those
   * queries finds a cached entry that is still inside the 30-second `staleTime`, so
   * TanStack Query serves it from memory without so much as a background refetch. The
   * route guards hid the *pages* from the clerk — they cannot hide a cache entry that has
   * already been handed to a component. The backend was never consulted, so none of its
   * authorization applies.
   *
   * <p>So the cache is emptied at each of the three real session boundaries: signing out,
   * being signed out by the server, and signing in as somebody new.
   *
   * <p>Note where it is <em>not</em> called: {@code adopt} itself, which also runs during
   * boot session-restore. At that point the cache cannot hold a previous session's data —
   * the tab has just loaded — so clearing buys nothing, and it actively harms, because any
   * query that started before the restore resolved is discarded mid-flight and its
   * component is left with no data and no refetch. `ProtectedRoute` waits for
   * `initialising` so the workspace does not do that, but nothing rendered outside that
   * gate is protected, and a component that fetches on mount would simply come up empty.
   */
  const forgetSessionData = useCallback(() => {
    queryClient.clear();
  }, [queryClient]);

  const adopt = useCallback((tokens: AuthTokens) => {
    setAccessToken(tokens.accessToken);
    setRefreshToken(tokens.refreshToken);
    setUserState(tokens.user);
  }, []);

  /**
   * On boot, trade a stored refresh token for a session.
   *
   * The access token lives in memory, so a reload always starts with none. If there is no
   * refresh token, or the backend has revoked it, this settles quickly into "signed out"
   * — which is why `initialising` exists: routing before this resolves would bounce a
   * signed-in user to the login page on every refresh.
   *
   * Two details here are load-bearing, and both were once wrong.
   *
   * `refreshSession()` rather than a direct POST to `/api/v1/auth/refresh`. Refresh
   * tokens rotate and the backend revokes the one it replaces — correctly, since a
   * replayed refresh token is how a stolen session is detected. StrictMode runs this
   * effect twice in development, so a direct call meant two rotations racing the same
   * token: one won, the other got a 409, and the user was signed out on every reload.
   * `refreshSession()` is the application's single-flight refresh; the second run joins
   * the first's promise, so there is one rotation and two readers of one result. The fix
   * belongs here, not in StrictMode and not in the backend's rotation.
   *
   * And the failure path is guarded by `cancelled`. An unmounted, superseded run must
   * never call `clearTokens()`: the tokens it would be clearing are the *successful*
   * run's. A cancelled effect cleans up after itself and touches nothing else.
   */
  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!getRefreshToken()) {
        if (!cancelled) setInitialising(false);
        return;
      }
      let tokens: AuthTokens | null = null;
      try {
        tokens = await refreshSession();
      } catch {
        tokens = null;
      }
      if (cancelled) return;
      if (tokens) adopt(tokens);
      // Only a live run may end the session — and only when the refresh genuinely
      // failed, which `refreshSession` reports as null rather than by throwing.
      else clearTokens();
      setInitialising(false);
    })();
    return () => { cancelled = true; };
  }, [adopt]);

  /** The HTTP layer ended the session (a 401 no refresh could rescue). Follow it. */
  useEffect(() => onSessionEnded(() => {
    setUserState(null);
    forgetSessionData();
  }), [forgetSessionData]);

  const login = useCallback(async (credentials: LoginRequest) => {
    const tokens = await api.anonymousPost<AuthTokens>('/api/v1/auth/login', credentials);
    // Before adopting, not after: whoever signs in next must not inherit a single entry
    // from whoever was here before, and this is the boundary that catches any path which
    // ended the previous session without going through logout or onSessionEnded.
    forgetSessionData();
    adopt(tokens);
    return tokens.user;
  }, [adopt, forgetSessionData]);

  const register = useCallback(async (details: RegisterRequest) => {
    const tokens = await api.anonymousPost<AuthTokens>('/api/v1/auth/register', details);
    forgetSessionData();
    adopt(tokens);
    return tokens.user;
  }, [adopt, forgetSessionData]);

  const logout = useCallback(async () => {
    const refreshToken = getRefreshToken();
    try {
      // Best effort: the backend revokes the refresh token so it cannot be replayed. A
      // failure here must still sign the user out locally — the alternative is a user who
      // pressed "sign out", saw an error, and is still signed in.
      await api.post('/api/v1/auth/logout', refreshToken ? { refreshToken } : undefined);
    } catch {
      /* fall through */
    } finally {
      clearTokens();
      setUserState(null);
      forgetSessionData();
    }
  }, [forgetSessionData]);

  const value = useMemo<AuthState>(
    () => ({ user, initialising, login, register, logout, setUser: setUserState }),
    [user, initialising, login, register, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside an AuthProvider');
  return context;
}
