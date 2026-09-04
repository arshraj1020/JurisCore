import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { courtsApi, hearingsApi } from './api';
import { casesApi } from '@/features/cases/api';
import { keys } from '@/lib/api/queryKeys';
import { useListParams } from '@/lib/api/hooks';
import { PageHeader } from '@/components/ui/PageHeader';
import { Card, Input, Select } from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { DataTable } from '@/components/ui/DataTable';
import { Pagination } from '@/components/ui/Pagination';
import { HearingStatusBadge } from '@/components/ui/StatusBadge';
import { formatDateTime, formatRelative, humanise } from '@/lib/format';
import type { Hearing, HearingStatus } from '@/types/api';

const STATUSES: HearingStatus[] = ['SCHEDULED', 'COMPLETED', 'ADJOURNED', 'CANCELLED'];

/**
 * The firm's diary.
 *
 * Defaults to scheduled hearings from today onwards, because the question this page
 * answers on most days is "what is coming up", not "everything that has ever been listed".
 */
export function HearingListPage() {
  const navigate = useNavigate();
  const today = new Date().toISOString().slice(0, 10);
  const { params, page, update, setPage } = useListParams({
    status: 'SCHEDULED', courtId: '', from: today, to: '',
  });

  const query = useQuery({
    queryKey: keys.hearings.list({ ...params, page }),
    queryFn: () => hearingsApi.list({
      status: params.status || undefined,
      courtId: params.courtId || undefined,
      // The API takes instants; a date filter covers the whole day in the browser's zone.
      from: params.from ? new Date(`${params.from}T00:00:00`).toISOString() : undefined,
      to: params.to ? new Date(`${params.to}T23:59:59`).toISOString() : undefined,
      page,
    }),
  });

  const courts = useQuery({
    queryKey: keys.courts.list({ all: true }),
    queryFn: () => courtsApi.list({ size: 200 }),
  });
  const courtName = (courtId: string) =>
    courts.data?.items.find((court) => court.id === courtId)?.name ?? '—';

  const cases = useQuery({
    queryKey: keys.cases.list({ all: true, forDiary: true }),
    queryFn: () => casesApi.list({ size: 200 }),
  });
  const caseLabel = (caseId: string) =>
    cases.data?.items.find((legalCase) => legalCase.id === caseId)?.caseNumber ?? '—';

  return (
    <>
      <PageHeader title="Diary" description="Hearings listed across the firm's matters." />

      <Card>
        <div className="flex flex-wrap gap-3 border-b border-ink-200 p-3">
          <div>
            <label htmlFor="hearing-status" className="sr-only">Filter by status</label>
            <Select id="hearing-status" value={params.status}
              onChange={(event) => update({ status: event.target.value })}>
              <option value="">All statuses</option>
              {STATUSES.map((status) => (
                <option key={status} value={status}>{humanise(status)}</option>
              ))}
            </Select>
          </div>
          <div>
            <label htmlFor="hearing-court" className="sr-only">Filter by court</label>
            <Select id="hearing-court" value={params.courtId}
              onChange={(event) => update({ courtId: event.target.value })}>
              <option value="">All courts</option>
              {courts.data?.items.map((court) => (
                <option key={court.id} value={court.id}>{court.name}</option>
              ))}
            </Select>
          </div>
          <div>
            <label htmlFor="hearing-from" className="block text-xs text-ink-500">From</label>
            <Input id="hearing-from" type="date" value={params.from}
              onChange={(event) => update({ from: event.target.value })} />
          </div>
          <div>
            <label htmlFor="hearing-to" className="block text-xs text-ink-500">To</label>
            <Input id="hearing-to" type="date" value={params.to}
              onChange={(event) => update({ to: event.target.value })} />
          </div>
        </div>

        <AsyncSection
          isLoading={query.isPending}
          error={query.error}
          data={query.data}
          isEmpty={(data) => data.items.length === 0}
          onRetry={() => query.refetch()}
          skeleton={<TableSkeleton columns={5} />}
          empty={(
            <EmptyState
              title="Nothing listed"
              description="No hearing matches these filters. Hearings are scheduled from a matter."
            />
          )}
        >
          {(data) => (
            <>
              <DataTable
                caption="Hearings"
                rows={data.items}
                rowKey={(hearing) => hearing.id}
                onRowClick={(hearing) => navigate(`/cases/${hearing.caseId}?tab=hearings`)}
                columns={[
                  {
                    key: 'when', header: 'Listed for', primary: true,
                    cell: (hearing: Hearing) => (
                      <span>
                        <span className="block font-medium text-ink-900">
                          {formatDateTime(hearing.scheduledAt)}
                        </span>
                        <span className="block text-xs text-ink-500">
                          {formatRelative(hearing.scheduledAt)}
                        </span>
                      </span>
                    ),
                  },
                  {
                    key: 'case', header: 'Matter',
                    cell: (hearing: Hearing) => caseLabel(hearing.caseId),
                  },
                  {
                    key: 'court', header: 'Court',
                    cell: (hearing: Hearing) => courtName(hearing.courtId),
                  },
                  {
                    key: 'type', header: 'Type',
                    cell: (hearing: Hearing) => humanise(hearing.hearingType),
                  },
                  {
                    key: 'status', header: 'Status',
                    cell: (hearing: Hearing) => <HearingStatusBadge status={hearing.status} />,
                  },
                ]}
              />
              <Pagination page={data} onPageChange={setPage} label="hearings" />
            </>
          )}
        </AsyncSection>
      </Card>
    </>
  );
}
