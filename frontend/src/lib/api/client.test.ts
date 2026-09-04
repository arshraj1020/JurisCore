import { describe, expect, it, vi } from 'vitest';
import { HttpResponse, http } from 'msw';
import { server } from '@/test/server';
import { api, onSessionEnded, uploadToPresignedUrl } from './client';
import { ApiError } from './errors';
import {
  getAccessToken, getRefreshToken, setAccessToken, setRefreshToken,
} from '@/lib/auth/tokenStorage';
import { envelope } from '@/test/utils';

describe('request', () => {
  it('unwraps the success envelope and sends the access token', async () => {
    let seenAuthorization: string | null = null;
    server.use(http.get('/api/v1/clients/abc', ({ request }) => {
      seenAuthorization = request.headers.get('Authorization');
      return HttpResponse.json(envelope({ id: 'abc', displayName: 'Rao & Co' }));
    }));

    setAccessToken('access-1');
    const client = await api.get<{ id: string; displayName: string }>('/api/v1/clients/abc');

    expect(client.displayName).toBe('Rao & Co');
    expect(seenAuthorization).toBe('Bearer access-1');
  });

  it('turns an error envelope into an ApiError carrying code and field errors', async () => {
    server.use(http.post('/api/v1/clients', () => HttpResponse.json({
      success: false,
      error: {
        code: 'VALIDATION_FAILED',
        message: 'That request was not valid.',
        details: [{ field: 'displayName', message: 'must not be blank' }],
        timestamp: '2026-09-01T10:00:00Z',
      },
    }, { status: 400, headers: { 'X-Request-Id': 'req-77' } })));

    setAccessToken('access-1');
    const error = await api.post('/api/v1/clients', {}).catch((thrown: unknown) => thrown);

    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(400);
    expect(apiError.code).toBe('VALIDATION_FAILED');
    expect(apiError.requestId).toBe('req-77');
    expect(apiError.fieldErrors).toEqual([{ field: 'displayName', message: 'must not be blank' }]);
  });

  it('never surfaces an unparseable body as a message', async () => {
    server.use(http.get('/api/v1/cases', () =>
      HttpResponse.text('<html>Gateway timeout at /internal/pool</html>', { status: 504 })));

    setAccessToken('access-1');
    const error = await api.get('/api/v1/cases').catch((thrown: unknown) => thrown) as ApiError;

    expect(error.code).toBe('UNEXPECTED_RESPONSE');
    expect(error.message).not.toContain('internal/pool');
  });

  it('reports a dropped connection as a network error, not an HTTP status', async () => {
    server.use(http.get('/api/v1/cases', () => HttpResponse.error()));

    setAccessToken('access-1');
    const error = await api.get('/api/v1/cases').catch((thrown: unknown) => thrown) as ApiError;

    expect(error.isNetwork).toBe(true);
    expect(error.status).toBe(0);
  });
});

describe('token refresh', () => {
  it('refreshes once and replays the original request', async () => {
    let refreshCalls = 0;
    server.use(
      http.post('/api/v1/auth/refresh', () => {
        refreshCalls += 1;
        return HttpResponse.json(envelope({
          accessToken: 'access-2', refreshToken: 'refresh-2',
        }));
      }),
      http.get('/api/v1/users/me', ({ request }) =>
        request.headers.get('Authorization') === 'Bearer access-2'
          ? HttpResponse.json(envelope({ id: 'u1' }))
          : new HttpResponse(null, { status: 401 })),
    );

    setAccessToken('stale');
    setRefreshToken('refresh-1');

    await expect(api.get<{ id: string }>('/api/v1/users/me')).resolves.toEqual({ id: 'u1' });
    expect(refreshCalls).toBe(1);
    // The rotated pair replaces the old one, so the revoked token is never replayed.
    expect(getAccessToken()).toBe('access-2');
    expect(getRefreshToken()).toBe('refresh-2');
  });

  /**
   * The single-flight promise, which is the whole reason `refreshInFlight` exists.
   *
   * Six parallel 401s must produce one rotation. The backend revokes the refresh token it
   * replaces, so six rotations would mean five revoked tokens and a user signed out in
   * the middle of a working session.
   */
  it('rotates the refresh token once for a burst of parallel 401s', async () => {
    let refreshCalls = 0;
    server.use(
      http.post('/api/v1/auth/refresh', async () => {
        refreshCalls += 1;
        await new Promise((resolve) => setTimeout(resolve, 20));
        return HttpResponse.json(envelope({
          accessToken: 'access-2', refreshToken: 'refresh-2',
        }));
      }),
      http.get('/api/v1/dashboard/:panel', ({ request, params }) =>
        request.headers.get('Authorization') === 'Bearer access-2'
          ? HttpResponse.json(envelope({ panel: params['panel'] }))
          : new HttpResponse(null, { status: 401 })),
    );

    setAccessToken('stale');
    setRefreshToken('refresh-1');

    const panels = ['cases', 'hearings', 'tasks', 'deadlines', 'invoices', 'notifications'];
    const results = await Promise.all(
      panels.map((panel) => api.get<{ panel: string }>(`/api/v1/dashboard/${panel}`)),
    );

    expect(results.map((result) => result.panel)).toEqual(panels);
    expect(refreshCalls).toBe(1);
  });

  it('ends the session when the refresh token is no longer accepted', async () => {
    const ended = vi.fn();
    const unsubscribe = onSessionEnded(ended);
    server.use(
      http.post('/api/v1/auth/refresh', () => new HttpResponse(null, { status: 401 })),
      http.get('/api/v1/users/me', () => new HttpResponse(null, { status: 401 })),
    );

    setAccessToken('stale');
    setRefreshToken('revoked');

    const error = await api.get('/api/v1/users/me').catch((thrown: unknown) => thrown) as ApiError;

    expect(error.code).toBe('SESSION_EXPIRED');
    expect(ended).toHaveBeenCalledTimes(1);
    // Both tokens are dropped: nothing usable is left in storage after a dead session.
    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
    unsubscribe();
  });

  it('does not try to refresh an anonymous call', async () => {
    let refreshCalls = 0;
    server.use(
      http.post('/api/v1/auth/refresh', () => {
        refreshCalls += 1;
        return HttpResponse.json(envelope({ accessToken: 'a', refreshToken: 'r' }));
      }),
      http.post('/api/v1/auth/login', () => HttpResponse.json({
        success: false,
        error: { code: 'INVALID_CREDENTIALS', message: 'Email or password is incorrect.' },
      }, { status: 401 })),
    );

    setRefreshToken('refresh-1');
    const error = await api
      .anonymousPost('/api/v1/auth/login', { email: 'a@b.test', password: 'x' })
      .catch((thrown: unknown) => thrown) as ApiError;

    // A rejected sign-in is a rejected sign-in, not an expired session.
    expect(error.code).toBe('INVALID_CREDENTIALS');
    expect(refreshCalls).toBe(0);
  });
});

describe('presigned upload', () => {
  it('sends the bytes with the signed content type and no Authorization header', async () => {
    const seen: { authorization: string | null; contentType: string | null } = {
      authorization: null, contentType: null,
    };
    server.use(http.put('https://storage.test/bucket/key', ({ request }) => {
      seen.authorization = request.headers.get('Authorization');
      seen.contentType = request.headers.get('Content-Type');
      return new HttpResponse(null, { status: 200 });
    }));

    setAccessToken('access-1');
    const file = new File(['filing'], 'petition.pdf', { type: 'application/pdf' });
    await uploadToPresignedUrl(
      'https://storage.test/bucket/key', 'PUT', file, 'application/pdf',
    );

    // Sending our bearer token to object storage would leak it to a third party and be
    // rejected as double-authentication by S3.
    expect(seen.authorization).toBeNull();
    expect(seen.contentType).toBe('application/pdf');
  });

  it('reports an expired link distinctly so the caller can mint a new one', async () => {
    server.use(http.put('https://storage.test/bucket/key', () =>
      new HttpResponse(null, { status: 403 })));

    const file = new File(['filing'], 'petition.pdf', { type: 'application/pdf' });
    const error = await uploadToPresignedUrl(
      'https://storage.test/bucket/key', 'PUT', file, 'application/pdf',
    ).catch((thrown: unknown) => thrown) as ApiError;

    expect(error.code).toBe('UPLOAD_LINK_EXPIRED');
  });
});
