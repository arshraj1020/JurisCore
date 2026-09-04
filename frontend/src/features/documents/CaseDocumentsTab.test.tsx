import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { server } from '@/test/server';
import { envelope, makeUser, pageOf, renderWithAuth } from '@/test/utils';
import { CaseDocumentsTab } from './CaseDocumentsTab';
import type { CaseDocument, Role } from '@/types/api';

const CASE_ID = '55555555-5555-5555-5555-555555555555';
const DOC_ID = '66666666-6666-6666-6666-666666666666';
const UPLOAD_URL = 'https://storage.test/bucket/organizations/1/documents/66666666';

function document(overrides: Partial<CaseDocument> = {}): CaseDocument {
  return {
    id: DOC_ID,
    caseId: CASE_ID,
    filename: 'petition.pdf',
    contentType: 'application/pdf',
    fileSize: 6,
    status: 'UPLOADING',
    createdAt: '2026-08-01T09:00:00Z',
    updatedAt: '2026-08-01T09:00:00Z',
    version: 0,
    ...overrides,
  };
}

function mount(role: Role, documents: CaseDocument[] = []) {
  server.use(
    http.post('/api/v1/auth/refresh', () => HttpResponse.json(envelope({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      tokenType: 'Bearer',
      expiresIn: 300,
      user: makeUser(role),
    }))),
    http.get(`/api/v1/cases/${CASE_ID}/documents`, () =>
      HttpResponse.json(envelope(pageOf(documents)))),
  );
  window.localStorage.setItem('juriscore.refreshToken', 'refresh-0');
  return renderWithAuth(<CaseDocumentsTab caseId={CASE_ID} />);
}

const pdf = () => new File(['filing'], 'petition.pdf', { type: 'application/pdf' });

describe('CaseDocumentsTab — presigned upload', () => {
  /**
   * The Phase 4 contract in order: register, PUT the bytes straight to storage, then tell
   * the backend they landed. The bytes must never travel through the Spring API, and the
   * storage request must not carry our bearer token.
   */
  it('registers, uploads to storage without our token, then completes', async () => {
    const calls: string[] = [];
    let storageAuthorization: string | null = 'unset';
    let registered: Record<string, unknown> | null = null;

    server.use(
      http.post(`/api/v1/cases/${CASE_ID}/documents`, async ({ request }) => {
        calls.push('register');
        registered = await request.json() as Record<string, unknown>;
        return HttpResponse.json(envelope({
          document: document(),
          uploadUrl: UPLOAD_URL,
          uploadMethod: 'PUT',
          requiredContentType: 'application/pdf',
          expiresAt: '2026-08-01T09:15:00Z',
          expiresInSeconds: 900,
        }));
      }),
      http.put(UPLOAD_URL, ({ request }) => {
        calls.push('storage');
        storageAuthorization = request.headers.get('Authorization');
        return new HttpResponse(null, { status: 200 });
      }),
      http.post(`/api/v1/documents/${DOC_ID}/complete`, () => {
        calls.push('complete');
        return HttpResponse.json(envelope(document({ status: 'AVAILABLE' })));
      }),
    );

    mount('LAWYER');
    await userEvent.upload(
      await screen.findByLabelText('Choose files to upload'),
      pdf(),
    );

    await waitFor(() => expect(calls).toEqual(['register', 'storage', 'complete']));
    expect(storageAuthorization).toBeNull();
    expect(registered).toMatchObject({
      filename: 'petition.pdf', contentType: 'application/pdf', fileSize: 6,
    });
  });

  it('explains an expired link and mints a fresh one on retry', async () => {
    let registrations = 0;
    let storageAttempts = 0;

    server.use(
      http.post(`/api/v1/cases/${CASE_ID}/documents`, () => {
        registrations += 1;
        return HttpResponse.json(envelope({
          document: document(),
          uploadUrl: `${UPLOAD_URL}?sig=${registrations}`,
          uploadMethod: 'PUT',
          requiredContentType: 'application/pdf',
          expiresAt: '2026-08-01T09:15:00Z',
          expiresInSeconds: 900,
        }));
      }),
      http.put(UPLOAD_URL, () => {
        storageAttempts += 1;
        // The first signature has gone stale; the second one works.
        return new HttpResponse(null, { status: storageAttempts === 1 ? 403 : 200 });
      }),
      http.post(`/api/v1/documents/${DOC_ID}/complete`, () =>
        HttpResponse.json(envelope(document({ status: 'AVAILABLE' })))),
    );

    mount('LAWYER');
    await userEvent.upload(await screen.findByLabelText('Choose files to upload'), pdf());

    expect(await screen.findByText(/upload link expired/i)).toBeInTheDocument();
    expect(registrations).toBe(1);

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));

    // The retry starts again at registration rather than replaying the dead signature.
    await waitFor(() => expect(registrations).toBe(2));
    await waitFor(() => expect(screen.queryByText(/upload link expired/i)).not.toBeInTheDocument());
  });

  it('does not offer uploading to a role that cannot upload', async () => {
    mount('CLIENT', [document({ status: 'AVAILABLE' })]);

    await screen.findByText('petition.pdf');
    expect(screen.queryByRole('button', { name: 'Upload files' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
  });

  it('keeps deletion to a firm administrator', async () => {
    mount('LAWYER', [document({ status: 'AVAILABLE' })]);

    await screen.findByText('petition.pdf');
    expect(screen.getByRole('button', { name: 'Download' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
  });

  it('says plainly when a document\'s bytes never arrived', async () => {
    mount('FIRM_ADMIN', [document({ status: 'FAILED' })]);

    expect(await screen.findByText(/never arrived in storage/i)).toBeInTheDocument();
    // Nothing to download: the row exists but the file does not.
    expect(screen.queryByRole('button', { name: 'Download' })).not.toBeInTheDocument();
  });
});
