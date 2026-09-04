import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { casesApi } from './api';
import { clientsApi } from '@/features/clients/api';
import { keys } from '@/lib/api/queryKeys';
import { useDebounced, useListParams } from '@/lib/api/hooks';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { useToast } from '@/components/ui/Toast';
import { PageHeader } from '@/components/ui/PageHeader';
import {
  Button, Card, Field, Input, SearchInput, Select, Textarea, Toolbar,
} from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { DataTable } from '@/components/ui/DataTable';
import { Dialog } from '@/components/ui/Dialog';
import { Pagination } from '@/components/ui/Pagination';
import { CaseStatusBadge } from '@/components/ui/StatusBadge';
import { formatDate, humanise } from '@/lib/format';
import { fieldErrorsOf, messageFor } from '@/lib/api/errors';
import type { CaseStatus, LegalCase } from '@/types/api';

const CASE_STATUSES: CaseStatus[] = ['OPEN', 'IN_PROGRESS', 'ON_HOLD', 'CLOSED'];

const createSchema = z.object({
  title: z.string().min(1, 'Enter a title').max(300),
  clientId: z.string().uuid('Choose a client'),
  description: z.string().max(4000).or(z.literal('')),
});
type CreateValues = z.infer<typeof createSchema>;

function CreateCaseForm({ defaultClientId, onDone, onCancel }: {
  defaultClientId?: string; onDone: (legalCase: LegalCase) => void; onCancel: () => void;
}) {
  const queryClient = useQueryClient();
  const clients = useQuery({
    queryKey: keys.clients.list({ all: true }),
    queryFn: () => clientsApi.list({ size: 200 }),
  });

  const { register, handleSubmit, setError, formState: { errors, isSubmitting } } =
    useForm<CreateValues>({
      resolver: zodResolver(createSchema),
      defaultValues: { title: '', clientId: defaultClientId ?? '', description: '' },
    });

  const create = useMutation({
    mutationFn: casesApi.create,
    onSuccess: async (legalCase) => {
      await queryClient.invalidateQueries({ queryKey: keys.cases.all });
      onDone(legalCase);
    },
  });

  const submit = handleSubmit(async (values) => {
    try {
      await create.mutateAsync({
        title: values.title.trim(),
        clientId: values.clientId,
        description: values.description.trim() || null,
      });
    } catch (error) {
      for (const [field, message] of Object.entries(fieldErrorsOf(error))) {
        if (field in createSchema.shape) setError(field as keyof CreateValues, { message });
      }
      setError('root', { message: messageFor(error) });
    }
  });

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      {errors.root && (
        <div role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800 ring-1 ring-inset ring-red-200">
          {errors.root.message}
        </div>
      )}

      <Field label="Matter title" error={errors.title?.message} required>
        {({ id, describedBy, invalid }) => (
          <Input id={id} autoFocus aria-describedby={describedBy} invalid={invalid}
            {...register('title')} />
        )}
      </Field>

      <Field label="Client" error={errors.clientId?.message} required>
        {({ id, describedBy, invalid }) => (
          <Select id={id} aria-describedby={describedBy} invalid={invalid}
            disabled={clients.isPending} {...register('clientId')}>
            <option value="">Choose a client…</option>
            {clients.data?.items.map((client) => (
              <option key={client.id} value={client.id}>{client.displayName}</option>
            ))}
          </Select>
        )}
      </Field>

      <Field label="Description" error={errors.description?.message}>
        {({ id, describedBy, invalid }) => (
          <Textarea id={id} rows={3} aria-describedby={describedBy} invalid={invalid}
            {...register('description')} />
        )}
      </Field>

      <div className="flex justify-end gap-2 pt-2">
        <Button type="button" variant="secondary" onClick={onCancel} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" loading={isSubmitting}>Open matter</Button>
      </div>
    </form>
  );
}

export function CaseListPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const toast = useToast();
  const { params, page, update, setPage } = useListParams({ search: '', status: '', clientId: '' });
  const [searchInput, setSearchInput] = useState(params.search);
  const debouncedSearch = useDebounced(searchInput);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    if (debouncedSearch !== params.search) update({ search: debouncedSearch });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedSearch, params.search]);

  const query = useQuery({
    queryKey: keys.cases.list({ ...params, page }),
    queryFn: () => casesApi.list({
      search: params.search || undefined,
      status: params.status || undefined,
      clientId: params.clientId || undefined,
      page,
    }),
  });

  const clients = useQuery({
    queryKey: keys.clients.list({ all: true }),
    queryFn: () => clientsApi.list({ size: 200 }),
  });

  const clientName = (clientId: string) =>
    clients.data?.items.find((client) => client.id === clientId)?.displayName ?? '—';

  return (
    <>
      <PageHeader
        title="Matters"
        description="Every matter the firm is running."
        actions={can(user?.role, 'createCases') && (
          <Button icon="plus" onClick={() => setCreating(true)}>Open matter</Button>
        )}
      />

      <Card>
        <Toolbar>
          <div className="min-w-[12rem] flex-1 sm:max-w-xs">
            <Field label="Search matters" srOnlyLabel>
              {({ id }) => (
                <SearchInput id={id} placeholder="Title or matter number"
                  value={searchInput}
                  onChange={(event) => setSearchInput(event.target.value)} />
              )}
            </Field>
          </div>
          <Field label="Status" srOnlyLabel>
            {({ id }) => (
              <Select id={id} value={params.status} aria-label="Filter by status"
                onChange={(event) => update({ status: event.target.value })}>
                <option value="">All statuses</option>
                {CASE_STATUSES.map((status) => (
                  <option key={status} value={status}>{humanise(status)}</option>
                ))}
              </Select>
            )}
          </Field>
          <Field label="Client" srOnlyLabel>
            {({ id }) => (
              <Select id={id} value={params.clientId} aria-label="Filter by client"
                onChange={(event) => update({ clientId: event.target.value })}>
                <option value="">All clients</option>
                {clients.data?.items.map((client) => (
                  <option key={client.id} value={client.id}>{client.displayName}</option>
                ))}
              </Select>
            )}
          </Field>
          {(params.search || params.status || params.clientId) && (
            <Button variant="ghost" size="sm"
              onClick={() => { setSearchInput(''); update({ search: '', status: '', clientId: '' }); }}>
              Clear filters
            </Button>
          )}
        </Toolbar>

        <AsyncSection
          isLoading={query.isPending}
          error={query.error}
          data={query.data}
          isEmpty={(data) => data.items.length === 0}
          onRetry={() => query.refetch()}
          skeleton={<TableSkeleton columns={5} />}
          empty={<EmptyState
            icon={params.search || params.status || params.clientId ? 'search' : 'cases'}
            title="No matters found"
            description={params.search || params.status || params.clientId
              ? 'Try widening the filters.'
              : 'Open the first matter to start tracking hearings, tasks and documents against it.'}
          />}
        >
          {(data) => (
            <>
              <DataTable
                caption="Matters"
                rows={data.items}
                rowKey={(legalCase) => legalCase.id}
                onRowClick={(legalCase) => navigate(`/cases/${legalCase.id}`)}
                columns={[
                  {
                    key: 'title', header: 'Matter', primary: true,
                    cell: (legalCase: LegalCase) => (
                      <span>
                        <span className="block font-medium text-ink-900">{legalCase.title}</span>
                        <span className="mt-0.5 block font-mono text-xs text-ink-500">
                          {legalCase.caseNumber}
                        </span>
                      </span>
                    ),
                  },
                  {
                    key: 'client', header: 'Client',
                    cell: (legalCase: LegalCase) => clientName(legalCase.clientId),
                  },
                  {
                    key: 'status', header: 'Status',
                    cell: (legalCase: LegalCase) => <CaseStatusBadge status={legalCase.status} />,
                  },
                  {
                    key: 'opened', header: 'Opened',
                    cell: (legalCase: LegalCase) => (
                      <span className="whitespace-nowrap text-ink-600">
                        {formatDate(legalCase.openedAt)}
                      </span>
                    ),
                  },
                ]}
              />
              <Pagination page={data} onPageChange={setPage} label="matters" />
            </>
          )}
        </AsyncSection>
      </Card>

      <Dialog
        open={creating} onClose={() => setCreating(false)} title="Open a matter"
        description="The matter number is issued by the system once it is opened."
        footer={<span />}
      >
        <CreateCaseForm
          defaultClientId={params.clientId || undefined}
          onCancel={() => setCreating(false)}
          onDone={(legalCase) => {
            setCreating(false);
            toast.success(`${legalCase.caseNumber} opened`);
            navigate(`/cases/${legalCase.id}`);
          }}
        />
      </Dialog>
    </>
  );
}
