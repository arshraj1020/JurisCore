import { describe, expect, it } from 'vitest';
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { HttpResponse, http } from 'msw';
import { server } from '@/test/server';
import { envelope, makeUser } from '@/test/utils';
import { AuthProvider, useAuth } from './AuthContext';
import { api } from '@/lib/api/client';

/**
 * What the previous session leaves behind in memory.
 *
 * <p>Ending a session clears its *authority* — the tokens go, the backend revokes the
 * refresh row. It does nothing about the data that session already fetched. The
 * `QueryClient` is created once, above the auth provider and above the router, so it
 * survives sign-out entirely: nothing unmounts it, and its entries live for `gcTime`
 * regardless of who is signed in.
 *
 * <p>On a shared machine that is a confidentiality breach rather than a stale-data glitch.
 * A firm administrator signs out after reading matters, invoices and the audit trail; a
 * clerk signs in on the same tab a minute later; every one of those queries finds an entry
 * still inside `staleTime` and renders it from memory without asking the server. The route
 * guards hide the *pages* from the clerk — they cannot un-hand a cache entry to a component
 * that already has it, and the backend, whose authorization is the real gate, is never
 * consulted at all.
 *
 * <p>These tests pin the three boundaries where that cache must be dropped.
 */

const CACHED_KEY = ['cases', 'list', { page: 0 }];
const CACHED_VALUE = { items: [{ id: 'case-1', title: "The previous user's matter" }] };

/** Every cache entry that still holds data. */
function cachedData(queryClient: QueryClient) {
  return queryClient.getQueryCache().getAll()
    .filter((query) => query.state.data !== undefined)
    .map((query) => query.queryKey);
}

function harness() {
  // A client this test owns, so it can inspect the cache rather than infer it from pixels.
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 30_000 } },
  });

  function Probe() {
    const { user, logout, login } = useAuth();
    // A real subscriber, so the entry behaves like one a page created rather than one
    // planted in an otherwise-empty cache.
    const cached = useQuery({
      queryKey: CACHED_KEY,
      queryFn: () => Promise.resolve(CACHED_VALUE),
      enabled: user !== null,
    });

    return (
      <div>
        <p>{user ? `signed in as ${user.email}` : 'signed out'}</p>
        <p>matter: {cached.data?.items[0]?.title ?? 'none'}</p>
        <button type="button" onClick={() => void logout()}>Sign out</button>
        <button
          type="button"
          onClick={() => void login({ email: 'clerk@example.test', password: 'x' })}
        >
          Sign in
        </button>
      </div>
    );
  }

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AuthProvider><Probe /></AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );

  return queryClient;
}

/** Signs a user in through the boot-restore path, then waits for the cache to fill. */
async function signedInWithCachedData(queryClient: QueryClient) {
  await screen.findByText(/signed in as/);
  await waitFor(() =>
    expect(screen.getByText(/^matter:/)).toHaveTextContent("The previous user's matter"));
  expect(queryClient.getQueryData(CACHED_KEY)).toBeDefined();
}

function restoreSession(email: string) {
  server.use(http.post('/api/v1/auth/refresh', () => HttpResponse.json(envelope({
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    tokenType: 'Bearer',
    expiresIn: 300,
    user: makeUser('FIRM_ADMIN', { email }),
  }))));
  window.localStorage.setItem('juriscore.refreshToken', 'refresh-0');
}

describe('session boundaries drop the previous session\'s cached data', () => {
  it('signing out empties the query cache', async () => {
    restoreSession('admin@sharma-legal.test');
    server.use(http.post('/api/v1/auth/logout', () => HttpResponse.json(envelope(null))));
    const queryClient = harness();
    await signedInWithCachedData(queryClient);

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    await screen.findByText('signed out');
    // The assertion that matters: not "the page stopped showing it", but "the datum is
    // gone". A still-mounted observer re-registers its (empty) query key immediately, so
    // counting cache *entries* would measure the wrong thing — what must not survive is
    // the data behind them.
    expect(queryClient.getQueryData(CACHED_KEY)).toBeUndefined();
    expect(cachedData(queryClient)).toEqual([]);
    expect(screen.getByText(/^matter:/)).toHaveTextContent('matter: none');
  });

  it('empties the cache even when the backend refuses the logout call', async () => {
    restoreSession('admin@sharma-legal.test');
    server.use(http.post('/api/v1/auth/logout', () =>
      HttpResponse.json({ success: false, error: { code: 'INTERNAL_ERROR', message: 'nope', timestamp: '2026-09-10T00:00:00Z' } }, { status: 500 })));
    const queryClient = harness();
    await signedInWithCachedData(queryClient);

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    // Sign-out is best-effort against the server but unconditional locally, and the cache
    // has to follow the local half — otherwise a failed revoke leaves the data on screen.
    await screen.findByText('signed out');
    expect(cachedData(queryClient)).toEqual([]);
  });

  it('empties the cache when the server ends the session underneath us', async () => {
    restoreSession('admin@sharma-legal.test');
    const queryClient = harness();
    await signedInWithCachedData(queryClient);

    // A 401 that no refresh can rescue: the refresh token is gone too, so the HTTP layer
    // gives up and ends the session. This is the involuntary path — an expired or revoked
    // session — and it leaves exactly the same data behind if nothing clears it.
    server.use(
      http.get('/api/v1/anything', () => new HttpResponse(null, { status: 401 })),
      http.post('/api/v1/auth/refresh', () => new HttpResponse(null, { status: 401 })),
    );
    window.localStorage.removeItem('juriscore.refreshToken');
    await expect(api.get('/api/v1/anything')).rejects.toThrow();

    await screen.findByText('signed out');
    await waitFor(() => expect(cachedData(queryClient)).toEqual([]));
  });

  it('signing in does not inherit whatever was in the cache', async () => {
    restoreSession('admin@sharma-legal.test');
    const queryClient = harness();
    await signedInWithCachedData(queryClient);

    // Belt and braces: by this point sign-out should already have emptied it. Adopting a
    // session is the last boundary, and the one that protects against any path that ends a
    // session without going through either handler above.
    server.use(http.post('/api/v1/auth/login', () => HttpResponse.json(envelope({
      accessToken: 'access-2',
      refreshToken: 'refresh-2',
      tokenType: 'Bearer',
      expiresIn: 300,
      user: makeUser('CLERK', { email: 'clerk@example.test' }),
    }))));

    const beforeSignIn = Date.now();
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await screen.findByText('signed in as clerk@example.test');
    // The clerk's own query refetches under the clerk's session, which is fine and
    // expected. What must never happen is the administrator's datum being *served* to the
    // clerk — so the check is that anything still holding data was written after the
    // boundary, not that the cache stays empty behind it.
    for (const query of queryClient.getQueryCache().getAll()) {
      if (query.state.data !== undefined) {
        expect(query.state.dataUpdatedAt).toBeGreaterThanOrEqual(beforeSignIn);
      }
    }
  });
});
