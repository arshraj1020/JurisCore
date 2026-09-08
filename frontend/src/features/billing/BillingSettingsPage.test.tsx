import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { server } from '@/test/server';
import { envelope, makeUser, renderWithAuth } from '@/test/utils';
import { BillingSettingsPage } from './BillingSettingsPage';
import type { BillingProfile } from '@/types/api';

/**
 * The billing profile endpoint is a full replacement wearing a PATCH.
 *
 * <p>`BillingProfileService.update` assigns eleven fields unconditionally — `setCity`,
 * `setBillingEmail`, `setTaxRegistration` and the rest — from whatever the request carried.
 * A field left out of the JSON arrives as null and is written as null, so a "partial" update
 * of one field silently erases the firm's address, contact details and tax registration.
 *
 * The frontend used to type this call as `Partial<BillingProfile>`, which made every field
 * optional and let the compiler wave through exactly that. The type now requires the whole
 * payload; this file is the runtime half of the same guarantee, because a type only protects
 * the code that was compiled against it.
 */

const PROFILE: BillingProfile = {
  id: 'profile-1',
  legalName: 'Sharma & Associates LLP',
  taxRegistration: '27AABCU9603R1ZX',
  billingEmail: 'accounts@sharma-legal.test',
  billingPhone: '+91 22 5555 0000',
  addressLine1: '4th Floor, Fort Chambers',
  addressLine2: 'Veer Nariman Road',
  city: 'Mumbai',
  state: 'Maharashtra',
  country: 'India',
  postalCode: '400001',
  defaultCurrency: 'INR',
  invoicePrefix: 'INV',
  invoiceNotes: 'Payable within 30 days.',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  version: 3,
};

/** Every field the server overwrites on each save. */
const REPLACED_FIELDS = [
  'legalName', 'taxRegistration', 'billingEmail', 'billingPhone',
  'addressLine1', 'addressLine2', 'city', 'state', 'country', 'postalCode',
  'invoiceNotes', 'defaultCurrency', 'invoicePrefix',
] as const;

function mount() {
  server.use(
    http.post('/api/v1/auth/refresh', () => HttpResponse.json(envelope({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      tokenType: 'Bearer',
      expiresIn: 300,
      user: makeUser('FIRM_ADMIN'),
    }))),
    http.get('/api/v1/billing/profile', () => HttpResponse.json(envelope(PROFILE))),
  );
  window.localStorage.setItem('juriscore.refreshToken', 'refresh-0');
  return renderWithAuth(<BillingSettingsPage />);
}

function capturePatch() {
  const captured: { body: Record<string, unknown> | null } = { body: null };
  server.use(http.patch('/api/v1/billing/profile', async ({ request }) => {
    captured.body = await request.json() as Record<string, unknown>;
    return HttpResponse.json(envelope({ ...PROFILE, version: 4 }));
  }));
  return captured;
}

describe('BillingSettingsPage — the save is a full replacement', () => {
  it('sends every field the server overwrites, not just the edited one', async () => {
    const captured = capturePatch();
    mount();

    // Change one field, exactly as a user tweaking the prefix would.
    const prefix = await screen.findByLabelText(/^Invoice prefix/);
    await userEvent.clear(prefix);
    await userEvent.type(prefix, 'SA');
    await userEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(captured.body).not.toBeNull());

    for (const field of REPLACED_FIELDS) {
      expect(captured.body, `"${field}" must be sent or the server writes null over it`)
        .toHaveProperty(field);
    }
  });

  it('round-trips the untouched fields at their existing values', async () => {
    const captured = capturePatch();
    mount();

    const prefix = await screen.findByLabelText(/^Invoice prefix/);
    await userEvent.clear(prefix);
    await userEvent.type(prefix, 'SA');
    await userEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(captured.body).not.toBeNull());

    // Present is not enough — they have to carry the *current* values back, or the save
    // blanks them just as effectively as omitting them would.
    expect(captured.body).toMatchObject({
      legalName: 'Sharma & Associates LLP',
      taxRegistration: '27AABCU9603R1ZX',
      billingEmail: 'accounts@sharma-legal.test',
      city: 'Mumbai',
      postalCode: '400001',
      invoicePrefix: 'SA',
    });
  });

  it('carries the optimistic lock so a stale save is refused rather than merged', async () => {
    const captured = capturePatch();
    mount();

    await screen.findByLabelText(/^Invoice prefix/);
    await userEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(captured.body).not.toBeNull());
    expect(captured.body).toMatchObject({ version: 3 });
  });

  it('clears a field with an explicit null rather than by omitting it', async () => {
    const captured = capturePatch();
    mount();

    const addressLine2 = await screen.findByLabelText(/^Address line 2/);
    await userEvent.clear(addressLine2);
    await userEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(captured.body).not.toBeNull());
    // Emptying a field is a real intention, and null is how this API expresses it. The key
    // still has to be there — absence and null mean the same thing to the server, but only
    // one of them is something the UI decided on purpose.
    expect(captured.body).toHaveProperty('addressLine2', null);
  });
});
