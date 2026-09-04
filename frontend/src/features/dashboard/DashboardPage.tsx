import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import type { UseQueryResult } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { casesApi } from '@/features/cases/api';
import { hearingsApi, remindersApi } from '@/features/case-management/api';
import { invoicesApi } from '@/features/billing/api';
import { notificationsApi } from '@/features/notifications/api';
import { auditApi } from '@/features/audit/api';
import { keys } from '@/lib/api/queryKeys';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { PageHeader } from '@/components/ui/PageHeader';
import { Badge, Card, CardHeader } from '@/components/ui/primitives';
import { AsyncSection, EmptyState, PanelSkeleton, Skeleton } from '@/components/ui/states';
import { Icon } from '@/components/ui/icons';
import type { IconName } from '@/components/ui/icons';
import {
  CaseStatusBadge, HearingStatusBadge, InvoiceStatusBadge,
} from '@/components/ui/StatusBadge';
import {
  formatDate, formatDateTime, formatMoney, formatRelative, humanise, isPast,
} from '@/lib/format';
import { cn } from '@/lib/cn';

/**
 * What the firm needs to know on opening the application.
 *
 * Every figure here is a count the server reported — `totalItems` on a filtered page —
 * or a row the server returned. Nothing is summed in the browser, and where the API
 * answers no question, the dashboard asks none: there is no "revenue this month" tile,
 * because no aggregate endpoint backs one and a figure assembled from a single page of
 * invoices would be wrong the moment there were two.
 *
 * Tasks and deadlines are addressed the same way. They are queried per matter, so a
 * firm-wide list of them does not exist; what does exist firm-wide is the reminder feed
 * set against them, and that is what the panel shows, described as what it is.
 */
export function DashboardPage() {
  const { user } = useAuth();
  const now = new Date();
  const weekAhead = new Date(now.getTime() + 7 * 24 * 60 * 60 * 1000);

  const mayBill = can(user?.role, 'viewBilling');
  const mayAudit = can(user?.role, 'viewAudit');

  const openCases = useQuery({
    queryKey: keys.cases.list({ dashboard: 'open' }),
    queryFn: () => casesApi.list({ status: 'OPEN', size: 1 }),
  });

  const activeCases = useQuery({
    queryKey: keys.cases.list({ dashboard: 'in-progress' }),
    queryFn: () => casesApi.list({ status: 'IN_PROGRESS', size: 5 }),
  });

  const upcoming = useQuery({
    queryKey: keys.hearings.list({ dashboard: 'week' }),
    queryFn: () => hearingsApi.list({
      status: 'SCHEDULED',
      from: now.toISOString(),
      to: weekAhead.toISOString(),
      size: 5,
    }),
  });

  const reminders = useQuery({
    queryKey: keys.reminders.list({ dashboard: true }),
    queryFn: () => remindersApi.list({ status: 'SCHEDULED', size: 5 }),
  });

  const overdue = useQuery({
    queryKey: keys.invoices.list({ dashboard: 'overdue' }),
    queryFn: () => invoicesApi.list({ status: 'OVERDUE', size: 5 }),
    enabled: mayBill,
  });

  const notifications = useQuery({
    queryKey: keys.notifications.list({ dashboard: true }),
    queryFn: () => notificationsApi.list({ size: 5 }),
  });

  const activity = useQuery({
    queryKey: keys.audit.list({ dashboard: true }),
    queryFn: () => auditApi.list({ size: 8 }),
    enabled: mayAudit,
  });

  return (
    <>
      <PageHeader
        title={user ? `Good day, ${user.firstName}` : 'Dashboard'}
        description={(
          <span className="flex items-center gap-1.5 text-ink-500">
            <Icon name="calendar" className="h-3.5 w-3.5" />
            {formatDate(now.toISOString())}
          </span>
        )}
      />

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <StatTile
          label="Open matters" icon="cases" query={openCases}
          to="/cases?status=OPEN"
        />
        <StatTile
          label="In progress" icon="cases" query={activeCases}
          to="/cases?status=IN_PROGRESS"
        />
        <StatTile
          label="Hearings this week" icon="hearings" query={upcoming}
          to="/hearings"
        />
        {mayBill ? (
          <StatTile
            label="Overdue invoices" icon="money" query={overdue} to="/invoices?status=OVERDUE"
            alertWhenPositive
          />
        ) : (
          <StatTile
            label="Reminders" icon="clock" query={reminders} to="/cases"
          />
        )}
      </div>

      <div className="mt-4 grid items-start gap-4 xl:grid-cols-2">
        <Panel
          title="Listed in the next seven days"
          description="Hearings scheduled across the firm's matters."
          icon="hearings"
          to="/hearings" toLabel="Open the diary"
        >
          <AsyncSection
            isLoading={upcoming.isPending}
            error={upcoming.error}
            data={upcoming.data}
            isEmpty={(data) => data.items.length === 0}
            onRetry={() => upcoming.refetch()}
            skeleton={<PanelSkeleton lines={3} />}
            empty={(
              <EmptyState compact icon="calendar" title="Nothing listed"
                description="No hearing falls in the next seven days." />
            )}
          >
            {(data) => (
              <ul className="divide-y divide-ink-100">
                {data.items.map((hearing) => (
                  <li key={hearing.id}>
                    <Link
                      to={`/cases/${hearing.caseId}?tab=hearings`}
                      className="flex items-center justify-between gap-3 px-4 py-2.5 transition-colors hover:bg-brand-50/40"
                    >
                      <span className="min-w-0">
                        <span className="block truncate text-sm font-medium text-ink-900">
                          {humanise(hearing.hearingType)} hearing
                        </span>
                        <span className="mt-0.5 block text-xs text-ink-500">
                          {formatDateTime(hearing.scheduledAt)}
                          <span className="text-ink-300"> · </span>
                          {formatRelative(hearing.scheduledAt)}
                        </span>
                      </span>
                      <HearingStatusBadge status={hearing.status} />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </AsyncSection>
        </Panel>

        <Panel
          title="Matters in progress"
          description="Work the firm has open right now."
          icon="cases"
          to="/cases" toLabel="All matters"
        >
          <AsyncSection
            isLoading={activeCases.isPending}
            error={activeCases.error}
            data={activeCases.data}
            isEmpty={(data) => data.items.length === 0}
            onRetry={() => activeCases.refetch()}
            skeleton={<PanelSkeleton lines={3} />}
            empty={(
              <EmptyState compact icon="cases" title="Nothing in progress"
                description="No matter is currently being worked on." />
            )}
          >
            {(data) => (
              <ul className="divide-y divide-ink-100">
                {data.items.map((legalCase) => (
                  <li key={legalCase.id}>
                    <Link
                      to={`/cases/${legalCase.id}`}
                      className="flex items-center justify-between gap-3 px-4 py-2.5 transition-colors hover:bg-brand-50/40"
                    >
                      <span className="min-w-0">
                        <span className="block truncate text-sm font-medium text-ink-900">
                          {legalCase.title}
                        </span>
                        <span className="mt-0.5 block text-xs text-ink-500">
                          <span className="font-mono">{legalCase.caseNumber}</span>
                          <span className="text-ink-300"> · </span>
                          opened {formatDate(legalCase.openedAt)}
                        </span>
                      </span>
                      <CaseStatusBadge status={legalCase.status} />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </AsyncSection>
        </Panel>

        <Panel
          title="Reminders"
          description="Set against tasks and deadlines across the firm's matters."
          icon="clock"
        >
          <AsyncSection
            isLoading={reminders.isPending}
            error={reminders.error}
            data={reminders.data}
            isEmpty={(data) => data.items.length === 0}
            onRetry={() => reminders.refetch()}
            skeleton={<PanelSkeleton lines={3} />}
            empty={(
              <EmptyState compact icon="clock" title="No reminders scheduled"
                description="Reminders are set on a task or a deadline from its matter." />
            )}
          >
            {(data) => (
              <ul className="divide-y divide-ink-100">
                {data.items.map((reminder) => (
                  <li key={reminder.id} className="flex items-center justify-between gap-3 px-4 py-2.5">
                    <span className="min-w-0">
                      <span className="block truncate text-sm text-ink-900">
                        {reminder.note ?? (reminder.taskId ? 'Task reminder' : 'Deadline reminder')}
                      </span>
                      <span className="mt-0.5 block text-xs text-ink-500">
                        {formatDateTime(reminder.remindAt)}
                        <span className="text-ink-300"> · </span>
                        {formatRelative(reminder.remindAt)}
                      </span>
                    </span>
                    <Badge tone={isPast(reminder.remindAt) ? 'warning' : 'neutral'} dot>
                      {reminder.taskId ? 'Task' : 'Deadline'}
                    </Badge>
                  </li>
                ))}
              </ul>
            )}
          </AsyncSection>
        </Panel>

        {mayBill && (
          <Panel
            title="Overdue invoices"
            description="Issued, past their due date and not settled."
            icon="money"
            to="/invoices?status=OVERDUE" toLabel="All invoices"
          >
            <AsyncSection
              isLoading={overdue.isPending}
              error={overdue.error}
              data={overdue.data}
              isEmpty={(data) => data.items.length === 0}
              onRetry={() => overdue.refetch()}
              skeleton={<PanelSkeleton lines={3} />}
              empty={(
                <EmptyState compact icon="check" title="Nothing overdue"
                  description="Every issued invoice is within its terms." />
              )}
            >
              {(data) => (
                <ul className="divide-y divide-ink-100">
                  {data.items.map((invoice) => (
                    <li key={invoice.id}>
                      <Link
                        to={`/invoices/${invoice.id}`}
                        className="flex items-center justify-between gap-3 px-4 py-2.5 transition-colors hover:bg-brand-50/40"
                      >
                        <span className="min-w-0">
                          <span className="block font-mono text-sm font-medium text-ink-900">
                            {invoice.invoiceNumber}
                          </span>
                          <span className="mt-0.5 block text-xs text-ink-500">
                            Due {formatDate(invoice.dueDate)}
                          </span>
                        </span>
                        <span className="flex shrink-0 items-center gap-2">
                          <span className="text-sm font-medium tabular-nums text-ink-900">
                            {formatMoney(invoice.amountDue, invoice.currency)}
                          </span>
                          <InvoiceStatusBadge status={invoice.status} />
                        </span>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </AsyncSection>
          </Panel>
        )}

        <Panel
          title="Your notifications"
          description="The latest, whether or not you have read them."
          icon="bell"
          to="/notifications" toLabel="All notifications"
        >
          <AsyncSection
            isLoading={notifications.isPending}
            error={notifications.error}
            data={notifications.data}
            isEmpty={(data) => data.items.length === 0}
            onRetry={() => notifications.refetch()}
            skeleton={<PanelSkeleton lines={3} />}
            empty={(
              <EmptyState compact icon="bell" title="Nothing yet"
                description="Notifications appear as invoices, payments and assignments happen." />
            )}
          >
            {(data) => (
              <ul className="divide-y divide-ink-100">
                {data.items.map((notification) => (
                  <li
                    key={notification.id}
                    className={cn('px-4 py-2.5', !notification.read && 'bg-brand-50/40')}
                  >
                    <div className="flex items-start gap-2">
                      {!notification.read && (
                        <span aria-hidden="true"
                          className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-brand-500" />
                      )}
                      <div className="min-w-0">
                        <p className="truncate text-sm font-medium text-ink-900">
                          {notification.title}
                        </p>
                        <p className="mt-0.5 text-xs text-ink-500">
                          {formatRelative(notification.createdAt)}
                        </p>
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </AsyncSection>
        </Panel>

        {mayAudit && (
          <Panel
            title="Recent activity"
            description="From the firm's audit trail."
            icon="audit"
            to="/audit" toLabel="Full trail"
          >
            <AsyncSection
              isLoading={activity.isPending}
              error={activity.error}
              data={activity.data}
              isEmpty={(data) => data.items.length === 0}
              onRetry={() => activity.refetch()}
              skeleton={<PanelSkeleton lines={4} />}
              empty={(
                <EmptyState compact icon="audit" title="No activity recorded"
                  description="The trail fills as people work in the application." />
              )}
            >
              {(data) => (
                <ol className="divide-y divide-ink-100">
                  {data.items.map((event) => (
                    <li key={event.id} className="flex items-start gap-3 px-4 py-2.5">
                      <span aria-hidden="true"
                        className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-ink-300" />
                      <div className="min-w-0">
                        <p className="truncate text-sm text-ink-800">{event.summary}</p>
                        <p className="mt-0.5 text-xs text-ink-500">
                          {formatRelative(event.occurredAt)}
                          <span className="text-ink-300"> · </span>
                          {event.entityType}
                        </p>
                      </div>
                    </li>
                  ))}
                </ol>
              )}
            </AsyncSection>
          </Panel>
        )}
      </div>
    </>
  );
}

// ------------------------------------------------------------------ pieces

/**
 * A count the server reported, and a link to the list it came from.
 *
 * The tile shows a skeleton rather than a zero while it loads: a "0" that turns into a
 * "14" a moment later is worse than a placeholder, because somebody reads the zero.
 */
function StatTile({ label, icon, query, to, alertWhenPositive = false }: {
  label: string;
  icon: IconName;
  query: UseQueryResult<{ totalItems: number }>;
  to: string;
  alertWhenPositive?: boolean;
}) {
  const value = query.data?.totalItems;
  const alert = alertWhenPositive && value !== undefined && value > 0;

  return (
    <Link
      to={to}
      className={cn(
        'group flex items-start justify-between gap-3 rounded-lg border bg-white p-3.5 shadow-card transition-colors',
        alert ? 'border-red-200 hover:border-red-300' : 'border-ink-200 hover:border-ink-300',
      )}
    >
      <span className="min-w-0">
        <span className="block text-2xs font-semibold uppercase tracking-wide text-ink-500">
          {label}
        </span>
        {query.isPending ? (
          <Skeleton className="mt-2 h-7 w-10" />
        ) : (
          <span className={cn(
            'mt-1 block text-2xl font-semibold tabular-nums',
            alert ? 'text-red-700' : 'text-ink-900',
          )}>
            {query.error ? '—' : value ?? '—'}
          </span>
        )}
      </span>
      <span className={cn(
        'grid h-8 w-8 shrink-0 place-items-center rounded-md',
        alert ? 'bg-red-50 text-red-600' : 'bg-ink-100 text-ink-500',
      )}>
        <Icon name={icon} className="h-4 w-4" />
      </span>
    </Link>
  );
}

function Panel({ title, description, icon, to, toLabel, children }: {
  title: string;
  description: string;
  icon: IconName;
  to?: string;
  toLabel?: string;
  children: ReactNode;
}) {
  return (
    <Card>
      <CardHeader
        icon={icon}
        title={title}
        description={description}
        actions={to && (
          <Link
            to={to}
            className="inline-flex items-center gap-1 rounded text-xs font-medium text-brand-700 hover:underline"
          >
            {toLabel}
            <Icon name="chevronRight" className="h-3 w-3" />
          </Link>
        )}
      />
      {children}
    </Card>
  );
}
