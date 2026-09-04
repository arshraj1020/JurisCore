import { Suspense, lazy } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider } from '@/lib/auth/AuthContext';
import { ToastProvider } from '@/components/ui/Toast';
import { AppShell } from '@/app/AppShell';
import {
  FullPageSpinner, ProtectedRoute, RequireFirmContext, RequirePermission,
} from '@/app/ProtectedRoute';
import { ApiError } from '@/lib/api/errors';
import { LoginPage } from '@/features/auth/LoginPage';
import { RegisterPage } from '@/features/auth/RegisterPage';
import { NotFoundPage } from '@/app/NotFoundPage';

/**
 * The workspace is split out of the entry chunk.
 *
 * The sign-in page is the first thing an unauthenticated visitor loads, and it has no
 * business shipping the invoice editor, the audit trail and the document uploader with
 * it. Everything behind the auth gate is fetched on the way to the route that needs it.
 */
const ProfilePage = lazy(() => import('@/features/auth/ProfilePage').then((m) => ({ default: m.ProfilePage })));
const MembersPage = lazy(() => import('@/features/auth/MembersPage').then((m) => ({ default: m.MembersPage })));
const DashboardPage = lazy(() => import('@/features/dashboard/DashboardPage').then((m) => ({ default: m.DashboardPage })));
const ClientListPage = lazy(() => import('@/features/clients/ClientListPage').then((m) => ({ default: m.ClientListPage })));
const ClientDetailPage = lazy(() => import('@/features/clients/ClientDetailPage').then((m) => ({ default: m.ClientDetailPage })));
const CaseListPage = lazy(() => import('@/features/cases/CaseListPage').then((m) => ({ default: m.CaseListPage })));
const CaseDetailPage = lazy(() => import('@/features/cases/CaseDetailPage').then((m) => ({ default: m.CaseDetailPage })));
const HearingListPage = lazy(() => import('@/features/case-management/HearingListPage').then((m) => ({ default: m.HearingListPage })));
const CourtListPage = lazy(() => import('@/features/case-management/CourtListPage').then((m) => ({ default: m.CourtListPage })));
const InvoiceListPage = lazy(() => import('@/features/billing/InvoiceListPage').then((m) => ({ default: m.InvoiceListPage })));
const InvoiceCreatePage = lazy(() => import('@/features/billing/InvoiceCreatePage').then((m) => ({ default: m.InvoiceCreatePage })));
const InvoiceDetailPage = lazy(() => import('@/features/billing/InvoiceDetailPage').then((m) => ({ default: m.InvoiceDetailPage })));
const BillingSettingsPage = lazy(() => import('@/features/billing/BillingSettingsPage').then((m) => ({ default: m.BillingSettingsPage })));
const NotificationsPage = lazy(() => import('@/features/notifications/NotificationsPage').then((m) => ({ default: m.NotificationsPage })));
const AuditPage = lazy(() => import('@/features/audit/AuditPage').then((m) => ({ default: m.AuditPage })));

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        retry: (failureCount, error) => {
          // Retrying a 403 or a 404 cannot help, and retrying a 401 races the refresh
          // the HTTP layer is already doing. Only transient failures are worth a retry.
          if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false;
          return failureCount < 2;
        },
      },
      mutations: { retry: false },
    },
  });
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />

      <Route element={<ProtectedRoute />}>
        <Route element={(
          <RequireFirmContext>
            <Suspense fallback={<FullPageSpinner />}>
              <AppShell />
            </Suspense>
          </RequireFirmContext>
        )}>
          <Route index element={<DashboardPage />} />
          <Route path="profile" element={<ProfilePage />} />
          <Route path="notifications" element={<NotificationsPage />} />

          <Route element={<RequirePermission permission="viewCasework" />}>
            <Route path="clients" element={<ClientListPage />} />
            <Route path="clients/:clientId" element={<ClientDetailPage />} />
            <Route path="cases" element={<CaseListPage />} />
            <Route path="cases/:caseId" element={<CaseDetailPage />} />
            <Route path="hearings" element={<HearingListPage />} />
            <Route path="courts" element={<CourtListPage />} />
          </Route>

          <Route element={<RequirePermission permission="viewMembers" />}>
            <Route path="members" element={<MembersPage />} />
          </Route>

          <Route element={<RequirePermission permission="viewBilling" />}>
            <Route path="invoices" element={<InvoiceListPage />} />
            <Route path="invoices/new" element={<InvoiceCreatePage />} />
            <Route path="invoices/:invoiceId" element={<InvoiceDetailPage />} />
          </Route>

          <Route element={<RequirePermission permission="viewBillingSettings" />}>
            <Route path="billing/settings" element={<BillingSettingsPage />} />
          </Route>

          <Route element={<RequirePermission permission="viewAudit" />}>
            <Route path="audit" element={<AuditPage />} />
          </Route>

          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export function App() {
  return (
    <QueryClientProvider client={createQueryClient()}>
      <BrowserRouter>
        <AuthProvider>
          <ToastProvider>
            <a href="#main"
              className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50 focus:rounded focus:bg-white focus:px-3 focus:py-2 focus:text-sm focus:shadow">
              Skip to content
            </a>
            <AppRoutes />
          </ToastProvider>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
