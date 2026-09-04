import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '@/lib/auth/AuthContext';
import { can, isFirmStaff } from '@/lib/auth/roles';
import type { Permission } from '@/lib/auth/roles';
import { Spinner } from '@/components/ui/primitives';
import { EmptyState } from '@/components/ui/states';

function FullPageSpinner() {
  return (
    <div className="flex min-h-screen items-center justify-center" role="status">
      <Spinner className="h-6 w-6 text-brand-600" />
      <span className="sr-only">Loading…</span>
    </div>
  );
}

/**
 * The gate every application route sits behind.
 *
 * It waits for `initialising` rather than redirecting immediately, because the access
 * token lives in memory: on a page reload there is briefly no session even for a user who
 * has one, and redirecting during that window would sign people out every time they hit
 * refresh.
 *
 * The redirect remembers where the user was going, so signing in returns them there
 * rather than dumping them on the dashboard.
 */
export function ProtectedRoute() {
  const { user, initialising } = useAuth();
  const location = useLocation();

  if (initialising) return <FullPageSpinner />;
  if (!user) return <Navigate to="/login" replace state={{ from: location }} />;
  return <Outlet />;
}

/**
 * A route that also requires a particular capability.
 *
 * UX only. The backend enforces the same rule and answers 403 regardless of what this
 * component decides — a user who types the URL of a page they cannot use gets a clear
 * message here instead of a screenful of failed requests, which is the entire benefit.
 */
export function RequirePermission({ permission }: { permission: Permission }) {
  const { user } = useAuth();
  if (!can(user?.role, permission)) {
    return (
      <EmptyState
        title="You do not have access to this"
        description="Your role does not include this area of the firm. If you think that is wrong, ask an administrator."
      />
    );
  }
  return <Outlet />;
}

/**
 * Keeps roles with no tenant out of the tenant application.
 *
 * A SUPER_ADMIN has no organization, so every tenant-scoped endpoint refuses it at
 * `requireOrganizationId()`. Letting it into the shell would render a dashboard of
 * errors; saying so plainly is more useful than six failed panels.
 */
export function RequireFirmContext({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  if (user && !isFirmStaff(user.role)) {
    return (
      <div className="mx-auto max-w-lg px-4 py-16">
        <EmptyState
          title={user.role === 'SUPER_ADMIN' ? 'Platform accounts have no firm workspace' : 'No workspace for this account'}
          description={
            user.role === 'SUPER_ADMIN'
              ? 'A platform administrator is not scoped to any single firm, so the firm workspace does not apply. Sign in with a firm account to use it.'
              : 'This account does not have access to the firm workspace.'
          }
        />
      </div>
    );
  }
  return <>{children}</>;
}

export { FullPageSpinner };
