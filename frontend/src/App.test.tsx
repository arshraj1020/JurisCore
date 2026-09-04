import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { HttpResponse, http } from 'msw';
import { server } from '@/test/server';
import { envelope, makeUser, pageOf, testQueryClient } from '@/test/utils';
import { AuthProvider } from '@/lib/auth/AuthContext';
import { ToastProvider } from '@/components/ui/Toast';
import { AppRoutes } from './App';
import type { Role } from '@/types/api';

/**
 * A boot test for the whole application: providers, guards, the lazily-loaded route
 * chunks and the shell, wired together as they are in production.
 *
 * Unit tests exercise each of those in isolation and would all still pass if the route
 * table imported the wrong module or a provider sat inside the thing that needs it. This
 * is the test that fails when the application does not start.
 */
function boot(role: Role, route = '/') {
  server.use(
    http.post('/api/v1/auth/refresh', () => HttpResponse.json(envelope({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      tokenType: 'Bearer',
      expiresIn: 300,
      user: makeUser(role),
    }))),
    http.get('/api/v1/notifications/unread-count', () =>
      HttpResponse.json(envelope({ unread: 2 }))),
    http.get('/api/v1/organizations/current', () => HttpResponse.json(envelope({
      id: '22222222-2222-2222-2222-222222222222',
      name: 'Rao & Partners',
      slug: 'rao-partners',
      status: 'ACTIVE',
      createdAt: '2026-01-01T00:00:00Z',
    }))),
    http.get('/api/v1/cases', () => HttpResponse.json(envelope(pageOf([])))),
    http.get('/api/v1/hearings', () => HttpResponse.json(envelope(pageOf([])))),
    http.get('/api/v1/invoices', () => HttpResponse.json(envelope(pageOf([])))),
  );
  window.localStorage.setItem('juriscore.refreshToken', 'refresh-0');

  return render(
    <QueryClientProvider client={testQueryClient()}>
      <MemoryRouter initialEntries={[route]}>
        <AuthProvider>
          <ToastProvider>
            <AppRoutes />
          </ToastProvider>
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('the application', () => {
  it('restores a session, loads the dashboard chunk and renders the shell', async () => {
    boot('FIRM_ADMIN');

    expect(await screen.findByRole('heading', { name: /Good day, Asha/ })).toBeInTheDocument();
    // The shell around it, not just the page.
    expect(screen.getByRole('link', { name: 'Matters' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Invoices' })).toBeInTheDocument();
  });

  it('shows a lawyer no route to the audit trail or billing settings', async () => {
    boot('LAWYER');

    await screen.findByRole('heading', { name: /Good day, Asha/ });
    expect(screen.queryByRole('link', { name: 'Audit trail' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Billing settings' })).not.toBeInTheDocument();
    // But billing itself is visible to every firm role.
    expect(screen.getByRole('link', { name: 'Invoices' })).toBeInTheDocument();
  });

  it('sends an unknown path inside the shell to a not-found page, not to sign-in', async () => {
    boot('FIRM_ADMIN', '/no-such-page');

    expect(await screen.findByText(/not found|does not exist/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign in' })).not.toBeInTheDocument();
  });
});
