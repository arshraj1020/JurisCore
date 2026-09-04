import { useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { casesApi } from './api';
import { clientsApi } from '@/features/clients/api';
import { keys } from '@/lib/api/queryKeys';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { nextCaseStatuses } from '@/lib/lifecycle';
import { useToast } from '@/components/ui/Toast';
import { PageHeader } from '@/components/ui/PageHeader';
import { Avatar, Button, Card } from '@/components/ui/primitives';
import { ErrorState, TableSkeleton } from '@/components/ui/states';
import { CaseStatusBadge } from '@/components/ui/StatusBadge';
import { ConfirmDialog } from '@/components/ui/Dialog';
import { Tabs, TabPanel } from '@/components/ui/Tabs';
import { Icon } from '@/components/ui/icons';
import { formatDate, humanise } from '@/lib/format';
import { messageFor } from '@/lib/api/errors';
import { usersApi } from '@/features/auth/api';
import { CaseOverviewTab } from './tabs/CaseOverviewTab';
import { CaseTimelineTab } from './tabs/CaseTimelineTab';
import { CaseTasksTab } from './tabs/CaseTasksTab';
import { CaseDeadlinesTab } from './tabs/CaseDeadlinesTab';
import { CaseHearingsTab } from './tabs/CaseHearingsTab';
import { CaseDocumentsTab } from '@/features/documents/CaseDocumentsTab';
import { CaseInvoicesTab } from '@/features/billing/CaseInvoicesTab';
import type { CaseStatus } from '@/types/api';

const TABS = [
  { id: 'overview', label: 'Overview', icon: 'info' },
  { id: 'timeline', label: 'Timeline', icon: 'clock' },
  { id: 'hearings', label: 'Hearings', icon: 'hearings' },
  { id: 'tasks', label: 'Tasks', icon: 'check' },
  { id: 'deadlines', label: 'Deadlines', icon: 'calendar' },
  { id: 'documents', label: 'Documents', icon: 'document' },
  { id: 'invoices', label: 'Invoices', icon: 'invoices' },
] as const;

type TabId = (typeof TABS)[number]['id'];

/**
 * The matter workspace.
 *
 * Everything about a matter lives here behind tabs rather than under seven separate
 * routes, because a lawyer working a case moves between its hearings, its tasks and its
 * documents constantly — and each of those as its own page would mean losing the matter's
 * context on every hop. The tab is in the URL, so a particular view is still linkable.
 */
export function CaseDetailPage() {
  const { caseId = '' } = useParams();
  const { user } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const [pendingStatus, setPendingStatus] = useState<CaseStatus | null>(null);

  const requested = searchParams.get('tab');
  const tab: TabId = TABS.some((candidate) => candidate.id === requested)
    ? (requested as TabId)
    : 'overview';

  const query = useQuery({
    queryKey: keys.cases.detail(caseId),
    queryFn: () => casesApi.byId(caseId),
    enabled: !!caseId,
  });

  const client = useQuery({
    queryKey: keys.clients.detail(query.data?.clientId ?? ''),
    queryFn: () => clientsApi.byId(query.data?.clientId ?? ''),
    enabled: !!query.data?.clientId,
  });

  // The header answers "who is on this?" without making anyone open a tab for it.
  const assignments = useQuery({
    queryKey: keys.cases.assignments(caseId),
    queryFn: () => casesApi.assignments(caseId),
    enabled: !!caseId,
  });

  const members = useQuery({
    queryKey: keys.users.list({ all: true }),
    queryFn: () => usersApi.list({ size: 200 }),
    enabled: (assignments.data?.length ?? 0) > 0,
  });

  const changeStatus = useMutation({
    mutationFn: (status: CaseStatus) => casesApi.changeStatus(caseId, status),
    onSuccess: async (updated) => {
      await queryClient.invalidateQueries({ queryKey: keys.cases.all });
      setPendingStatus(null);
      toast.success(`Matter moved to ${humanise(updated.status)}`);
    },
    onError: (error) => {
      setPendingStatus(null);
      toast.error(messageFor(error));
    },
  });

  if (query.isPending) return <Card><TableSkeleton rows={6} /></Card>;
  if (query.error || !query.data) {
    return <Card><ErrorState error={query.error} onRetry={() => query.refetch()} /></Card>;
  }

  const legalCase = query.data;
  // Only transitions the backend's CaseStatusPolicy actually permits are offered.
  const available = can(user?.role, 'changeCaseStatus') ? nextCaseStatuses(legalCase.status) : [];
  const nameOf = (userId: string) =>
    members.data?.items.find((member) => member.id === userId)?.fullName ?? 'Assigned lawyer';
  const lead = assignments.data?.find((assignment) => assignment.lead);
  const team = assignments.data ?? [];

  const setTab = (next: TabId) => {
    const merged = new URLSearchParams(searchParams);
    if (next === 'overview') merged.delete('tab');
    else merged.set('tab', next);
    setSearchParams(merged, { replace: true });
  };

  return (
    <>
      <PageHeader
        breadcrumbs={[{ label: 'Matters', to: '/cases' }, { label: legalCase.caseNumber }]}
        title={legalCase.title}
        meta={<CaseStatusBadge status={legalCase.status} />}
        actions={available.map((status) => (
          <Button
            key={status}
            variant={status === 'CLOSED' ? 'secondary' : 'primary'}
            size="sm"
            onClick={() => setPendingStatus(status)}
            disabled={changeStatus.isPending}
          >
            {status === 'CLOSED' ? 'Close matter' : `Move to ${humanise(status)}`}
          </Button>
        ))}
      />

      {/* The facts somebody needs before opening any tab: whose matter it is, who is on
          it, and how long it has been running. */}
      <div className="mb-4 flex flex-wrap items-center gap-x-6 gap-y-3 rounded-lg border border-ink-200 bg-white px-4 py-3 shadow-card">
        <div className="min-w-0">
          <p className="text-2xs font-semibold uppercase tracking-wide text-ink-500">Matter</p>
          <p className="mt-0.5 font-mono text-sm text-ink-900">{legalCase.caseNumber}</p>
        </div>

        <div className="min-w-0">
          <p className="text-2xs font-semibold uppercase tracking-wide text-ink-500">Client</p>
          <p className="mt-0.5 truncate text-sm">
            {client.data ? (
              <Link to={`/clients/${legalCase.clientId}`}
                className="font-medium text-brand-700 hover:underline">
                {client.data.displayName}
              </Link>
            ) : <span className="text-ink-500">—</span>}
          </p>
        </div>

        <div className="min-w-0">
          <p className="text-2xs font-semibold uppercase tracking-wide text-ink-500">Lead lawyer</p>
          <p className="mt-0.5 truncate text-sm text-ink-900">
            {lead ? nameOf(lead.lawyerUserId) : <span className="text-ink-500">Not named</span>}
          </p>
        </div>

        <div className="min-w-0">
          <p className="text-2xs font-semibold uppercase tracking-wide text-ink-500">Assigned</p>
          <div className="mt-1 flex items-center gap-1.5">
            {team.length === 0 ? (
              <span className="text-sm text-ink-500">Nobody</span>
            ) : (
              <>
                <span className="flex -space-x-1">
                  {team.slice(0, 4).map((assignment) => (
                    <Avatar
                      key={assignment.id} size="sm" name={nameOf(assignment.lawyerUserId)}
                      className="ring-2 ring-white"
                    />
                  ))}
                </span>
                <span className="text-sm text-ink-700">
                  {team.length} {team.length === 1 ? 'lawyer' : 'lawyers'}
                </span>
              </>
            )}
          </div>
        </div>

        <div className="min-w-0">
          <p className="text-2xs font-semibold uppercase tracking-wide text-ink-500">Opened</p>
          <p className="mt-0.5 flex items-center gap-1.5 text-sm text-ink-900">
            <Icon name="calendar" className="h-3.5 w-3.5 text-ink-400" />
            {formatDate(legalCase.openedAt)}
          </p>
        </div>

        {legalCase.closedAt && (
          <div className="min-w-0">
            <p className="text-2xs font-semibold uppercase tracking-wide text-ink-500">Closed</p>
            <p className="mt-0.5 text-sm text-ink-900">{formatDate(legalCase.closedAt)}</p>
          </div>
        )}
      </div>

      <Tabs tabs={TABS} active={tab} onChange={setTab} panelId="matter-panel" />

      <TabPanel id="matter-panel" labelledBy={`tab-${tab}`}>
        {tab === 'overview' && <CaseOverviewTab legalCase={legalCase} />}
        {tab === 'timeline' && <CaseTimelineTab caseId={caseId} />}
        {tab === 'hearings' && <CaseHearingsTab caseId={caseId} />}
        {tab === 'tasks' && <CaseTasksTab caseId={caseId} />}
        {tab === 'deadlines' && <CaseDeadlinesTab caseId={caseId} />}
        {tab === 'documents' && <CaseDocumentsTab caseId={caseId} />}
        {tab === 'invoices' && <CaseInvoicesTab caseId={caseId} clientId={legalCase.clientId} />}
      </TabPanel>

      <ConfirmDialog
        open={pendingStatus !== null}
        onClose={() => setPendingStatus(null)}
        onConfirm={() => pendingStatus && changeStatus.mutate(pendingStatus)}
        busy={changeStatus.isPending}
        destructive={pendingStatus === 'CLOSED'}
        title={pendingStatus === 'CLOSED' ? 'Close this matter?' : `Move to ${humanise(pendingStatus ?? '')}?`}
        description={pendingStatus === 'CLOSED'
          ? 'A closed matter cannot be reopened. Its history, documents and invoices stay intact.'
          : `The matter moves from ${humanise(legalCase.status)} to ${humanise(pendingStatus ?? '')} and the change is written to its timeline.`}
        confirmLabel={pendingStatus === 'CLOSED' ? 'Close matter' : 'Move matter'}
      />
    </>
  );
}
