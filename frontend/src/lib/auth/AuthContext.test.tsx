import { describe, expect, it } from 'vitest';
import { StrictMode } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { HttpResponse, http } from 'msw';
import { server } from '@/test/server';
import { envelope, makeUser, testQueryClient } from '@/test/utils';
import { AuthProvider, useAuth } from './AuthContext';
import { getRefreshToken } from './tokenStorage';

/**
 * Session restoration across React's double-invoked effects.
 *
 * The bug this file locks down: `AuthProvider`'s boot effect used to POST
 * `/api/v1/auth/refresh` directly. StrictMode runs that effect twice, so two requests
 * raced with the *same* rotating refresh token. The backend — correctly, because a
 * replayed refresh token is how a stolen session announces itself — accepted one and
 * answered the other with 409. The losing branch then called `clearTokens()` without
 * checking whether it had been cancelled, wiping the session the winner had just
 * established. Every page reload signed the user out.
 *
 * None of the backend's behaviour is mocked away below. The second refresh still gets a
 * 409; the fixture is deliberately a single-use token, exactly like the real thing. What
 * changed is that the frontend now shares one in-flight refresh, so the second request is
 * never sent — and if it somehow were, a cancelled effect can no longer clear anything.
 */

const REFRESH_PATH = '/api/v1/auth/refresh';

interface RefreshLog {
  /** Every refresh request that actually reached the network. */
  requests: string[];
}

/**
 * A refresh endpoint that rotates once and then rejects the old token with 409 — the
 * backend's real reuse-detection behaviour, not a softened stand-in.
 */
function singleUseRefresh(log: RefreshLog) {
  const consumed = new Set<string>();
  return http.post(REFRESH_PATH, async ({ request }) => {
    const body = await request.json() as { refreshToken: string };
    log.requests.push(body.refreshToken);

    if (consumed.has(body.refreshToken)) {
      // Reuse detected. In production this also revokes the whole chain.
      return HttpResponse.json(
        { success: false, error: { code: 'REFRESH_TOKEN_REUSED', message: 'Token already used', timestamp: '2026-09-08T00:00:00Z' } },
        { status: 409 },
      );
    }
    consumed.add(body.refreshToken);

    return HttpResponse.json(envelope({
      accessToken: 'access-rotated',
      refreshToken: 'refresh-rotated',
      tokenType: 'Bearer',
      expiresIn: 300,
      user: makeUser('FIRM_ADMIN'),
    }));
  });
}

/** Prints what the session looks like, plus where the router would have sent us. */
function SessionProbe() {
  const { user, initialising } = useAuth();
  if (initialising) return <p>initialising</p>;
  return user
    ? <p>signed in as {user.email}</p>
    : <p>signed out</p>;
}

function LoginRoute() {
  return <p>redirected to login</p>;
}

/** Mounts the provider inside StrictMode, so every effect runs twice as it does in dev. */
function mountUnderStrictMode() {
  return render(
    <StrictMode>
      <QueryClientProvider client={testQueryClient()}>
        <MemoryRouter initialEntries={['/']}>
          <AuthProvider>
            <Routes>
              <Route path="/" element={<SessionProbe />} />
              <Route path="/login" element={<LoginRoute />} />
            </Routes>
          </AuthProvider>
        </MemoryRouter>
      </QueryClientProvider>
    </StrictMode>,
  );
}

describe('AuthProvider — session restoration under StrictMode', () => {
  it('restores the session on reload instead of signing the user out', async () => {
    const log: RefreshLog = { requests: [] };
    server.use(singleUseRefresh(log));
    window.localStorage.setItem('juriscore.refreshToken', 'refresh-stored');

    mountUnderStrictMode();

    // D: the successful session remains.
    expect(await screen.findByText('signed in as asha@example.test')).toBeInTheDocument();
    expect(screen.queryByText('signed out')).not.toBeInTheDocument();

    // D: the user is not redirected to /login.
    expect(screen.queryByText('redirected to login')).not.toBeInTheDocument();

    // D: a refresh token remains available, and it is the rotated one — the session can
    // survive the *next* reload too, which is what "not signed out" has to mean.
    expect(getRefreshToken()).toBe('refresh-rotated');
  });

  it('sends exactly one refresh even though the boot effect runs twice', async () => {
    const log: RefreshLog = { requests: [] };
    server.use(singleUseRefresh(log));
    window.localStorage.setItem('juriscore.refreshToken', 'refresh-stored');

    mountUnderStrictMode();
    await screen.findByText('signed in as asha@example.test');

    // The single-flight is the fix. Two boot runs, one rotation: the second run joins the
    // first's promise rather than racing it. Anything above 1 here means a rotating token
    // is being spent twice per reload, which is the bug however it ends up rendering.
    expect(log.requests).toEqual(['refresh-stored']);
  });

  it('survives a competing refresh that loses with a 409', async () => {
    const log: RefreshLog = { requests: [] };
    server.use(singleUseRefresh(log));
    window.localStorage.setItem('juriscore.refreshToken', 'refresh-stored');

    mountUnderStrictMode();
    await screen.findByText('signed in as asha@example.test');

    // Simulate the race the old code created: something replays the now-consumed token
    // directly and is rejected, exactly as the backend rejects it.
    const rejected = await fetch(REFRESH_PATH, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: 'refresh-stored' }),
    });
    expect(rejected.status).toBe(409);

    // The losing branch must not take the winner's session with it.
    await waitFor(() => {
      expect(screen.getByText('signed in as asha@example.test')).toBeInTheDocument();
    });
    expect(getRefreshToken()).toBe('refresh-rotated');
    expect(screen.queryByText('redirected to login')).not.toBeInTheDocument();
  });

  it('settles into signed out when there is no stored refresh token', async () => {
    server.use(singleUseRefresh({ requests: [] }));

    mountUnderStrictMode();

    // No token, no request, and `initialising` still resolves — otherwise the app would
    // hang on a spinner for every first-time visitor.
    expect(await screen.findByText('signed out')).toBeInTheDocument();
  });

  it('clears a refresh token the backend has revoked', async () => {
    server.use(http.post(REFRESH_PATH, () => HttpResponse.json(
      { success: false, error: { code: 'INVALID_REFRESH_TOKEN', message: 'Revoked', timestamp: '2026-09-08T00:00:00Z' } },
      { status: 401 },
    )));
    window.localStorage.setItem('juriscore.refreshToken', 'refresh-revoked');

    mountUnderStrictMode();

    // The other half of the guard: a *genuine* failure must still sign the user out and
    // drop the dead token, or the app retries a revoked credential on every reload.
    expect(await screen.findByText('signed out')).toBeInTheDocument();
    await waitFor(() => expect(getRefreshToken()).toBeNull());
  });
});
