import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { casesApi } from '@/features/cases/api';
import { hearingsApi } from '@/features/case-management/api';
import { invoicesApi } from '@/features/billing/api';
import { keys } from '@/lib/api/queryKeys';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { PageHeader } from '@/components/ui/PageHeader';
import { Card, CardHeader } from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { CaseStatusBadge, HearingStatusBadge, InvoiceStatusBadge } from '@/components/ui/StatusBadge';
import { formatDate, formatDateTime, formatMoney, formatRelative, humanise } from '@/lib/format';

/**
 * What the firm needs to know on opening the application.
 *
 * Every figure shown here is a count the server reported (`totalItems` on a filtered
 * page), not something summed in the browser. Where no endpoint answers a question, the
 * dashboard does not invent an answer — there is no "revenue this month" tile, because
 * the API has no aggregate to back one and a number assembled from one page of invoices
 * would be wrong the moment there were more than a page.
 */
export function DashboardPage() {
  const { user } = useAuth();
  const now = new Date();
  const fromIso = now.toISOString();
  const inSevenDays = new Date(now.getTime() + 7 * 24 * 60 * 60 * 1000).toISOString();

  const upcoming = useQuery({
    queryKey: keys.hearings.list({ dashboard: true }),
    queryFn: () => hearingsApi.list({
      status: 'SCHEDULED', from: fromIso, to: inSevenDays, size: 5,
    }),
  });

  const activeCases = useQuery({
    queryKey: keys.cases.list({ dashboard: 'recent' }),
    queryFn: () => casesApi.list({ status: 'IN_PROGRESS', size: 5 }),
  });

  const openCases = useQuery({
    queryKey: keys.cases.list({ dashboard: 'open-count' }),
    queryFn: () => casesApi.list({ status: 'OPEN', size: 1 }),
  });

  const mayBill = can(user?.role, 'viewBilling');
  const overdue = useQuery({
    queryKey: keys.invoices.list({ dashboard: 'overdue' }),
    queryFn: () => invoicesApi.list({ status: 'OVERDUE', size: 5 }),
    enabled: mayBill,
  });

  const greeting = user ? `Good day, ${user.firstName}` : 'Dashboard';

  return (
    <>
      <PageHeader title={greeting} description="Where the firm's work stands today." />

      <div className="grid gap-4 sm:grid-cols-3">
        <StatTile
          label="Matters awaiting work"
          value={openCases.data?.totalItems}
          to="/cases?status=OPEN"
        />
        <StatTile
          label="Matters in progress"
          value={activeCases.data?.totalItems}
          to="/cases?status=IN_PROGRESS"
        />
        {mayBill && (
          <StatTile
            label="Invoices overdue"
            value={overdue.data?.totalItems}
            to="/invoices?status=OVERDUE"
            tone={overdue.data && overdue.data.totalItems > 0 ? 'alert' : 'plain'}
          />
        )}
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader
            title="Listed in the next seven days"
            description="Hearings scheduled across the firm."
            actions={<Link to="/hearings" className="text-sm text-brand-700 hover:underline">Diary</Link>}
          />
          <AsyncSection
            isLoading={upcoming.isPending}
            error={upcoming.error}
            data={upcoming.data}
            isEmpty={(data) => data.items.length === 0}
            onRetry={() => upcoming.refetch()}
            skeleton={<TableSkeleton rows={3} columns={2} />}
            empty={<EmptyState title="Nothing listed" description="No hearing falls in the next week." />}
          >
            {(data) => (
              <ul className="divide-y divide-ink-100">
                {data.items.map((hearing) => (
                  <li key={hearing.id} className="px-4 py-3">
                    <Link
                      to={`/cases/${hearing.caseId}?tab=hearings`}
                      className="flex flex-wrap items-baseline justify-between gap-2"
                    >
                      <span>
                        <span className="block text-sm font-medium text-ink-900">
                          {formatDateTime(hearing.scheduledAt)}
                        </span>
                        <span className="block text-xs text-ink-500">
                          {humanise(hearing.hearingType)} · {formatRelative(hearing.scheduledAt)}
                        </span>
                      </span>
                      <HearingStatusBadge status={hearing.status} />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </AsyncSection>
        </Card>

        <Card>
          <CardHeader
            title="Matters in progress"
            actions={<Link to="/cases" className="text-sm text-brand-700 hover:underline">All matters</Link>}
          />
          <AsyncSection
            isLoading={activeCases.isPending}
            error={activeCases.error}
            data={activeCases.data}
            isEmpty={(data) => data.items.length === 0}
            onRetry={() => activeCases.refetch()}
            skeleton={<TableSkeleton rows={3} columns={2} />}
            empty={<EmptyState title="Nothing in progress" description="No matter is currently being worked on." />}
          >
            {(data) => (
              <ul className="divide-y divide-ink-100">
                {data.items.map((legalCase) => (
                  <li key={legalCase.id} className="px-4 py-3">
                    <Link
                      to={`/cases/${legalCase.id}`}
                      className="flex flex-wrap items-baseline justify-between gap-2"
                    >
                      <span className="min-w-0">
                        <span className="block truncate text-sm font-medium text-ink-900">
                          {legalCase.title}
                        </span>
                        <span className="block text-xs text-ink-500">
                          {legalCase.caseNumber} · opened {formatDate(legalCase.openedAt)}
                        </span>
                      </span>
                      <CaseStatusBadge status={legalCase.status} />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </AsyncSection>
        </Card>

        {mayBill && (
          <Card className="lg:col-span-2">
            <CardHeader
              title="Overdue invoices"
              description="Issued, past their due date and not settled."
              actions={<Link to="/invoices" className="text-sm text-brand-700 hover:underline">All invoices</Link>}
            />
            <AsyncSection
              isLoading={overdue.isPending}
              error={overdue.error}
              data={overdue.data}
              isEmpty={(data) => data.items.length === 0}
              onRetry={() => overdue.refetch()}
              skeleton={<TableSkeleton rows={3} columns={3} />}
              empty={<EmptyState title="Nothing overdue" description="Every issued invoice is within its terms." />}
            >
              {(data) => (
                <ul className="divide-y divide-ink-100">
                  {data.items.map((invoice) => (
                    <li key={invoice.id} className="px-4 py-3">
                      <Link
                        to={`/invoices/${invoice.id}`}
                        className="flex flex-wrap items-baseline justify-between gap-2"
                      >
                        <span>
                          <span className="block text-sm font-medium text-ink-900">
                            {invoice.invoiceNumber}
                          </span>
                          <span className="block text-xs text-ink-500">
                            Due {formatDate(invoice.dueDate)}
                          </span>
                        </span>
                        <span className="flex items-center gap-2">
                          <span className="tabular-nums text-sm text-ink-900">
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
          </Card>
        )}
      </div>
    </>
  );
}

function StatTile({ label, value, to, tone = 'plain' }: {
  label: string; value: number | undefined; to: string; tone?: 'plain' | 'alert';
}) {
  return (
    <Link
      to={to}
      className="block rounded-lg border border-ink-200 bg-white p-4 transition hover:border-ink-300 hover:shadow-sm"
    >
      <p className="text-xs uppercase tracking-wide text-ink-500">{label}</p>
      <p className={`mt-1 text-2xl font-semibold tabular-nums ${tone === 'alert' ? 'text-red-700' : 'text-ink-900'}`}>
        {value === undefined ? '—' : value}
      </p>
    </Link>
  );
}
