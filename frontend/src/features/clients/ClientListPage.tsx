import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { clientsApi } from './api';
import { ClientForm } from './ClientForm';
import { keys } from '@/lib/api/queryKeys';
import { useDebounced, useListParams } from '@/lib/api/hooks';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { useToast } from '@/components/ui/Toast';
import { PageHeader } from '@/components/ui/PageHeader';
import { Badge, Button, Card, Input } from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { DataTable } from '@/components/ui/DataTable';
import { Dialog } from '@/components/ui/Dialog';
import { Pagination } from '@/components/ui/Pagination';
import { formatDate } from '@/lib/format';
import type { Client } from '@/types/api';

export function ClientListPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const toast = useToast();
  const queryClient = useQueryClient();
  const { params, page, update, setPage } = useListParams({ search: '' });
  const [searchInput, setSearchInput] = useState(params.search);
  const debouncedSearch = useDebounced(searchInput);
  const [creating, setCreating] = useState(false);

  // The URL is the source of truth for the query; the input only feeds it after a pause.
  // In an effect, not during render: setting router state while rendering re-enters the
  // render immediately and loops.
  useEffect(() => {
    if (debouncedSearch !== params.search) update({ search: debouncedSearch });
    // `update` is derived from the current search params and changes every render, so it
    // is deliberately not a dependency — the debounced value is what should drive this.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedSearch, params.search]);

  const query = useQuery({
    queryKey: keys.clients.list({ search: params.search, page }),
    queryFn: () => clientsApi.list({ search: params.search || undefined, page }),
  });

  const create = useMutation({
    mutationFn: clientsApi.create,
    onSuccess: async (client) => {
      await queryClient.invalidateQueries({ queryKey: keys.clients.all });
      setCreating(false);
      toast.success(`${client.displayName} added`);
      navigate(`/clients/${client.id}`);
    },
  });

  const mayManage = can(user?.role, 'manageClients');

  return (
    <>
      <PageHeader
        title="Clients"
        description="Everyone the firm acts for. Removed clients are hidden."
        actions={mayManage && <Button onClick={() => setCreating(true)}>Add client</Button>}
      />

      <Card>
        <div className="border-b border-ink-200 p-3">
          <label htmlFor="client-search" className="sr-only">Search clients</label>
          <Input
            id="client-search" type="search" placeholder="Search by name, email or phone"
            value={searchInput} onChange={(event) => setSearchInput(event.target.value)}
            className="max-w-sm"
          />
        </div>

        <AsyncSection
          isLoading={query.isPending}
          error={query.error}
          data={query.data}
          isEmpty={(data) => data.items.length === 0}
          onRetry={() => query.refetch()}
          skeleton={<TableSkeleton columns={4} />}
          empty={
            <EmptyState
              title={params.search ? 'No clients match that search' : 'No clients yet'}
              description={
                params.search
                  ? 'Try a different name, email or phone number.'
                  : 'Add the first client to start opening matters against it.'
              }
              action={mayManage && !params.search
                ? <Button onClick={() => setCreating(true)}>Add client</Button>
                : undefined}
            />
          }
        >
          {(data) => (
            <>
              <DataTable
                caption="Clients"
                rows={data.items}
                rowKey={(client) => client.id}
                onRowClick={(client) => navigate(`/clients/${client.id}`)}
                columns={[
                  {
                    key: 'name', header: 'Name', primary: true,
                    cell: (client: Client) => (
                      <span className="font-medium text-ink-900">{client.displayName}</span>
                    ),
                  },
                  {
                    key: 'type', header: 'Type',
                    cell: (client: Client) => (
                      <Badge tone={client.clientType === 'CORPORATE' ? 'info' : 'neutral'}>
                        {client.clientType === 'CORPORATE' ? 'Corporate' : 'Individual'}
                      </Badge>
                    ),
                  },
                  { key: 'email', header: 'Email', cell: (client: Client) => client.email ?? '—' },
                  { key: 'phone', header: 'Phone', cell: (client: Client) => client.phone ?? '—' },
                  {
                    key: 'added', header: 'Added',
                    cell: (client: Client) => formatDate(client.createdAt),
                  },
                ]}
              />
              <Pagination page={data} onPageChange={setPage} label="clients" />
            </>
          )}
        </AsyncSection>
      </Card>

      <Dialog open={creating} onClose={() => setCreating(false)} title="Add client" footer={<span />}>
        <ClientForm
          submitLabel="Add client"
          onCancel={() => setCreating(false)}
          onSubmit={(body) => create.mutateAsync(body)}
        />
      </Dialog>
    </>
  );
}
