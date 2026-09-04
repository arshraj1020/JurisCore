import type { ReactElement, ReactNode } from 'react';
import { render } from '@testing-library/react';
import type { RenderResult } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '@/components/ui/Toast';
import { AuthProvider } from '@/lib/auth/AuthContext';
import type { ApiResponse, PageResponse, Role, User } from '@/types/api';

/** A query client with retries off, so a test asserting an error state does not wait. */
export function testQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
      mutations: { retry: false },
    },
  });
}

export function renderWithProviders(
  ui: ReactElement,
  options: { route?: string; path?: string } = {},
): RenderResult {
  const { route = '/', path } = options;
  const client = testQueryClient();

  const wrapper = (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>
        <ToastProvider>
          {path ? <Routes><Route path={path} element={ui} /></Routes> : ui}
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>
  );

  return render(wrapper);
}

/** The same tree, plus a real `AuthProvider` — for anything that reads `useAuth`. */
export function renderWithAuth(
  ui: ReactNode,
  options: { route?: string; path?: string } = {},
): RenderResult {
  const { route = '/', path } = options;
  const client = testQueryClient();

  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>
        <AuthProvider>
          <ToastProvider>
            {path ? <Routes><Route path={path} element={ui} /></Routes> : ui}
          </ToastProvider>
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// ------------------------------------------------------------------ fixtures

/** The backend's success envelope, so fixtures cannot drift from the real shape. */
export const envelope = <T,>(data: T): ApiResponse<T> => ({ success: true, data });

export const pageOf = <T,>(items: T[], overrides: Partial<PageResponse<T>> = {}):
PageResponse<T> => ({
  items,
  page: 0,
  size: 20,
  totalItems: items.length,
  totalPages: items.length === 0 ? 0 : 1,
  hasNext: false,
  ...overrides,
});

export function makeUser(role: Role = 'FIRM_ADMIN', overrides: Partial<User> = {}): User {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    organizationId: '22222222-2222-2222-2222-222222222222',
    email: 'asha@example.test',
    firstName: 'Asha',
    lastName: 'Rao',
    fullName: 'Asha Rao',
    role,
    status: 'ACTIVE',
    createdAt: '2026-01-05T09:00:00Z',
    ...overrides,
  };
}
