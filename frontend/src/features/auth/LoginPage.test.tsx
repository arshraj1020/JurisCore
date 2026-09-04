import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { server } from '@/test/server';
import { envelope, makeUser, renderWithAuth } from '@/test/utils';
import { getAccessToken, getRefreshToken } from '@/lib/auth/tokenStorage';
import { LoginPage } from './LoginPage';

const tokens = (user = makeUser()) => envelope({
  accessToken: 'access-1',
  refreshToken: 'refresh-1',
  tokenType: 'Bearer',
  expiresIn: 300,
  user,
});

describe('LoginPage', () => {
  it('validates before sending anything to the server', async () => {
    // No handler is registered, so any request would fail the suite outright — which is
    // the assertion: an invalid form must not reach the API.
    renderWithAuth(<LoginPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Enter your email address')).toBeInTheDocument();
    expect(screen.getByText('Enter your password')).toBeInTheDocument();
  });

  it('signs in and lands on the page the user was originally sent away from', async () => {
    server.use(http.post('/api/v1/auth/login', () => HttpResponse.json(tokens())));

    renderWithAuth(
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<h1>Dashboard</h1>} />
      </Routes>,
      { route: '/login' },
    );

    await userEvent.type(await screen.findByLabelText(/Email address/), 'asha@example.test');
    await userEvent.type(screen.getByLabelText(/^Password/), 'correct horse battery');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    await waitFor(() => expect(getAccessToken()).toBe('access-1'));
    expect(getRefreshToken()).toBe('refresh-1');
  });

  it('shows the backend message without revealing whether the account exists', async () => {
    server.use(http.post('/api/v1/auth/login', () => HttpResponse.json({
      success: false,
      error: { code: 'INVALID_CREDENTIALS', message: 'Email or password is incorrect.' },
    }, { status: 401 })));

    renderWithAuth(<LoginPage />);

    await userEvent.type(await screen.findByLabelText(/Email address/), 'asha@example.test');
    await userEvent.type(screen.getByLabelText(/^Password/), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Email or password is incorrect.');
    expect(alert.textContent).not.toMatch(/no such account|unknown user|not registered/i);
    // A failed sign-in must not leave a usable credential behind.
    expect(getAccessToken()).toBeNull();
  });

  it('keeps an already-signed-in user off the sign-in page', async () => {
    server.use(
      http.post('/api/v1/auth/refresh', () => HttpResponse.json(tokens())),
    );
    window.localStorage.setItem('juriscore.refreshToken', 'refresh-0');

    renderWithAuth(
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<h1>Dashboard</h1>} />
      </Routes>,
      { route: '/login' },
    );

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign in' })).not.toBeInTheDocument();
  });
});
