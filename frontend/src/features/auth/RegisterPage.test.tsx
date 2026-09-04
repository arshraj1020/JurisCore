import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { server } from '@/test/server';
import { envelope, makeUser, renderWithAuth } from '@/test/utils';
import { getAccessToken, getRefreshToken } from '@/lib/auth/tokenStorage';
import { RegisterPage } from './RegisterPage';

const tokens = () => envelope({
  accessToken: 'access-1',
  refreshToken: 'refresh-1',
  tokenType: 'Bearer',
  expiresIn: 300,
  user: makeUser('FIRM_ADMIN', {
    email: 'arsh@example.test', firstName: 'Arsh', lastName: 'Raj', fullName: 'Arsh Raj',
  }),
});

async function fillTheForm() {
  await userEvent.type(await screen.findByLabelText(/Firm name/), 'xyz');
  await userEvent.type(screen.getByLabelText(/First name/), 'Arsh');
  await userEvent.type(screen.getByLabelText(/Last name/), 'Raj');
  await userEvent.type(screen.getByLabelText(/Email address/), 'arsh@example.test');
  await userEvent.type(screen.getByLabelText(/^Password/), 'correct horse battery staple');
  await userEvent.click(screen.getByRole('button', { name: 'Create firm' }));
}

describe('RegisterPage', () => {
  /**
   * The payload is asserted field by field against `RegisterRequest` on the server.
   * A firm is created by one POST — there is no separate organization call to order
   * wrongly — so what this pins down is that the one call carries exactly what the
   * backend record requires and nothing it does not.
   */
  it('creates the firm with a single POST carrying the whole payload', async () => {
    let url: string | null = null;
    let body: Record<string, unknown> | null = null;
    server.use(http.post('/api/v1/auth/register', async ({ request }) => {
      url = new URL(request.url).pathname;
      body = await request.json() as Record<string, unknown>;
      return HttpResponse.json(tokens());
    }));

    renderWithAuth(
      <Routes>
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/" element={<h1>Dashboard</h1>} />
      </Routes>,
      { route: '/register' },
    );

    await fillTheForm();

    await waitFor(() => expect(body).not.toBeNull());
    expect(url).toBe('/api/v1/auth/register');
    expect(body).toMatchObject({
      firmName: 'xyz',
      firstName: 'Arsh',
      lastName: 'Raj',
      email: 'arsh@example.test',
      password: 'correct horse battery staple',
    });
    // The browser's zone travels with the request; the backend stores it on the firm.
    expect(typeof (body as unknown as { timezone: unknown }).timezone).toBe('string');
  });

  it('signs the new administrator in and leaves the registration page', async () => {
    server.use(http.post('/api/v1/auth/register', () => HttpResponse.json(tokens())));

    renderWithAuth(
      <Routes>
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/" element={<h1>Dashboard</h1>} />
      </Routes>,
      { route: '/register' },
    );

    await fillTheForm();

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    await waitFor(() => expect(getAccessToken()).toBe('access-1'));
    expect(getRefreshToken()).toBe('refresh-1');
  });

  it('puts a rejected password on the password field, not in a banner', async () => {
    server.use(http.post('/api/v1/auth/register', () => HttpResponse.json({
      success: false,
      error: { code: 'WEAK_PASSWORD', message: 'That password is too easy to guess.' },
    }, { status: 400 })));

    renderWithAuth(<RegisterPage />);
    await fillTheForm();

    const field = await screen.findByLabelText(/^Password/);
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(await screen.findByText('That password is too easy to guess.')).toBeInTheDocument();
    expect(getAccessToken()).toBeNull();
  });

  it('shows a duplicate-firm conflict from the server as it was written', async () => {
    server.use(http.post('/api/v1/auth/register', () => HttpResponse.json({
      success: false,
      error: { code: 'EMAIL_ALREADY_REGISTERED', message: 'That email is already registered.' },
    }, { status: 409 })));

    renderWithAuth(<RegisterPage />);
    await fillTheForm();

    expect(await screen.findByRole('alert'))
      .toHaveTextContent('That email is already registered.');
  });

  /**
   * The bug this file was written for.
   *
   * With no dev-server proxy the browser sends `POST /api/v1/auth/register` to Vite,
   * which answers 404 with an empty body. The fetch layer cannot tell that from a
   * genuinely missing record, so the form reported "That record does not exist." while
   * the backend was never contacted. The message is correct for what the layer was
   * handed; the assertion below pins the behaviour so the symptom stays recognisable,
   * and `vite.config.test.ts` pins the proxy that stops it happening.
   */
  it('reports an unrouted 404 as a missing record, which is the symptom to recognise', async () => {
    server.use(http.post('/api/v1/auth/register', () =>
      new HttpResponse(null, { status: 404 })));

    renderWithAuth(<RegisterPage />);
    await fillTheForm();

    expect(await screen.findByRole('alert')).toHaveTextContent('That record does not exist.');
  });
});
