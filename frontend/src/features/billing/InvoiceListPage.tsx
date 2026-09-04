import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { invoicesApi } from './api';
import { clientsApi } from '@/features/clients/api';
import { keys } from '@/lib/api/queryKeys';
import { useListParams } from '@/lib/api/hooks';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { PageHeader } from '@/components/ui/PageHeader';
import { Button, Card, Field, Select, Toolbar } from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { DataTable } from '@/components/ui/DataTable';
import { Pagination } from '@/components/ui/Pagination';
import { InvoiceStatusBadge } from '@/components/ui/StatusBadge';
import { formatDate, formatMoney, humanise, isPast } from '@/lib/format';
import type { Invoice, InvoiceStatus } from '@/types/api';

const STATUSES: InvoiceStatus[] = [
  'DRAFT', 'ISSUED', 'PARTIALLY_PAID', 'PAID', 'OVERDUE', 'CANCELLED',
];

export function InvoiceListPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const { params, page, update, setPage } = useListParams({ status: '', clientId: '' });

  const query = useQuery({
    queryKey: keys.invoices.list({ ...params, page }),
    queryFn: () => invoicesApi.list({
      status: params.status || undefined,
      clientId: params.clientId || undefined,
      page,
    }),
  });

  const clients = useQuery({
    queryKey: keys.clients.list({ all: true }),
    queryFn: () => clientsApi.list({ size: 200 }),
    enabled: can(user?.role, 'viewCasework'),
  });
  const clientName = (clientId: string) =>
    clients.data?.items.find((client) => client.id === clientId)?.displayName ?? '—';

  return (
    <>
      <PageHeader
        title="Invoices"
        description="What the firm has billed and what is still owed. Every figure is calculated by the server."
        actions={can(user?.role, 'draftInvoices') && (
          <Button icon="plus" onClick={() => navigate('/invoices/new')}>New invoice</Button>
        )}
      />

      <Card>
        <Toolbar>
          <Field label="Status" srOnlyLabel>
            {({ id }) => (
              <Select id={id} value={params.status} aria-label="Filter by status"
                onChange={(event) => update({ status: event.target.value })}>
                <option value="">All statuses</option>
                {STATUSES.map((status) => (
                  <option key={status} value={status}>{humanise(status)}</option>
                ))}
              </Select>
            )}
          </Field>
          {clients.data && (
            <Field label="Client" srOnlyLabel>
              {({ id }) => (
                <Select id={id} value={params.clientId} aria-label="Filter by client"
                  onChange={(event) => update({ clientId: event.target.value })}>
                  <option value="">All clients</option>
                  {clients.data.items.map((client) => (
                    <option key={client.id} value={client.id}>{client.displayName}</option>
                  ))}
                </Select>
              )}
            </Field>
          )}
          {(params.status || params.clientId) && (
            <Button variant="ghost" size="sm"
              onClick={() => update({ status: '', clientId: '' })}>
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
          skeleton={<TableSkeleton columns={6} />}
          empty={(
            <EmptyState
              icon="invoices"
              title="No invoices found"
              description={params.status || params.clientId
                ? 'Try widening the filters.'
                : 'Nothing has been billed yet.'}
            />
          )}
        >
          {(data) => (
            <>
              <DataTable
                caption="Invoices"
                rows={data.items}
                rowKey={(invoice) => invoice.id}
                onRowClick={(invoice) => navigate(`/invoices/${invoice.id}`)}
                columns={[
                  {
                    key: 'number', header: 'Invoice', primary: true,
                    cell: (invoice: Invoice) => (
                      <span className="font-mono font-medium text-ink-900">
                        {invoice.invoiceNumber}
                      </span>
                    ),
                  },
                  {
                    key: 'client', header: 'Client',
                    cell: (invoice: Invoice) => clientName(invoice.clientId),
                  },
                  {
                    key: 'status', header: 'Status',
                    cell: (invoice: Invoice) => <InvoiceStatusBadge status={invoice.status} />,
                  },
                  {
                    key: 'issued', header: 'Issued',
                    cell: (invoice: Invoice) => (
                      <span className="whitespace-nowrap text-ink-600">
                        {formatDate(invoice.issueDate)}
                      </span>
                    ),
                  },
                  {
                    key: 'due', header: 'Due',
                    cell: (invoice: Invoice) => {
                      const late = invoice.status === 'OVERDUE'
                        || (invoice.dueDate ? isPast(invoice.dueDate) && invoice.amountDue !== '0.00' : false);
                      return (
                        <span className={late ? 'font-medium text-red-700' : 'text-ink-600'}>
                          {formatDate(invoice.dueDate)}
                        </span>
                      );
                    },
                  },
                  {
                    key: 'total', header: 'Total', numeric: true,
                    cell: (invoice: Invoice) => (
                      <span className="text-ink-700">
                        {formatMoney(invoice.totalAmount, invoice.currency)}
                      </span>
                    ),
                  },
                  {
                    key: 'outstanding', header: 'Outstanding', numeric: true,
                    // amountDue comes from the backend — the frontend never subtracts.
                    cell: (invoice: Invoice) => (
                      <span className={invoice.amountDue === '0.00'
                        ? 'text-ink-500'
                        : 'font-medium text-ink-900'}>
                        {formatMoney(invoice.amountDue, invoice.currency)}
                      </span>
                    ),
                  },
                ]}
              />
              <Pagination page={data} onPageChange={setPage} label="invoices" />
            </>
          )}
        </AsyncSection>
      </Card>
    </>
  );
}
