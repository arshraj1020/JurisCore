import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { NAVIGATION } from './navigation';
import { api } from '@/lib/api/client';
import { keys } from '@/lib/api/queryKeys';
import { cn } from '@/lib/cn';
import { Button } from '@/components/ui/primitives';
import type { Organization } from '@/types/api';

function useUnreadCount() {
  return useQuery({
    queryKey: keys.notifications.unreadCount,
    queryFn: () => api.get<{ unread: number }>('/api/v1/notifications/unread-count'),
    // The backend has no push channel, so the badge is polled. A minute is often enough
    // to feel live and rare enough not to be a background load on the API.
    refetchInterval: 60_000,
    staleTime: 30_000,
  });
}

function useOrganization() {
  return useQuery({
    queryKey: keys.organization,
    queryFn: () => api.get<Organization>('/api/v1/organizations/current'),
    staleTime: 5 * 60_000,
  });
}

function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  const { user } = useAuth();
  const { data: organization } = useOrganization();

  return (
    <div className="flex h-full flex-col bg-ink-900 text-ink-200">
      <div className="flex h-14 shrink-0 items-center gap-2 border-b border-ink-800 px-4">
        <span className="grid h-7 w-7 place-items-center rounded bg-brand-500 text-xs font-bold text-white">
          JC
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-white">JurisCore</p>
          {organization && (
            <p className="truncate text-[11px] text-ink-400">{organization.name}</p>
          )}
        </div>
      </div>

      <nav aria-label="Main" className="flex-1 overflow-y-auto px-2 py-3">
        {NAVIGATION.map((section) => {
          const items = section.items.filter(
            (item) => !item.permission || can(user?.role, item.permission),
          );
          if (items.length === 0) return null;
          return (
            <div key={section.label} className="mb-4">
              <p className="px-2 pb-1 text-[11px] font-semibold uppercase tracking-wider text-ink-500">
                {section.label}
              </p>
              <ul className="space-y-0.5">
                {items.map((item) => (
                  <li key={item.to}>
                    <NavLink
                      to={item.to}
                      end={item.to === '/'}
                      onClick={onNavigate}
                      className={({ isActive }) => cn(
                        'block rounded-md px-2 py-1.5 text-sm transition-colors',
                        isActive
                          ? 'bg-ink-800 font-medium text-white'
                          : 'text-ink-300 hover:bg-ink-800/60 hover:text-white',
                      )}
                    >
                      {item.label}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </div>
          );
        })}
      </nav>
    </div>
  );
}

function UserMenu() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open) return;
    const close = () => setOpen(false);
    window.addEventListener('click', close);
    return () => window.removeEventListener('click', close);
  }, [open]);

  if (!user) return null;

  return (
    <div className="relative" onClick={(event) => event.stopPropagation()}>
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        aria-haspopup="menu"
        className="flex items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-ink-100"
      >
        <span className="grid h-7 w-7 place-items-center rounded-full bg-brand-100 text-xs font-semibold text-brand-800">
          {user.firstName.charAt(0)}{user.lastName.charAt(0)}
        </span>
        <span className="hidden text-left sm:block">
          <span className="block text-sm font-medium leading-tight text-ink-900">{user.fullName}</span>
          <span className="block text-[11px] leading-tight text-ink-500">
            {user.role.replace(/_/g, ' ').toLowerCase()}
          </span>
        </span>
      </button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 z-30 mt-1 w-56 rounded-md bg-white py-1 shadow-lg ring-1 ring-ink-200"
        >
          <div className="border-b border-ink-100 px-3 py-2">
            <p className="truncate text-sm font-medium text-ink-900">{user.fullName}</p>
            <p className="truncate text-xs text-ink-500">{user.email}</p>
          </div>
          <button
            type="button" role="menuitem"
            onClick={() => { setOpen(false); navigate('/profile'); }}
            className="block w-full px-3 py-2 text-left text-sm text-ink-700 hover:bg-ink-50"
          >
            Your profile
          </button>
          <button
            type="button" role="menuitem"
            onClick={async () => { setOpen(false); await logout(); navigate('/login', { replace: true }); }}
            className="block w-full px-3 py-2 text-left text-sm text-ink-700 hover:bg-ink-50"
          >
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}

function NotificationBell() {
  const { data } = useUnreadCount();
  const unread = data?.unread ?? 0;

  return (
    <NavLink
      to="/notifications"
      className="relative rounded-md p-2 text-ink-600 hover:bg-ink-100 hover:text-ink-900"
      aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}
    >
      <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="1.75" aria-hidden="true">
        <path strokeLinecap="round" strokeLinejoin="round"
          d="M15 17h5l-1.4-1.4A2 2 0 0118 14.2V11a6 6 0 10-12 0v3.2a2 2 0 01-.6 1.4L4 17h5m6 0a3 3 0 11-6 0m6 0H9" />
      </svg>
      {unread > 0 && (
        <span className="absolute right-1 top-1 grid min-w-[1.1rem] place-items-center rounded-full bg-red-600 px-1 text-[10px] font-semibold leading-4 text-white">
          {unread > 99 ? '99+' : unread}
        </span>
      )}
    </NavLink>
  );
}

/**
 * The authenticated frame: a persistent sidebar on wide screens, a dismissible drawer on
 * narrow ones, and a header that stays put.
 *
 * The drawer closes on navigation — leaving it open over the page somebody just chose is
 * a small thing that makes an application feel unfinished on a phone.
 */
export function AppShell() {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const location = useLocation();

  useEffect(() => { setDrawerOpen(false); }, [location.pathname]);

  return (
    <div className="min-h-screen lg:flex">
      <aside className="hidden w-60 shrink-0 lg:block">
        <div className="fixed inset-y-0 w-60"><Sidebar /></div>
      </aside>

      {drawerOpen && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div
            className="absolute inset-0 bg-ink-950/40"
            onClick={() => setDrawerOpen(false)}
            aria-hidden="true"
          />
          <div className="absolute inset-y-0 left-0 w-64 shadow-xl">
            <Sidebar onNavigate={() => setDrawerOpen(false)} />
          </div>
        </div>
      )}

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-20 flex h-14 items-center gap-2 border-b border-ink-200 bg-white px-3 sm:px-4">
          <Button
            variant="ghost" size="sm" className="lg:hidden"
            onClick={() => setDrawerOpen(true)}
            aria-label="Open navigation" aria-expanded={drawerOpen}
          >
            <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              strokeWidth="1.75" aria-hidden="true">
              <path strokeLinecap="round" d="M4 7h16M4 12h16M4 17h16" />
            </svg>
          </Button>
          <div className="flex-1" />
          <NotificationBell />
          <UserMenu />
        </header>

        <main id="main" className="flex-1 px-3 py-4 sm:px-6 sm:py-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
