import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { clientsApi } from './api';
import { ClientForm } from './ClientForm';
import { casesApi } from '@/features/cases/api';
import { keys } from '@/lib/api/queryKeys';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { useToast } from '@/components/ui/Toast';
import { PageHeader } from '@/components/ui/PageHeader';
import { Badge, Button, Card, CardHeader } from '@/components/ui/primitives';
import { AsyncSection, EmptyState, ErrorState, TableSkeleton } from '@/components/ui/states';
import { ConfirmDialog, Dialog } from '@/components/ui/Dialog';
import { CaseStatusBadge } from '@/components/ui/StatusBadge';
import { formatDate, formatDateTime } from '@/lib/format';
import { messageFor } from '@/lib/api/errors';

function DetailRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[9rem,1fr] gap-3 py-2 text-sm">
      <dt className="text-ink-500">{label}</dt>
      <dd className="text-ink-900">{value || '—'}</dd>
    </div>
  );
}

export function ClientDetailPage() {
  const { clientId = '' } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const query = useQuery({
    queryKey: keys.clients.detail(clientId),
    queryFn: () => clientsApi.byId(clientId),
    enabled: !!clientId,
  });

  const casesQuery = useQuery({
    queryKey: keys.cases.list({ clientId }),
    queryFn: () => casesApi.list({ clientId, size: 50 }),
    enabled: !!clientId,
  });

  const update = useMutation({
    mutationFn: (body: Parameters<typeof clientsApi.update>[1]) =>
      clientsApi.update(clientId, body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.clients.all });
      setEditing(false);
      toast.success('Client updated');
    },
  });

  const remove = useMutation({
    mutationFn: () => clientsApi.remove(clientId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.clients.all });
      toast.success('Client removed');
      navigate('/clients');
    },
    onError: (error) => toast.error(messageFor(error)),
  });

  if (query.isPending) return <TableSkeleton rows={6} />;
  if (query.error || !query.data) {
    return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  }

  const client = query.data;
  const mayManage = can(user?.role, 'manageClients');
  const mayDelete = can(user?.role, 'deleteClients');

  return (
    <>
      <PageHeader
        breadcrumbs={[{ label: 'Clients', to: '/clients' }, { label: client.displayName }]}
        title={client.displayName}
        description={
          <span className="flex items-center gap-2">
            <Badge tone={client.clientType === 'CORPORATE' ? 'info' : 'neutral'}>
              {client.clientType === 'CORPORATE' ? 'Corporate' : 'Individual'}
            </Badge>
            <span>Client since {formatDate(client.createdAt)}</span>
          </span>
        }
        actions={
          <>
            {mayManage && (
              <Button variant="secondary" onClick={() => setEditing(true)}>Edit</Button>
            )}
            {mayDelete && (
              <Button variant="danger" onClick={() => setConfirmingDelete(true)}>Remove</Button>
            )}
          </>
        }
      />

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-1">
          <CardHeader title="Details" />
          <dl className="divide-y divide-ink-100 px-4 py-2">
            <DetailRow label="Email" value={client.email} />
            <DetailRow label="Phone" value={client.phone} />
            <DetailRow label="Address" value={
              [client.addressLine1, client.addressLine2, client.city, client.state,
                client.postalCode, client.country].filter(Boolean).join(', ')
            } />
            <DetailRow label="Notes" value={client.notes} />
            <DetailRow label="Last updated" value={formatDateTime(client.updatedAt)} />
          </dl>
        </Card>

        <Card className="lg:col-span-2">
          <CardHeader
            title="Matters"
            description="Every matter opened for this client."
            actions={
              can(user?.role, 'createCases') && (
                <Link
                  to={`/cases?clientId=${client.id}`}
                  className="text-sm font-medium text-brand-700 hover:underline"
                >
                  Open a matter
                </Link>
              )
            }
          />
          <AsyncSection
            isLoading={casesQuery.isPending}
            error={casesQuery.error}
            data={casesQuery.data}
            isEmpty={(data) => data.items.length === 0}
            onRetry={() => casesQuery.refetch()}
            skeleton={<TableSkeleton rows={3} columns={3} />}
            empty={<EmptyState title="No matters yet"
              description="Matters opened for this client will appear here." />}
          >
            {(data) => (
              <ul className="divide-y divide-ink-100">
                {data.items.map((legalCase) => (
                  <li key={legalCase.id}>
                    <Link
                      to={`/cases/${legalCase.id}`}
                      className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 hover:bg-ink-50"
                    >
                      <span className="min-w-0">
                        <span className="block truncate text-sm font-medium text-ink-900">
                          {legalCase.title}
                        </span>
                        <span className="block text-xs text-ink-500">{legalCase.caseNumber}</span>
                      </span>
                      <CaseStatusBadge status={legalCase.status} />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </AsyncSection>
        </Card>
      </div>

      <Dialog open={editing} onClose={() => setEditing(false)} title="Edit client" footer={<span />}>
        <ClientForm
          client={client}
          submitLabel="Save changes"
          onCancel={() => setEditing(false)}
          onSubmit={(body) => update.mutateAsync(body)}
        />
      </Dialog>

      <ConfirmDialog
        open={confirmingDelete}
        onClose={() => setConfirmingDelete(false)}
        onConfirm={() => remove.mutate()}
        busy={remove.isPending}
        title={`Remove ${client.displayName}?`}
        description="The client is hidden from lists and cannot be used for new matters. Existing matters keep resolving to the name."
        confirmLabel="Remove client"
      />
    </>
  );
}
