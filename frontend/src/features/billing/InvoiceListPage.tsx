import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { invoicesApi } from './api';
import { clientsApi } from '@/features/clients/api';
import { keys } from '@/lib/api/queryKeys';
import { useListParams } from '@/lib/api/hooks';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { PageHeader } from '@/components/ui/PageHeader';
import { Button, Card, Select } from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { DataTable } from '@/components/ui/DataTable';
import { Pagination } from '@/components/ui/Pagination';
import { InvoiceStatusBadge } from '@/components/ui/StatusBadge';
import { formatDate, formatMoney } from '@/lib/format';
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
        description="What the firm has billed and what is still owed."
        actions={can(user?.role, 'draftInvoices') && (
          <Button onClick={() => navigate('/invoices/new')}>New invoice</Button>
        )}
      />

      <Card>
        <div className="flex flex-wrap gap-3 border-b border-ink-200 p-3">
          <div>
            <label htmlFor="invoice-status" className="sr-only">Filter by status</label>
            <Select id="invoice-status" value={params.status}
              onChange={(event) => update({ status: event.target.value })}>
              <option value="">All statuses</option>
              {STATUSES.map((status) => (
                <option key={status} value={status}>
                  {status.charAt(0) + status.slice(1).toLowerCase().replace('_', ' ')}
                </option>
              ))}
            </Select>
          </div>
          {clients.data && (
            <div>
              <label htmlFor="invoice-client" className="sr-only">Filter by client</label>
              <Select id="invoice-client" value={params.clientId}
                onChange={(event) => update({ clientId: event.target.value })}>
                <option value="">All clients</option>
                {clients.data.items.map((client) => (
                  <option key={client.id} value={client.id}>{client.displayName}</option>
                ))}
              </Select>
            </div>
          )}
        </div>

        <AsyncSection
          isLoading={query.isPending}
          error={query.error}
          data={query.data}
          isEmpty={(data) => data.items.length === 0}
          onRetry={() => query.refetch()}
          skeleton={<TableSkeleton columns={6} />}
          empty={(
            <EmptyState
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
                      <span className="font-medium text-ink-900">{invoice.invoiceNumber}</span>
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
                    cell: (invoice: Invoice) => formatDate(invoice.issueDate),
                  },
                  {
                    key: 'due', header: 'Due',
                    cell: (invoice: Invoice) => formatDate(invoice.dueDate),
                  },
                  {
                    key: 'total', header: 'Total',
                    className: 'text-right tabular-nums',
                    headerClassName: 'text-right',
                    cell: (invoice: Invoice) => formatMoney(invoice.totalAmount, invoice.currency),
                  },
                  {
                    key: 'outstanding', header: 'Outstanding',
                    className: 'text-right tabular-nums',
                    headerClassName: 'text-right',
                    // amountDue comes from the backend — the frontend never subtracts.
                    cell: (invoice: Invoice) => formatMoney(invoice.amountDue, invoice.currency),
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
