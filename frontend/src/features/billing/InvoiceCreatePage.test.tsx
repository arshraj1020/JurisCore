import { describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { server } from '@/test/server';
import { envelope, makeUser, pageOf, renderWithAuth } from '@/test/utils';
import { InvoiceCreatePage } from './InvoiceCreatePage';

/**
 * "Save draft appears to do nothing."
 *
 * That was the audit's finding, and it was accurate: when the resolver rejected a field
 * scrolled out of view, react-hook-form declined to submit and the page gave no sign why.
 * The tests below pin the two halves of the fix — a summary next to the button that was
 * pressed, and validation bounds that match what the server will actually accept — plus
 * the invariant none of it is allowed to break: the totals shown are still the server's.
 */

const CLIENT_ID = '44444444-4444-4444-4444-444444444444';

function mount() {
  server.use(
    http.post('/api/v1/auth/refresh', () => HttpResponse.json(envelope({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      tokenType: 'Bearer',
      expiresIn: 300,
      user: makeUser('FIRM_ADMIN'),
    }))),
    http.get('/api/v1/clients', () => HttpResponse.json(envelope(pageOf([{
      id: CLIENT_ID,
      displayName: 'Rao & Company',
      clientType: 'CORPORATE',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      version: 0,
    }])))),
    http.get('/api/v1/cases', () => HttpResponse.json(envelope(pageOf([])))),
  );
  window.localStorage.setItem('juriscore.refreshToken', 'refresh-0');
  return renderWithAuth(<InvoiceCreatePage />, { route: '/invoices/new' });
}

/** Fills the minimum a valid draft needs, so each test can break exactly one thing. */
async function fillValidDraft() {
  // The client list arrives after the first render, so wait for the option itself rather
  // than for the (immediately present) label.
  await screen.findByRole('option', { name: 'Rao & Company' });
  await userEvent.selectOptions(screen.getByLabelText(/^Client/), CLIENT_ID);
  await userEvent.type(screen.getByLabelText(/^Description/), 'Drafting the petition');
  await userEvent.type(screen.getByLabelText(/^Unit price/), '4000.00');
}

const save = () => screen.getByRole('button', { name: 'Save draft' });

/**
 * Everything currently announced as an alert: the form-level summary plus any field-level
 * messages. Asserted together on purpose — the finding was that a problem could be
 * *nowhere* on screen, so "somewhere a user will see it" is the property under test.
 */
async function alertText(): Promise<string> {
  const alerts = await screen.findAllByRole('alert');
  return alerts.map((alert) => alert.textContent ?? '').join(' | ');
}

describe('InvoiceCreatePage — a blocked save says so', () => {
  it('shows why the draft was not saved instead of doing nothing', async () => {
    let posted = 0;
    server.use(http.post('/api/v1/invoices', () => {
      posted += 1;
      return HttpResponse.json(envelope({}), { status: 500 });
    }));
    mount();

    await screen.findByLabelText(/^Client/);
    await userEvent.click(save());

    // The summary is an alert so it is announced, and it names the actual problems.
    const shown = await alertText();
    expect(shown).toContain('Choose a client');
    expect(shown).toContain('Describe the work');
    // Nothing was sent: the point is that the user learns why, not that a request failed.
    expect(posted).toBe(0);
  });

  it('numbers the line a problem belongs to', async () => {
    mount();
    await fillValidDraft();
    await userEvent.clear(screen.getByLabelText(/^Unit price/));
    await userEvent.click(save());

    expect(await alertText()).toContain('Line 1:');
  });

  it('clears the summary once the draft is accepted', async () => {
    server.use(http.post('/api/v1/invoices', () => HttpResponse.json(envelope({
      id: 'inv-1', invoiceNumber: 'INV-2026-0001', clientId: CLIENT_ID, status: 'DRAFT',
      currency: 'INR', subtotal: '4000.00', taxAmount: '0.00', discountAmount: '0.00',
      totalAmount: '4000.00', amountPaid: '0.00', amountDue: '4000.00',
      createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z', version: 0,
    }), { status: 201 })));
    mount();

    await fillValidDraft();
    await userEvent.click(save());

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
  });
});

describe('InvoiceCreatePage — validation that matches the backend', () => {
  it('refuses a currency code that is not a currency', async () => {
    mount();
    await fillValidDraft();
    await userEvent.type(screen.getByLabelText(/^Currency/), '122');
    await userEvent.click(save());

    expect(await alertText()).toMatch(/valid three-letter currency/i);
  });

  it('accepts a real ISO code', async () => {
    let body: Record<string, unknown> | null = null;
    server.use(http.post('/api/v1/invoices', async ({ request }) => {
      body = await request.json() as Record<string, unknown>;
      return HttpResponse.json(envelope({
        id: 'inv-1', invoiceNumber: 'INV-2026-0001', clientId: CLIENT_ID, status: 'DRAFT',
        currency: 'INR', subtotal: '4000.00', taxAmount: '0.00', discountAmount: '0.00',
        totalAmount: '4000.00', amountPaid: '0.00', amountDue: '4000.00',
        createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z', version: 0,
      }), { status: 201 });
    }));
    mount();

    await fillValidDraft();
    await userEvent.type(screen.getByLabelText(/^Currency/), 'INR');
    await userEvent.click(save());

    await waitFor(() => expect(body).not.toBeNull());
    // Sent as a string, untouched by any arithmetic — the Phase 1 contract.
    expect(body).toMatchObject({ currency: 'INR', discountAmount: null });
  });

  it('refuses a tax rate above 100, as @DecimalMax does', async () => {
    mount();
    await fillValidDraft();
    await userEvent.type(screen.getByLabelText(/^Tax %/), '120');
    await userEvent.click(save());

    expect(await alertText()).toMatch(/cannot exceed 100/i);
  });

  it('refuses a discount larger than the invoice, which the server also refuses', async () => {
    mount();
    await fillValidDraft();
    await userEvent.type(screen.getByLabelText(/^Discount/), '9000');
    await userEvent.click(save());

    // InvoiceCalculator throws VALIDATION_FAILED for this. Catching it here means the user
    // is told before the round trip, and never sees a negative estimated total.
    expect(await alertText()).toMatch(/more than the invoice comes to/i);
  });

  it('allows a discount up to the full amount', async () => {
    let posted = 0;
    server.use(http.post('/api/v1/invoices', () => {
      posted += 1;
      return HttpResponse.json(envelope({
        id: 'inv-1', invoiceNumber: 'INV-2026-0001', clientId: CLIENT_ID, status: 'DRAFT',
        currency: 'INR', subtotal: '4000.00', taxAmount: '0.00', discountAmount: '4000.00',
        totalAmount: '0.00', amountPaid: '0.00', amountDue: '0.00',
        createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z', version: 0,
      }), { status: 201 });
    }));
    mount();

    await fillValidDraft();
    await userEvent.type(screen.getByLabelText(/^Discount/), '4000.00');
    await userEvent.click(save());

    // The boundary is inclusive on the server (`discount.compareTo(gross) > 0` throws), so
    // writing off an invoice entirely has to remain possible.
    await waitFor(() => expect(posted).toBe(1));
  });
});

describe('InvoiceCreatePage — server validation reaches the screen', () => {
  it('shows a line-item violation the form has no field for', async () => {
    server.use(http.post('/api/v1/invoices', () => HttpResponse.json({
      success: false,
      error: {
        code: 'VALIDATION_FAILED',
        message: 'That request was not valid.',
        details: [{ field: 'lineItems[0].quantity', message: 'must be greater than zero' }],
        timestamp: '2026-09-01T00:00:00Z',
      },
    }, { status: 400 })));
    mount();

    await fillValidDraft();
    await userEvent.click(save());

    expect(await alertText()).toContain('Line 1 — quantity: must be greater than zero');
  });

  it('shows a service-level refusal that names no field at all', async () => {
    server.use(http.post('/api/v1/invoices', () => HttpResponse.json({
      success: false,
      error: {
        code: 'VALIDATION_FAILED',
        message: 'A discount of 20000 is more than the invoice’s 11800',
        timestamp: '2026-09-01T00:00:00Z',
      },
    }, { status: 400 })));
    mount();

    await fillValidDraft();
    await userEvent.click(save());

    expect(await alertText()).toContain('is more than the invoice');
  });
});

describe('InvoiceCreatePage — the estimate stays an estimate', () => {
  it('never shows a negative total, whatever the discount says', async () => {
    mount();
    await fillValidDraft();
    await userEvent.type(screen.getByLabelText(/^Discount/), '9000');
    await userEvent.click(save());

    await alertText();
    const estimate = screen.getByText('Estimated total').closest('div')!;
    // The draft is blocked rather than displaying "-5000.00", which reads as though the
    // firm owes the client. The server remains the authority on every figure regardless.
    expect(within(estimate).queryByText(/^-/)).not.toBeInTheDocument();
  });
});
