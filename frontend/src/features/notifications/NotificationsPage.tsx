import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { notificationsApi } from './api';
import { keys } from '@/lib/api/queryKeys';
import { useListParams } from '@/lib/api/hooks';
import { useToast } from '@/components/ui/Toast';
import { PageHeader } from '@/components/ui/PageHeader';
import { Badge, Button, Card, CardHeader } from '@/components/ui/primitives';
import type { Tone } from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { Pagination } from '@/components/ui/Pagination';
import { formatDateTime, formatRelative, humanise } from '@/lib/format';
import { messageFor } from '@/lib/api/errors';
import type { AppNotification, NotificationSeverity } from '@/types/api';

const SEVERITY: Record<NotificationSeverity, Tone> = {
  INFO: 'info', SUCCESS: 'success', WARNING: 'warning', CRITICAL: 'danger',
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
            variant="secondary"
            loading={markAllRead.isPending}
            onClick={() => markAllRead.mutate()}
          >
            Mark all read
          </Button>
        )}
      />

      <div className="space-y-4">
        <Card>
          <div className="flex flex-wrap items-center gap-3 border-b border-ink-200 p-3">
            <label className="flex items-center gap-2 text-sm text-ink-700">
              <input
                type="checkbox"
                className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500"
                checked={unreadOnly}
                onChange={(event) => update({ unread: event.target.checked ? 'true' : '' })}
              />
              Unread only
            </label>
          </div>

          <AsyncSection
            isLoading={query.isPending}
            error={query.error}
            data={query.data}
            isEmpty={(data) => data.items.length === 0}
            onRetry={() => query.refetch()}
            skeleton={<TableSkeleton rows={4} columns={2} />}
            empty={(
              <EmptyState
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
                        className={notification.read ? 'bg-white' : 'bg-brand-50/40'}
                      >
                        <div className="flex flex-wrap items-start justify-between gap-3 px-4 py-3">
                          <div className="min-w-0 flex-1">
                            <div className="flex flex-wrap items-center gap-2">
                              <span className="text-sm font-medium text-ink-900">
                                {notification.title}
                              </span>
                              <Badge tone={SEVERITY[notification.severity]}>
                                {humanise(notification.category)}
                              </Badge>
                              {!notification.read && <Badge tone="info">New</Badge>}
                            </div>
                            <p className="mt-1 text-sm text-ink-600">{notification.message}</p>
                            <p className="mt-1 text-xs text-ink-500">
                              <time dateTime={notification.createdAt}>
                                {formatDateTime(notification.createdAt)}
                              </time>
                              <span className="text-ink-400"> · </span>
                              {formatRelative(notification.createdAt)}
                            </p>
                          </div>
                          <div className="flex flex-wrap gap-1">
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
                <li key={key} className="flex items-center justify-between gap-3 px-4 py-3">
                  <div>
                    <p className="text-sm font-medium text-ink-900">{label}</p>
                    <p className="text-xs text-ink-500">{description}</p>
                  </div>
                  <label className="flex items-center gap-2 text-sm">
                    <span className="sr-only">{label}</span>
                    <input
                      type="checkbox"
                      className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500"
                      checked={preferences.data[key]}
                      disabled={setPreference.isPending}
                      onChange={(event) => setPreference.mutate({ [key]: event.target.checked })}
                    />
                  </label>
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
