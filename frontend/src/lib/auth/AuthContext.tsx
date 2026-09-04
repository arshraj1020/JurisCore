import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { api, onSessionEnded } from '@/lib/api/client';
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
   */
  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!getRefreshToken()) {
        if (!cancelled) setInitialising(false);
        return;
      }
      try {
        const tokens = await api.anonymousPost<AuthTokens>('/api/v1/auth/refresh', {
          refreshToken: getRefreshToken(),
        });
        if (!cancelled) adopt(tokens);
      } catch {
        clearTokens();
      } finally {
        if (!cancelled) setInitialising(false);
      }
    })();
    return () => { cancelled = true; };
  }, [adopt]);

  /** The HTTP layer ended the session (a 401 no refresh could rescue). Follow it. */
  useEffect(() => onSessionEnded(() => setUserState(null)), []);

  const login = useCallback(async (credentials: LoginRequest) => {
    const tokens = await api.anonymousPost<AuthTokens>('/api/v1/auth/login', credentials);
    adopt(tokens);
    return tokens.user;
  }, [adopt]);

  const register = useCallback(async (details: RegisterRequest) => {
    const tokens = await api.anonymousPost<AuthTokens>('/api/v1/auth/register', details);
    adopt(tokens);
    return tokens.user;
  }, [adopt]);

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
    }
  }, []);

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
