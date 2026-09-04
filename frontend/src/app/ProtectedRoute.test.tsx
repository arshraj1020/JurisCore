import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { server } from '@/test/server';
import { envelope, makeUser, renderWithAuth } from '@/test/utils';
import { ProtectedRoute, RequireFirmContext, RequirePermission } from './ProtectedRoute';
import type { Role } from '@/types/api';

const signedInAs = (role: Role) => {
  server.use(http.post('/api/v1/auth/refresh', () => HttpResponse.json(envelope({
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    tokenType: 'Bearer',
    expiresIn: 300,
    user: makeUser(role),
  }))));
  window.localStorage.setItem('juriscore.refreshToken', 'refresh-0');
};

function tree() {
  return (
    <Routes>
      <Route path="/login" element={<h1>Sign in</h1>} />
      <Route element={<ProtectedRoute />}>
        <Route
          path="/"
          element={<RequireFirmContext><h1>Workspace</h1></RequireFirmContext>}
        />
        <Route element={<RequirePermission permission="viewAudit" />}>
          <Route path="/audit" element={<h1>Audit trail</h1>} />
        </Route>
      </Route>
    </Routes>
  );
}

describe('ProtectedRoute', () => {
  it('sends a signed-out visitor to the sign-in page', async () => {
    renderWithAuth(tree(), { route: '/' });
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });

  /**
   * The access token lives in memory, so a reload starts with no session even for a user
   * who has one. Redirecting during that window would sign people out on every refresh —
   * the provider must be given time to trade the stored refresh token first.
   */
  it('waits for the session check instead of bouncing on reload', async () => {
    signedInAs('FIRM_ADMIN');
    renderWithAuth(tree(), { route: '/' });

    expect(screen.getByRole('status')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Workspace' })).toBeInTheDocument();
  });
});

describe('RequirePermission', () => {
  it('lets a firm administrator into the audit trail', async () => {
    signedInAs('FIRM_ADMIN');
    renderWithAuth(tree(), { route: '/audit' });
    expect(await screen.findByRole('heading', { name: 'Audit trail' })).toBeInTheDocument();
  });

  it('explains rather than renders the page for a role without the permission', async () => {
    signedInAs('LAWYER');
    renderWithAuth(tree(), { route: '/audit' });

    expect(await screen.findByText('You do not have access to this')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Audit trail' })).not.toBeInTheDocument();
  });
});

describe('RequireFirmContext', () => {
  it.each(['SUPER_ADMIN', 'CLIENT'] as Role[])(
    'keeps %s out of the firm workspace instead of showing a broken one',
    async (role) => {
      signedInAs(role);
      renderWithAuth(tree(), { route: '/' });

      // Not an error page: a plain explanation, because these roles have no tenant and
      // every tenant-scoped call would 4xx.
      expect(await screen.findByText(/no firm workspace|No workspace for this account/i))
        .toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: 'Workspace' })).not.toBeInTheDocument();
    },
  );
});
