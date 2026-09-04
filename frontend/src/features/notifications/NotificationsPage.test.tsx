import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { server } from '@/test/server';
import { envelope, makeUser, pageOf, renderWithAuth } from '@/test/utils';
import { NotificationsPage } from './NotificationsPage';
import type { AppNotification } from '@/types/api';

function notification(overrides: Partial<AppNotification> = {}): AppNotification {
  return {
    id: 'n1',
    type: 'INVOICE_ISSUED',
    category: 'INVOICE',
    severity: 'INFO',
    title: 'Invoice INV-2026-0007 issued',
    message: 'The invoice has gone out to Rao & Company.',
    read: false,
    createdAt: '2026-08-30T09:00:00Z',
    ...overrides,
  };
}

function mount(items: AppNotification[]) {
  server.use(
    http.post('/api/v1/auth/refresh', () => HttpResponse.json(envelope({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      tokenType: 'Bearer',
      expiresIn: 300,
      user: makeUser('FIRM_ADMIN'),
    }))),
    http.get('/api/v1/notifications', () => HttpResponse.json(envelope(pageOf(items)))),
    http.get('/api/v1/notification-preferences', () => HttpResponse.json(envelope({
      invoice: true, payment: true, caseUpdates: true, system: false, version: 1,
    }))),
  );
  window.localStorage.setItem('juriscore.refreshToken', 'refresh-0');
  return renderWithAuth(<NotificationsPage />);
}

describe('NotificationsPage', () => {
  it('offers to open a notification that carries a relative in-app path', async () => {
    mount([notification({ actionPath: '/invoices/33333333-3333-3333-3333-333333333333' })]);
    expect(await screen.findByRole('button', { name: 'Open' })).toBeInTheDocument();
  });

  /**
   * The backend constrains `actionPath` to a relative path, and the frontend checks it
   * again. A protocol-relative `//host` or a `javascript:` value handed to the router is
   * how a notification becomes an open redirect, and re-checking costs one comparison.
   */
  it.each([
    'https://evil.example/steal',
    '//evil.example/steal',
    'javascript:alert(1)',
    'invoices/33333333',
  ])('refuses to offer %s as a destination', async (actionPath) => {
    mount([notification({ actionPath })]);

    await screen.findByText('Invoice INV-2026-0007 issued');
    expect(screen.queryByRole('button', { name: 'Open' })).not.toBeInTheDocument();
  });

  it('marks an unread notification visibly and offers to read it', async () => {
    mount([notification({ read: false })]);

    expect(await screen.findByText('New')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Mark read' })).toBeInTheDocument();
  });

  it('shows the saved preference state rather than defaulting everything on', async () => {
    mount([]);

    const system = await screen.findByRole('checkbox', { name: 'System' });
    expect(system).not.toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Invoices' })).toBeChecked();
  });
});
