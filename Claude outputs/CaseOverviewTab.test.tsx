import { describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { server } from '@/test/server';
import { envelope, makeUser, pageOf, renderWithAuth } from '@/test/utils';
import { CaseOverviewTab } from './CaseOverviewTab';
import type { CaseAssignment, LegalCase } from '@/types/api';

/**
 * The lead-lawyer invariant, as the interface has to present it.
 *
 * `CaseAssignmentService.unassign` refuses to remove the lead unless the same request names
 * a successor who is already on the matter, and refuses outright when the lead is the only
 * lawyer. The old dialog knew none of that: it offered "Remove", promised the matter would
 * be "left without a lead", and collected a 400. These tests pin the two rules the UI now
 * mirrors — never weakening them, only refusing to offer what the server will not do.
 */

const CASE_ID = '55555555-5555-5555-5555-555555555555';
const LEAD_ID = '11111111-1111-1111-1111-111111111111';
const SECOND_ID = '99999999-9999-9999-9999-999999999999';

const legalCase: LegalCase = {
  id: CASE_ID,
  caseNumber: 'CASE-2026-0001',
  title: 'Menon v. Iyer',
  status: 'OPEN',
  clientId: '44444444-4444-4444-4444-444444444444',
  openedAt: '2026-01-05',
  createdAt: '2026-01-05T09:00:00Z',
  updatedAt: '2026-01-05T09:00:00Z',
  version: 0,
};

function assignment(lawyerUserId: string, lead: boolean): CaseAssignment {
  return {
    id: `assignment-${lawyerUserId}`,
    caseId: CASE_ID,
    lawyerUserId,
    lead,
    assignedAt: '2026-01-06T09:00:00Z',
  };
}

interface Removal { url: string }

function mount(assignments: CaseAssignment[], removals: Removal[] = []) {
  server.use(
    http.post('/api/v1/auth/refresh', () => HttpResponse.json(envelope({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      tokenType: 'Bearer',
      expiresIn: 300,
      user: makeUser('FIRM_ADMIN'),
    }))),
    http.get(`/api/v1/cases/${CASE_ID}/assignments`, () =>
      HttpResponse.json(envelope(assignments))),
    http.get('/api/v1/users', () => HttpResponse.json(envelope(pageOf([
      makeUser('LAWYER', { id: LEAD_ID, fullName: 'Asha Rao' }),
      makeUser('LAWYER', {
        id: SECOND_ID, fullName: 'Vikram Nair', email: 'vikram@example.test',
      }),
    ])))),
    http.delete(`/api/v1/cases/${CASE_ID}/assignments/:lawyerUserId`, ({ request }) => {
      removals.push({ url: request.url });
      return HttpResponse.json(envelope(null));
    }),
  );
  window.localStorage.setItem('juriscore.refreshToken', 'refresh-0');
  return renderWithAuth(<CaseOverviewTab legalCase={legalCase} />);
}

async function openRemoveFor(name: string) {
  const row = (await screen.findByText(name)).closest('li')!;
  await userEvent.click(within(row).getByRole('button', { name: 'Remove' }));
  return screen.findByRole('dialog');
}

describe('CaseOverviewTab — removing a lawyer who is not the lead', () => {
  it('is a plain confirmation, with no successor to choose', async () => {
    const removals: Removal[] = [];
    mount([assignment(LEAD_ID, true), assignment(SECOND_ID, false)], removals);

    const dialog = await openRemoveFor('Vikram Nair');
    expect(within(dialog).queryByLabelText(/new lead/i)).not.toBeInTheDocument();

    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove' }));

    await waitFor(() => expect(removals).toHaveLength(1));
    // No successor is sent, because none is needed.
    expect(removals[0]!.url).not.toContain('newLeadUserId');
    expect(removals[0]!.url).toContain(SECOND_ID);
  });
});

describe('CaseOverviewTab — removing the lead', () => {
  it('requires a successor before the action can be taken', async () => {
    mount([assignment(LEAD_ID, true), assignment(SECOND_ID, false)]);

    const dialog = await openRemoveFor('Asha Rao');

    // The button is not offered as a live action until the request can succeed.
    expect(within(dialog).getByRole('button', { name: 'Remove' })).toBeDisabled();
    expect(within(dialog).getByLabelText(/new lead/i)).toBeInTheDocument();
  });

  it('offers only the lawyers already on the matter as successors', async () => {
    mount([assignment(LEAD_ID, true), assignment(SECOND_ID, false)]);

    const dialog = await openRemoveFor('Asha Rao');
    const select = within(dialog).getByLabelText(/new lead/i);

    // "The new lead must already be assigned to this case" — so the person leaving, and
    // anybody not on the matter, must not appear.
    expect(within(select).getByRole('option', { name: 'Vikram Nair' })).toBeInTheDocument();
    expect(within(select).queryByRole('option', { name: 'Asha Rao' })).not.toBeInTheDocument();
  });

  it('sends the successor with the removal once one is chosen', async () => {
    const removals: Removal[] = [];
    mount([assignment(LEAD_ID, true), assignment(SECOND_ID, false)], removals);

    const dialog = await openRemoveFor('Asha Rao');
    await userEvent.selectOptions(within(dialog).getByLabelText(/new lead/i), SECOND_ID);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove' }));

    await waitFor(() => expect(removals).toHaveLength(1));
    // One request that satisfies the invariant, rather than a removal that fails and a
    // promotion that never happens.
    expect(removals[0]!.url).toContain(`newLeadUserId=${SECOND_ID}`);
  });
});

describe('CaseOverviewTab — the lead is the only lawyer', () => {
  it('explains why they cannot be removed instead of offering a doomed action', async () => {
    const removals: Removal[] = [];
    mount([assignment(LEAD_ID, true)], removals);

    const dialog = await openRemoveFor('Asha Rao');

    expect(dialog).toHaveTextContent(/nobody to take the lead/i);
    expect(dialog).toHaveTextContent(/assign another lawyer first/i);
    expect(within(dialog).getByRole('button', { name: 'Remove' })).toBeDisabled();

    // And the invariant is not worked around: nothing is sent.
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(removals).toHaveLength(0);
  });

  it('still allows removing a sole lawyer who is not the lead', async () => {
    // An unusual state, but the server permits it, so the UI must not be stricter.
    const removals: Removal[] = [];
    mount([assignment(SECOND_ID, false)], removals);

    const dialog = await openRemoveFor('Vikram Nair');
    expect(within(dialog).getByRole('button', { name: 'Remove' })).toBeEnabled();

    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(removals).toHaveLength(1));
  });
});

describe('CaseOverviewTab — a server refusal is still shown', () => {
  it('reports a rejection rather than closing as though it worked', async () => {
    mount([assignment(LEAD_ID, true), assignment(SECOND_ID, false)]);
    server.use(http.delete(`/api/v1/cases/${CASE_ID}/assignments/:lawyerUserId`, () =>
      HttpResponse.json({
        success: false,
        error: {
          code: 'INVALID_ARGUMENT',
          message: 'The new lead must already be assigned to this case',
          timestamp: '2026-09-08T00:00:00Z',
        },
      }, { status: 400 })));

    const dialog = await openRemoveFor('Asha Rao');
    await userEvent.selectOptions(within(dialog).getByLabelText(/new lead/i), SECOND_ID);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove' }));

    expect(await within(dialog).findByRole('alert'))
      .toHaveTextContent('The new lead must already be assigned to this case');
  });
});
