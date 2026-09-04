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
import {
  Avatar, Badge, Button, Card, CardHeader, Detail, DetailList,
} from '@/components/ui/primitives';
import { Icon } from '@/components/ui/icons';
import { AsyncSection, EmptyState, ErrorState, TableSkeleton } from '@/components/ui/states';
import { ConfirmDialog, Dialog } from '@/components/ui/Dialog';
import { CaseStatusBadge } from '@/components/ui/StatusBadge';
import { formatDate, formatDateTime } from '@/lib/format';
import { messageFor } from '@/lib/api/errors';

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

  if (query.isPending) return <Card><TableSkeleton rows={6} /></Card>;
  if (query.error || !query.data) {
    return <Card><ErrorState error={query.error} onRetry={() => query.refetch()} /></Card>;
  }

  const client = query.data;
  const mayManage = can(user?.role, 'manageClients');
  const mayDelete = can(user?.role, 'deleteClients');
  const address = [
    client.addressLine1, client.addressLine2,
    [client.city, client.state].filter(Boolean).join(', '),
    [client.postalCode, client.country].filter(Boolean).join(' '),
  ].map((line) => line?.trim()).filter((line): line is string => !!line);

  return (
    <>
      <PageHeader
        breadcrumbs={[{ label: 'Clients', to: '/clients' }, { label: client.displayName }]}
        title={(
          <span className="flex items-center gap-2.5">
            <Avatar name={client.displayName} />
            {client.displayName}
          </span>
        )}
        meta={(
          <Badge tone={client.clientType === 'CORPORATE' ? 'info' : 'neutral'} dot>
            {client.clientType === 'CORPORATE' ? 'Corporate' : 'Individual'}
          </Badge>
        )}
        description={`Client since ${formatDate(client.createdAt)}`}
        actions={
          <>
            {mayManage && (
              <Button variant="secondary" icon="edit" onClick={() => setEditing(true)}>
                Edit
              </Button>
            )}
            {mayDelete && (
              <Button variant="secondary" icon="trash" onClick={() => setConfirmingDelete(true)}>
                Remove
              </Button>
            )}
          </>
        }
      />

      <div className="grid items-start gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-1">
          <CardHeader title="Contact details" icon="clients" />
          <DetailList columns={2} className="sm:grid-cols-1">
            <Detail label="Email">
              {client.email
                ? (
                  <a href={`mailto:${client.email}`}
                    className="inline-flex items-center gap-1 break-all text-brand-700 hover:underline">
                    {client.email}
                  </a>
                )
                : <span className="text-ink-500">Not recorded</span>}
            </Detail>
            <Detail label="Phone">
              {client.phone ?? <span className="text-ink-500">Not recorded</span>}
            </Detail>
            <Detail label="Address">
              {address.length > 0
                ? <span className="whitespace-pre-line">{address.join('\n')}</span>
                : <span className="text-ink-500">Not recorded</span>}
            </Detail>
            {client.notes && (
              <Detail label="Notes">
                <span className="whitespace-pre-wrap text-ink-700">{client.notes}</span>
              </Detail>
            )}
            <Detail label="Last updated">
              <span className="text-ink-600">{formatDateTime(client.updatedAt)}</span>
            </Detail>
          </DetailList>
        </Card>

        <Card className="lg:col-span-2">
          <CardHeader
            title="Matters"
            icon="cases"
            description="Every matter opened for this client."
            actions={
              can(user?.role, 'createCases') && (
                <Link
                  to={`/cases?clientId=${client.id}`}
                  className="inline-flex items-center gap-1 rounded text-xs font-medium text-brand-700 hover:underline"
                >
                  Open a matter
                  <Icon name="chevronRight" className="h-3 w-3" />
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
            empty={<EmptyState compact icon="cases" title="No matters yet"
              description="Matters opened for this client will appear here." />}
          >
            {(data) => (
              <ul className="divide-y divide-ink-100">
                {data.items.map((legalCase) => (
                  <li key={legalCase.id}>
                    <Link
                      to={`/cases/${legalCase.id}`}
                      className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5 transition-colors hover:bg-brand-50/40"
                    >
                      <span className="min-w-0">
                        <span className="block truncate text-sm font-medium text-ink-900">
                          {legalCase.title}
                        </span>
                        <span className="mt-0.5 block font-mono text-xs text-ink-500">
                          {legalCase.caseNumber}
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
