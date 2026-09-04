import { describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { server } from '@/test/server';
import { envelope, makeUser, pageOf, renderWithAuth } from '@/test/utils';
import { InvoiceDetailPage } from './InvoiceDetailPage';
import type { Invoice, InvoiceStatus, Role } from '@/types/api';

const INVOICE_ID = '33333333-3333-3333-3333-333333333333';

function invoice(overrides: Partial<Invoice> = {}): Invoice {
  return {
    id: INVOICE_ID,
    invoiceNumber: 'INV-2026-0007',
    clientId: '44444444-4444-4444-4444-444444444444',
    caseId: null,
    status: 'DRAFT',
    currency: 'INR',
    subtotal: '10000.00',
    taxAmount: '1800.00',
    discountAmount: '0.00',
    totalAmount: '11800.00',
    amountPaid: '0.00',
    amountDue: '11800.00',
    lineItems: [{
      id: 'line-1',
      description: 'Drafting the petition',
      quantity: '10.00',
      unitPrice: '1000.00',
      amount: '10000.00',
      taxRate: '18.00',
      taxAmount: '1800.00',
      sortOrder: 0,
    }],
    createdAt: '2026-08-01T09:00:00Z',
    updatedAt: '2026-08-01T09:00:00Z',
    version: 3,
    ...overrides,
  };
}

function mount(role: Role, data: Invoice) {
  server.use(
    http.post('/api/v1/auth/refresh', () => HttpResponse.json(envelope({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      tokenType: 'Bearer',
      expiresIn: 300,
      user: makeUser(role),
    }))),
    http.get(`/api/v1/invoices/${INVOICE_ID}`, () => HttpResponse.json(envelope(data))),
    http.get(`/api/v1/invoices/${INVOICE_ID}/payments`, () =>
      HttpResponse.json(envelope(pageOf([])))),
    http.get(`/api/v1/clients/${data.clientId}`, () => HttpResponse.json(envelope({
      id: data.clientId,
      displayName: 'Rao & Company',
      clientType: 'CORPORATE',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    }))),
  );
  window.localStorage.setItem('juriscore.refreshToken', 'refresh-0');

  return renderWithAuth(<InvoiceDetailPage />, {
    route: `/invoices/${INVOICE_ID}`,
    path: '/invoices/:invoiceId',
  });
}

describe('InvoiceDetailPage — figures', () => {
  it('shows the server\'s own totals rather than recomputing them', async () => {
    // Deliberately inconsistent arithmetic: subtotal + tax is 11800, but the server says
    // 9999.99. The page must print what the server sent — it is not the calculator.
    mount('FIRM_ADMIN', invoice({ totalAmount: '9999.99', amountDue: '9999.99' }));

    // Total and outstanding both carry it, hence findAll.
    expect(await screen.findAllByText('₹9,999.99')).not.toHaveLength(0);
    expect(screen.queryByText('₹11,800.00')).not.toBeInTheDocument();
  });
});

describe('InvoiceDetailPage — lifecycle gating', () => {
  it('offers issue and cancel on a draft, but never a payment', async () => {
    mount('FIRM_ADMIN', invoice({ status: 'DRAFT' }));

    expect(await screen.findByRole('button', { name: 'Issue' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit draft' })).toBeInTheDocument();
    // Nobody has been asked to pay a draft yet.
    expect(screen.queryByRole('button', { name: 'Record payment' })).not.toBeInTheDocument();
  });

  it('offers a payment on an issued invoice but no further issuing or editing', async () => {
    mount('FIRM_ADMIN', invoice({ status: 'ISSUED', issueDate: '2026-08-01', dueDate: '2026-08-31' }));

    expect(await screen.findByRole('button', { name: 'Record payment' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Issue' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit draft' })).not.toBeInTheDocument();
  });

  it.each(['PAID', 'CANCELLED'] as InvoiceStatus[])(
    'offers no financial action on a %s invoice',
    async (status) => {
      mount('FIRM_ADMIN', invoice({ status, amountDue: '0.00', amountPaid: '11800.00' }));

      await screen.findByRole('heading', { name: 'INV-2026-0007' });
      for (const label of ['Issue', 'Cancel', 'Record payment', 'Edit draft']) {
        expect(screen.queryByRole('button', { name: label }), label).not.toBeInTheDocument();
      }
    },
  );
});

describe('InvoiceDetailPage — role gating', () => {
  it('lets a clerk edit a draft but not issue, cancel or take money', async () => {
    mount('CLERK', invoice({ status: 'DRAFT' }));

    expect(await screen.findByRole('button', { name: 'Edit draft' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Issue' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();
  });

  it('gives a lawyer a read-only view of billing', async () => {
    mount('LAWYER', invoice({ status: 'ISSUED', issueDate: '2026-08-01', dueDate: '2026-08-31' }));

    await screen.findByRole('heading', { name: 'INV-2026-0007' });
    for (const label of ['Issue', 'Cancel', 'Record payment', 'Edit draft']) {
      expect(screen.queryByRole('button', { name: label }), label).not.toBeInTheDocument();
    }
  });
});

describe('InvoiceDetailPage — recording a payment', () => {
  it('refuses an amount larger than what is outstanding', async () => {
    mount('FIRM_ADMIN', invoice({
      status: 'PARTIALLY_PAID',
      issueDate: '2026-08-01',
      dueDate: '2026-08-31',
      amountPaid: '10000.00',
      amountDue: '1800.00',
    }));

    await userEvent.click(await screen.findByRole('button', { name: 'Record payment' }));
    const dialog = await screen.findByRole('dialog');

    const amount = within(dialog).getByLabelText(/Amount/);
    await userEvent.clear(amount);
    await userEvent.type(amount, '5000');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Record' }));

    // No handler for POST /payments exists, so a request would fail the suite: the
    // assertion is that the form never got that far.
    expect(await within(dialog).findByRole('alert'))
      .toHaveTextContent('more than the ₹1,800.00 outstanding');
  });

  it('sends a valid payment with the invoice\'s own currency', async () => {
    let body: Record<string, unknown> | null = null;
    mount('FIRM_ADMIN', invoice({
      status: 'ISSUED', issueDate: '2026-08-01', dueDate: '2026-08-31',
    }));
    server.use(http.post(`/api/v1/invoices/${INVOICE_ID}/payments`, async ({ request }) => {
      body = await request.json() as Record<string, unknown>;
      return HttpResponse.json(envelope({
        id: 'pay-1', invoiceId: INVOICE_ID, amount: '11800.00', currency: 'INR',
        paymentDate: '2026-09-01', method: 'BANK_TRANSFER', createdAt: '2026-09-01T00:00:00Z',
      }));
    }));

    await userEvent.click(await screen.findByRole('button', { name: 'Record payment' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Record' }));

    await waitFor(() => expect(body).not.toBeNull());
    // The amount defaults to the outstanding balance the server reported, unparsed.
    expect(body).toMatchObject({ amount: '11800.00', currency: 'INR', method: 'BANK_TRANSFER' });
  });
});
