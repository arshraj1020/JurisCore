import { useEffect, useRef, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { NAVIGATION } from './navigation';
import { api } from '@/lib/api/client';
import { keys } from '@/lib/api/queryKeys';
import { cn } from '@/lib/cn';
import { humanise } from '@/lib/format';
import { Avatar, IconButton } from '@/components/ui/primitives';
import { Icon, Wordmark } from '@/components/ui/icons';
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
    <div className="flex h-full flex-col bg-ink-900">
      {/* The firm's name sits under the product's, because the question a user asks when
          they work across two firms' installations is "whose workspace am I in?" */}
      <div className="flex h-14 shrink-0 items-center gap-2.5 border-b border-white/10 px-4">
        <Wordmark className="h-7 w-7 text-brand-500" />
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold tracking-tight text-white">JurisCore</p>
          <p className="truncate text-2xs text-ink-300">
            {organization?.name ?? ' '}
          </p>
        </div>
      </div>

      <nav aria-label="Main" className="flex-1 overflow-y-auto px-2.5 py-3">
        {NAVIGATION.map((section) => {
          const items = section.items.filter(
            (item) => !item.permission || can(user?.role, item.permission),
          );
          if (items.length === 0) return null;
          return (
            <div key={section.label} className="mb-5 last:mb-0">
              <p className="px-2 pb-1.5 text-2xs font-semibold uppercase tracking-wider text-ink-400">
                {section.label}
              </p>
              <ul className="space-y-px">
                {items.map((item) => (
                  <li key={item.to}>
                    <NavLink
                      to={item.to}
                      end={item.to === '/'}
                      onClick={onNavigate}
                      className={({ isActive }) => cn(
                        'group flex items-center gap-2.5 rounded-md px-2 py-1.5 text-sm transition-colors',
                        isActive
                          ? 'bg-brand-600/90 font-medium text-white'
                          : 'text-ink-300 hover:bg-white/5 hover:text-white',
                      )}
                    >
                      {({ isActive }) => (
                        <>
                          <Icon
                            name={item.icon}
                            className={cn('h-[1.05rem] w-[1.05rem]',
                              isActive ? 'text-white' : 'text-ink-400 group-hover:text-ink-200')}
                          />
                          <span className="truncate">{item.label}</span>
                        </>
                      )}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </div>
          );
        })}
      </nav>

      <div className="border-t border-white/10 px-4 py-3">
        <p className="text-2xs text-ink-400">
          Signed in as <span className="text-ink-300">{humanise(user?.role ?? '')}</span>
        </p>
      </div>
    </div>
  );
}

function UserMenu() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const close = () => setOpen(false);
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
        // Returning focus to the trigger, or the keyboard user is left at the top of the
        // document with no idea where they are.
        containerRef.current?.querySelector('button')?.focus();
      }
    };
    window.addEventListener('click', close);
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('click', close);
      window.removeEventListener('keydown', onKey);
    };
  }, [open]);

  if (!user) return null;

  return (
    <div ref={containerRef} className="relative" onClick={(event) => event.stopPropagation()}>
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        aria-haspopup="menu"
        className="flex items-center gap-2 rounded-md py-1 pl-1 pr-1.5 text-sm transition-colors hover:bg-ink-100 sm:pr-2"
      >
        <Avatar name={user.fullName} />
        <span className="hidden text-left sm:block">
          <span className="block text-sm font-medium leading-tight text-ink-900">
            {user.firstName} {user.lastName}
          </span>
          <span className="block text-2xs leading-tight text-ink-500">
            {humanise(user.role)}
          </span>
        </span>
        <Icon name="chevronDown" className="hidden h-3.5 w-3.5 text-ink-400 sm:block" />
      </button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 z-30 mt-1 w-60 animate-slide-up overflow-hidden rounded-lg border border-ink-200 bg-white py-1 shadow-pop"
        >
          <div className="border-b border-ink-100 px-3 py-2.5">
            <p className="truncate text-sm font-medium text-ink-900">{user.fullName}</p>
            <p className="truncate text-xs text-ink-500">{user.email}</p>
          </div>
          <button
            type="button" role="menuitem"
            onClick={() => { setOpen(false); navigate('/profile'); }}
            className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-ink-700 hover:bg-ink-50"
          >
            <Icon name="user" className="h-4 w-4 text-ink-400" />
            Your profile
          </button>
          <button
            type="button" role="menuitem"
            onClick={async () => { setOpen(false); await logout(); navigate('/login', { replace: true }); }}
            className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-ink-700 hover:bg-ink-50"
          >
            <Icon name="logout" className="h-4 w-4 text-ink-400" />
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
      className="relative rounded-md p-2 text-ink-500 transition-colors hover:bg-ink-100 hover:text-ink-900"
      aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}
    >
      <Icon name="bell" className="h-5 w-5" />
      {unread > 0 && (
        <span className="absolute right-0.5 top-0.5 grid min-w-[1.05rem] place-items-center rounded-full bg-red-600 px-1 text-[10px] font-semibold leading-4 text-white ring-2 ring-white">
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
 * a small thing that makes an application feel unfinished on a phone — and Escape closes
 * it too, since a full-screen overlay with no keyboard exit is a trap.
 */
export function AppShell() {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const location = useLocation();
  const drawerRef = useRef<HTMLDivElement>(null);

  useEffect(() => { setDrawerOpen(false); }, [location.pathname]);

  useEffect(() => {
    if (!drawerOpen) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setDrawerOpen(false);
    };
    window.addEventListener('keydown', onKey);
    drawerRef.current?.querySelector<HTMLElement>('a')?.focus();
    return () => window.removeEventListener('keydown', onKey);
  }, [drawerOpen]);

  return (
    <div className="min-h-screen bg-ink-100 lg:flex">
      <aside className="hidden w-60 shrink-0 lg:block">
        <div className="fixed inset-y-0 w-60"><Sidebar /></div>
      </aside>

      {drawerOpen && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div
            className="absolute inset-0 animate-fade-in bg-ink-950/50"
            onClick={() => setDrawerOpen(false)}
            aria-hidden="true"
          />
          <div
            ref={drawerRef}
            role="dialog"
            aria-modal="true"
            aria-label="Navigation"
            className="absolute inset-y-0 left-0 w-[17rem] shadow-pop"
          >
            <Sidebar onNavigate={() => setDrawerOpen(false)} />
          </div>
        </div>
      )}

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-20 flex h-14 items-center gap-1 border-b border-ink-200 bg-white/95 px-2 backdrop-blur sm:px-4">
          <IconButton
            icon="menu" label="Open navigation" className="lg:hidden"
            onClick={() => setDrawerOpen(true)} aria-expanded={drawerOpen}
          />
          <div className="flex min-w-0 flex-1 items-center gap-2 lg:hidden">
            <Wordmark className="h-6 w-6 text-brand-600" />
            <span className="text-sm font-semibold text-ink-900">JurisCore</span>
          </div>
          <div className="hidden flex-1 lg:block" />
          <NotificationBell />
          <div className="mx-1 hidden h-6 w-px bg-ink-200 sm:block" />
          <UserMenu />
        </header>

        {/* A capped measure: full-width tables on a 27-inch monitor are unreadable, and
            the cap is generous enough that nothing is cramped on a laptop. */}
        <main id="main" className="mx-auto w-full max-w-[86rem] flex-1 px-3 py-5 sm:px-6 sm:py-7">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
