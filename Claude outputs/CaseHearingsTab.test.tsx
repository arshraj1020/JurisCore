import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { server } from '@/test/server';
import { envelope, makeUser, pageOf, renderWithAuth } from '@/test/utils';
import { CaseHearingsTab } from './CaseHearingsTab';
import type { Court, Hearing, HearingStatus } from '@/types/api';

/**
 * "No courts are on file yet" is a claim about the data, and the dialog used to make it
 * whenever `courts.data` was absent — including when the request had failed. The user was
 * told to go and create a court they already had, with no way to try again and no hint that
 * anything had gone wrong. An error is not an empty list.
 */

const CASE_ID = '55555555-5555-5555-5555-555555555555';
const COURT_ID = '77777777-7777-7777-7777-777777777777';

function court(): Court {
  return {
    id: COURT_ID,
    name: 'Bombay High Court',
    courtType: 'HIGH',
    city: 'Mumbai',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    version: 0,
  };
}

function hearing(status: HearingStatus): Hearing {
  return {
    id: '88888888-8888-8888-8888-888888888888',
    caseId: CASE_ID,
    courtId: COURT_ID,
    hearingType: 'MENTION',
    status,
    scheduledAt: '2026-10-01T04:30:00Z',
    createdAt: '2026-08-01T09:00:00Z',
    updatedAt: '2026-08-01T09:00:00Z',
    version: 0,
  };
}

function mount(courts: () => Response, hearings: Hearing[] = []) {
  server.use(
    http.post('/api/v1/auth/refresh', () => HttpResponse.json(envelope({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      tokenType: 'Bearer',
      expiresIn: 300,
      user: makeUser('FIRM_ADMIN'),
    }))),
    http.get('/api/v1/hearings', () => HttpResponse.json(envelope(pageOf(hearings)))),
    http.get('/api/v1/courts', courts),
  );
  window.localStorage.setItem('juriscore.refreshToken', 'refresh-0');
  return renderWithAuth(<CaseHearingsTab caseId={CASE_ID} />);
}

const okCourts = () => HttpResponse.json(envelope(pageOf([court()])));
const noCourts = () => HttpResponse.json(envelope(pageOf([])));
const brokenCourts = () => HttpResponse.json({
  success: false,
  error: {
    code: 'INTERNAL_ERROR',
    message: 'Something went wrong on the server.',
    timestamp: '2026-09-08T00:00:00Z',
  },
}, { status: 500 });

async function openDialog() {
  await userEvent.click(await screen.findByRole('button', { name: /schedule/i }));
  return screen.findByRole('dialog');
}

describe('CaseHearingsTab — the court list', () => {
  it('says the firm has no courts when the query succeeded and returned none', async () => {
    mount(noCourts);
    await openDialog();

    expect(await screen.findByText(/no courts are on file yet/i)).toBeInTheDocument();
    expect(screen.queryByText(/couldn.t load courts/i)).not.toBeInTheDocument();
  });

  it('says the courts could not be loaded when the query failed', async () => {
    mount(brokenCourts);
    await openDialog();

    expect(await screen.findByText(/couldn.t load courts/i)).toBeInTheDocument();
    // Emphatically not this: it would send the user off to create a court they have.
    expect(screen.queryByText(/no courts are on file yet/i)).not.toBeInTheDocument();
  });

  it('offers a retry that fetches again and recovers', async () => {
    let attempt = 0;
    mount(() => {
      attempt += 1;
      return attempt === 1 ? brokenCourts() : okCourts();
    });
    await openDialog();

    await userEvent.click(await screen.findByRole('button', { name: 'Retry' }));

    await waitFor(() =>
      expect(screen.queryByText(/couldn.t load courts/i)).not.toBeInTheDocument());
    expect(await screen.findByRole('option', { name: /Bombay High Court/ })).toBeInTheDocument();
  });

  it('does not offer a court to pick while the list is unavailable', async () => {
    mount(brokenCourts);
    const dialog = await openDialog();

    // Choosing from an empty dropdown and pressing Schedule would fail on the server for
    // reasons unrelated to what actually went wrong.
    await waitFor(() =>
      expect(screen.getByLabelText(/^Court/)).toBeDisabled());
    expect(dialog).toHaveTextContent(/courts unavailable/i);
  });

  it('lists the courts normally when the query works', async () => {
    mount(okCourts);
    await openDialog();

    expect(await screen.findByRole('option', { name: /Bombay High Court/ })).toBeInTheDocument();
    expect(screen.queryByText(/couldn.t load courts/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/no courts are on file yet/i)).not.toBeInTheDocument();
  });
});

describe('CaseHearingsTab — transitions the backend actually allows', () => {
  it('offers relisting but not completion on an adjourned hearing', async () => {
    mount(okCourts, [hearing('ADJOURNED')]);

    expect(await screen.findByRole('button', { name: 'Relist' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /mark cancelled/i })).toBeInTheDocument();
    // HearingStatusPolicy has no ADJOURNED → COMPLETED edge; the button used to exist and
    // collected a 409 every time it was pressed.
    expect(screen.queryByRole('button', { name: /mark completed/i })).not.toBeInTheDocument();
  });

  it('offers every real ending on a scheduled hearing', async () => {
    mount(okCourts, [hearing('SCHEDULED')]);

    expect(await screen.findByRole('button', { name: /mark completed/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /mark adjourned/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /mark cancelled/i })).toBeInTheDocument();
  });

  it('offers nothing on a completed hearing', async () => {
    mount(okCourts, [hearing('COMPLETED')]);

    await screen.findByText(/Bombay High Court/);
    for (const label of [/mark /i, /relist/i]) {
      expect(screen.queryByRole('button', { name: label })).not.toBeInTheDocument();
    }
  });
});
