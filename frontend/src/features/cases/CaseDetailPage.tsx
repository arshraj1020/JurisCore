import { useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { casesApi } from './api';
import { clientsApi } from '@/features/clients/api';
import { keys } from '@/lib/api/queryKeys';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { nextCaseStatuses } from '@/lib/lifecycle';
import { useToast } from '@/components/ui/Toast';
import { PageHeader } from '@/components/ui/PageHeader';
import { Button, Card } from '@/components/ui/primitives';
import { ErrorState, TableSkeleton } from '@/components/ui/states';
import { CaseStatusBadge } from '@/components/ui/StatusBadge';
import { ConfirmDialog } from '@/components/ui/Dialog';
import { formatDate, humanise } from '@/lib/format';
import { messageFor } from '@/lib/api/errors';
import { cn } from '@/lib/cn';
import { CaseOverviewTab } from './tabs/CaseOverviewTab';
import { CaseTimelineTab } from './tabs/CaseTimelineTab';
import { CaseTasksTab } from './tabs/CaseTasksTab';
import { CaseDeadlinesTab } from './tabs/CaseDeadlinesTab';
import { CaseHearingsTab } from './tabs/CaseHearingsTab';
import { CaseDocumentsTab } from '@/features/documents/CaseDocumentsTab';
import { CaseInvoicesTab } from '@/features/billing/CaseInvoicesTab';
import type { CaseStatus } from '@/types/api';

const TABS = [
  { id: 'overview', label: 'Overview' },
  { id: 'timeline', label: 'Timeline' },
  { id: 'hearings', label: 'Hearings' },
  { id: 'tasks', label: 'Tasks' },
  { id: 'deadlines', label: 'Deadlines' },
  { id: 'documents', label: 'Documents' },
  { id: 'invoices', label: 'Invoices' },
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
    queryFn: () => clientsApi.byId(query.data!.clientId),
    enabled: !!query.data?.clientId,
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

  if (query.isPending) return <TableSkeleton rows={6} />;
  if (query.error || !query.data) {
    return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  }

  const legalCase = query.data;
  // Only transitions the backend's CaseStatusPolicy actually permits are offered.
  const available = can(user?.role, 'changeCaseStatus') ? nextCaseStatuses(legalCase.status) : [];

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
        description={
          <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <span className="font-mono text-xs text-ink-500">{legalCase.caseNumber}</span>
            <CaseStatusBadge status={legalCase.status} />
            <span>Opened {formatDate(legalCase.openedAt)}</span>
            {client.data && <span>· {client.data.displayName}</span>}
          </span>
        }
        actions={available.map((status) => (
          <Button
            key={status} variant="secondary" size="sm"
            onClick={() => setPendingStatus(status)}
            disabled={changeStatus.isPending}
          >
            {status === 'CLOSED' ? 'Close matter' : `Move to ${humanise(status)}`}
          </Button>
        ))}
      />

      <div className="mb-4 overflow-x-auto border-b border-ink-200">
        <div role="tablist" aria-label="Matter sections" className="flex min-w-max gap-1">
          {TABS.map((candidate) => (
            <button
              key={candidate.id}
              type="button"
              role="tab"
              aria-selected={tab === candidate.id}
              onClick={() => setTab(candidate.id)}
              className={cn(
                'whitespace-nowrap border-b-2 px-3 py-2 text-sm font-medium transition-colors',
                tab === candidate.id
                  ? 'border-brand-600 text-brand-700'
                  : 'border-transparent text-ink-600 hover:border-ink-300 hover:text-ink-900',
              )}
            >
              {candidate.label}
            </button>
          ))}
        </div>
      </div>

      <div role="tabpanel">
        {tab === 'overview' && <CaseOverviewTab legalCase={legalCase} />}
        {tab === 'timeline' && <CaseTimelineTab caseId={caseId} />}
        {tab === 'hearings' && <CaseHearingsTab caseId={caseId} />}
        {tab === 'tasks' && <CaseTasksTab caseId={caseId} />}
        {tab === 'deadlines' && <CaseDeadlinesTab caseId={caseId} />}
        {tab === 'documents' && <CaseDocumentsTab caseId={caseId} />}
        {tab === 'invoices' && (
          <Card>
            <CaseInvoicesTab caseId={caseId} clientId={legalCase.clientId} />
          </Card>
        )}
      </div>

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
