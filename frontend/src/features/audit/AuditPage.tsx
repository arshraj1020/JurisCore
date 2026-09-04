import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { auditApi } from './api';
import { usersApi } from '@/features/auth/api';
import { keys } from '@/lib/api/queryKeys';
import { useDebounced, useListParams } from '@/lib/api/hooks';
import { PageHeader } from '@/components/ui/PageHeader';
import { Card, Input, Select } from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { DataTable } from '@/components/ui/DataTable';
import { Pagination } from '@/components/ui/Pagination';
import { formatDateTime, humanise } from '@/lib/format';
import type { AuditEvent } from '@/types/api';

/**
 * The audit trail, read-only by construction.
 *
 * There is deliberately no edit and no delete here — not because they are hidden from
 * this role, but because a trail that can be altered from the application it records is
 * not a trail. The backend exposes GET and nothing else.
 */
export function AuditPage() {
  const { params, page, update, setPage } = useListParams({
    action: '', entityType: '', entityId: '', from: '', to: '',
  });
  const [actionInput, setActionInput] = useState(params.action);
  const debouncedAction = useDebounced(actionInput);

  useEffect(() => {
    if (debouncedAction !== params.action) update({ action: debouncedAction });
    // `update` is recreated on every render; depending on it would loop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedAction, params.action]);

  const query = useQuery({
    queryKey: keys.audit.list({ ...params, page }),
    queryFn: () => auditApi.list({
      action: params.action || undefined,
      entityType: params.entityType || undefined,
      entityId: params.entityId || undefined,
      from: params.from ? new Date(`${params.from}T00:00:00`).toISOString() : undefined,
      to: params.to ? new Date(`${params.to}T23:59:59`).toISOString() : undefined,
      page,
      size: 50,
    }),
  });

  // Actor ids are opaque; the member list turns them into names for the people reading.
  const members = useQuery({
    queryKey: keys.users.list({ all: true }),
    queryFn: () => usersApi.list({ size: 200 }),
  });
  const actorName = (userId: string | null | undefined) => {
    if (!userId) return 'System';
    const member = members.data?.items.find((user) => user.id === userId);
    return member ? `${member.firstName} ${member.lastName}` : userId;
  };

  const entityTypes = Array.from(
    new Set((query.data?.items ?? []).map((event) => event.entityType)),
  ).sort();

  return (
    <>
      <PageHeader
        title="Audit trail"
        description="A record of what happened, who did it and when. Entries cannot be edited or removed."
      />

      <Card>
        <div className="flex flex-wrap gap-3 border-b border-ink-200 p-3">
          <div className="min-w-[12rem] flex-1">
            <label htmlFor="audit-action" className="sr-only">Filter by action</label>
            <Input id="audit-action" type="search" placeholder="Action, e.g. INVOICE_ISSUED"
              value={actionInput} onChange={(event) => setActionInput(event.target.value)} />
          </div>
          <div>
            <label htmlFor="audit-entity" className="sr-only">Filter by entity type</label>
            <Select id="audit-entity" value={params.entityType}
              onChange={(event) => update({ entityType: event.target.value })}>
              <option value="">All entities</option>
              {entityTypes.map((type) => (
                <option key={type} value={type}>{type}</option>
              ))}
            </Select>
          </div>
          <div>
            <label htmlFor="audit-from" className="block text-xs text-ink-500">From</label>
            <Input id="audit-from" type="date" value={params.from}
              onChange={(event) => update({ from: event.target.value })} />
          </div>
          <div>
            <label htmlFor="audit-to" className="block text-xs text-ink-500">To</label>
            <Input id="audit-to" type="date" value={params.to}
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
              title="No audit entries"
              description="Nothing matches these filters."
            />
          )}
        >
          {(data) => (
            <>
              <DataTable
                caption="Audit entries"
                rows={data.items}
                rowKey={(event) => event.id}
                columns={[
                  {
                    key: 'summary', header: 'What happened', primary: true,
                    cell: (event: AuditEvent) => (
                      <span>
                        <span className="block text-ink-900">{event.summary}</span>
                        <span className="block text-xs text-ink-500">
                          {humanise(event.action)}
                        </span>
                      </span>
                    ),
                  },
                  {
                    key: 'actor', header: 'Who',
                    cell: (event: AuditEvent) => actorName(event.actorUserId),
                  },
                  {
                    key: 'entity', header: 'Entity',
                    cell: (event: AuditEvent) => (
                      <span>
                        <span className="block text-ink-800">{event.entityType}</span>
                        {event.entityId && (
                          <span className="block font-mono text-[11px] text-ink-500">
                            {event.entityId}
                          </span>
                        )}
                      </span>
                    ),
                  },
                  {
                    key: 'when', header: 'When',
                    cell: (event: AuditEvent) => (
                      <time dateTime={event.occurredAt}>{formatDateTime(event.occurredAt)}</time>
                    ),
                  },
                  {
                    key: 'request', header: 'Request',
                    cell: (event: AuditEvent) => (
                      <span className="font-mono text-[11px] text-ink-500">
                        {event.requestId ?? '—'}
                      </span>
                    ),
                  },
                ]}
              />
              <Pagination page={data} onPageChange={setPage} label="entries" />
            </>
          )}
        </AsyncSection>
      </Card>
    </>
  );
}
