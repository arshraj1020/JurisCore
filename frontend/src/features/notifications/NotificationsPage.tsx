import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { notificationsApi } from './api';
import { keys } from '@/lib/api/queryKeys';
import { useListParams } from '@/lib/api/hooks';
import { useToast } from '@/components/ui/Toast';
import { PageHeader } from '@/components/ui/PageHeader';
import { Badge, Button, Card, CardHeader, Toggle, Toolbar } from '@/components/ui/primitives';
import { Icon } from '@/components/ui/icons';
import type { Tone } from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { Pagination } from '@/components/ui/Pagination';
import { formatDateTime, formatRelative, humanise } from '@/lib/format';
import { messageFor } from '@/lib/api/errors';
import type {
  AppNotification, NotificationCategory, NotificationSeverity,
} from '@/types/api';
import type { IconName } from '@/components/ui/icons';
import { cn } from '@/lib/cn';

const SEVERITY: Record<NotificationSeverity, Tone> = {
  INFO: 'info', SUCCESS: 'success', WARNING: 'warning', CRITICAL: 'danger',
};

const SEVERITY_SURFACE: Record<NotificationSeverity, string> = {
  INFO: 'bg-brand-50 text-brand-600',
  SUCCESS: 'bg-emerald-50 text-emerald-600',
  WARNING: 'bg-amber-50 text-amber-600',
  CRITICAL: 'bg-red-50 text-red-600',
};

const CATEGORY_ICON: Record<NotificationCategory, IconName> = {
  INVOICE: 'invoices', PAYMENT: 'money', CASE: 'cases', SYSTEM: 'info',
};

/**
 * A notification carries an `actionPath` the backend has already constrained to a
 * relative path. It is checked again here before being navigated to: a value that reaches
 * the router unchecked is how `javascript:` and `//evil.example` end up as destinations,
 * and the cost of re-checking is one comparison.
 */
function safeInAppPath(path: string | null | undefined): string | null {
  if (!path) return null;
  if (!path.startsWith('/') || path.startsWith('//')) return null;
  return path;
}

export function NotificationsPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const queryClient = useQueryClient();
  const { params, page, update, setPage } = useListParams({ unread: '' });
  const unreadOnly = params.unread === 'true';

  const query = useQuery({
    queryKey: keys.notifications.list({ unread: unreadOnly, page }),
    queryFn: () => notificationsApi.list({ unread: unreadOnly || undefined, page }),
  });

  const preferences = useQuery({
    queryKey: keys.notifications.preferences,
    queryFn: () => notificationsApi.preferences(),
  });

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: keys.notifications.all });
  };

  const markRead = useMutation({
    mutationFn: (id: string) => notificationsApi.markRead(id),
    onSuccess: refresh,
    onError: (error) => toast.error(messageFor(error)),
  });

  const markAllRead = useMutation({
    mutationFn: () => notificationsApi.markAllRead(),
    onSuccess: async (result) => {
      await refresh();
      toast.success(result.marked === 0
        ? 'Nothing was unread'
        : `${result.marked} marked as read`);
    },
    onError: (error) => toast.error(messageFor(error)),
  });

  const remove = useMutation({
    mutationFn: (id: string) => notificationsApi.remove(id),
    onSuccess: refresh,
    onError: (error) => toast.error(messageFor(error)),
  });

  const setPreference = useMutation({
    mutationFn: (patch: Partial<Record<'invoice' | 'payment' | 'caseUpdates' | 'system', boolean>>) =>
      notificationsApi.updatePreferences(patch),
    onSuccess: async (updated) => {
      queryClient.setQueryData(keys.notifications.preferences, updated);
      toast.success('Preferences saved');
    },
    onError: (error) => toast.error(messageFor(error)),
  });

  const open = (notification: AppNotification) => {
    if (!notification.read) markRead.mutate(notification.id);
    const path = safeInAppPath(notification.actionPath);
    if (path) navigate(path);
  };

  return (
    <>
      <PageHeader
        title="Notifications"
        description="What has happened while you were elsewhere."
        actions={(
          <Button
            variant="secondary" icon="check"
            loading={markAllRead.isPending}
            onClick={() => markAllRead.mutate()}
          >
            Mark all read
          </Button>
        )}
      />

      <div className="space-y-4">
        <Card>
          <Toolbar className="items-center">
            <label className="flex cursor-pointer items-center gap-2 py-1.5 text-sm text-ink-700">
              <input
                type="checkbox"
                className="h-4 w-4 cursor-pointer rounded border-ink-300 text-brand-600 focus:ring-brand-500"
                checked={unreadOnly}
                onChange={(event) => update({ unread: event.target.checked ? 'true' : '' })}
              />
              Unread only
            </label>
          </Toolbar>

          <AsyncSection
            isLoading={query.isPending}
            error={query.error}
            data={query.data}
            isEmpty={(data) => data.items.length === 0}
            onRetry={() => query.refetch()}
            skeleton={<TableSkeleton rows={4} columns={2} />}
            empty={(
              <EmptyState
                icon={unreadOnly ? 'check' : 'bell'}
                title={unreadOnly ? 'Nothing unread' : 'No notifications'}
                description={unreadOnly
                  ? 'You are up to date.'
                  : 'Notifications appear here as invoices, payments and assignments happen.'}
              />
            )}
          >
            {(data) => (
              <>
                <ul className="divide-y divide-ink-100">
                  {data.items.map((notification) => {
                    const path = safeInAppPath(notification.actionPath);
                    return (
                      <li
                        key={notification.id}
                        className={cn(
                          'transition-colors',
                          notification.read ? 'bg-white' : 'bg-brand-50/50',
                        )}
                      >
                        <div className="flex flex-wrap items-start gap-3 px-4 py-3">
                          {/* An unread marker as well as the tint: a background wash alone
                              is easy to miss and invisible in high-contrast modes. */}
                          <span className={cn(
                            'mt-0.5 grid h-8 w-8 shrink-0 place-items-center rounded-md',
                            SEVERITY_SURFACE[notification.severity],
                          )}>
                            <Icon name={CATEGORY_ICON[notification.category]} className="h-4 w-4" />
                          </span>

                          <div className="min-w-0 basis-full sm:flex-1 sm:basis-0">
                            <div className="flex flex-wrap items-center gap-2">
                              <span className="text-sm font-medium text-ink-900">
                                {notification.title}
                              </span>
                              {!notification.read && <Badge tone="info" dot>New</Badge>}
                            </div>
                            <p className="mt-1 text-sm text-ink-600">{notification.message}</p>
                            <p className="mt-1 flex flex-wrap items-center gap-x-2 text-xs text-ink-500">
                              <Badge tone={SEVERITY[notification.severity]}>
                                {humanise(notification.category)}
                              </Badge>
                              <time dateTime={notification.createdAt}>
                                {formatDateTime(notification.createdAt)}
                              </time>
                              <span className="text-ink-300">·</span>
                              {formatRelative(notification.createdAt)}
                            </p>
                          </div>

                          <div className="flex w-full flex-wrap gap-1 sm:w-auto sm:justify-end">
                            {path && (
                              <Button size="sm" variant="secondary"
                                onClick={() => open(notification)}>
                                Open
                              </Button>
                            )}
                            {!notification.read && (
                              <Button
                                size="sm" variant="ghost"
                                disabled={markRead.isPending}
                                onClick={() => markRead.mutate(notification.id)}
                              >
                                Mark read
                              </Button>
                            )}
                            <Button
                              size="sm" variant="ghost"
                              disabled={remove.isPending}
                              onClick={() => remove.mutate(notification.id)}
                            >
                              Dismiss
                            </Button>
                          </div>
                        </div>
                      </li>
                    );
                  })}
                </ul>
                <Pagination page={data} onPageChange={setPage} label="notifications" />
              </>
            )}
          </AsyncSection>
        </Card>

        <Card>
          <CardHeader
            icon="settings"
            title="What you are notified about"
            description="Turning a category off stops new notifications in it; it does not delete past ones."
          />
          {preferences.data ? (
            <ul className="divide-y divide-ink-100">
              {([
                ['invoice', 'Invoices', 'Issued, cancelled and overdue invoices.'],
                ['payment', 'Payments', 'Money recorded against an invoice.'],
                ['caseUpdates', 'Matters', 'Being assigned to a matter.'],
                ['system', 'System', 'Announcements and account messages.'],
              ] as const).map(([key, label, description]) => (
                <li key={key} className="px-4 py-3">
                  <Toggle
                    label={label}
                    description={description}
                    checked={preferences.data[key]}
                    disabled={setPreference.isPending}
                    onChange={(checked) => setPreference.mutate({ [key]: checked })}
                  />
                </li>
              ))}
            </ul>
          ) : (
            <TableSkeleton rows={4} columns={2} />
          )}
        </Card>
      </div>
    </>
  );
}
