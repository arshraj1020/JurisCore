import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { invoicesApi } from './api';
import { keys } from '@/lib/api/queryKeys';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { Button, Card, CardHeader } from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { DataTable } from '@/components/ui/DataTable';
import { InvoiceStatusBadge } from '@/components/ui/StatusBadge';
import { formatDate, formatMoney } from '@/lib/format';
import type { Invoice } from '@/types/api';

export function CaseInvoicesTab({ caseId, clientId }: { caseId: string; clientId: string }) {
  const { user } = useAuth();
  const navigate = useNavigate();

  const query = useQuery({
    queryKey: keys.invoices.list({ caseId }),
    queryFn: () => invoicesApi.list({ caseId, size: 50 }),
  });

  return (
    <Card>
      <CardHeader
        icon="invoices"
        title="Invoices"
        description="Billing raised against this matter."
        actions={can(user?.role, 'draftInvoices') && (
          <Button
            size="sm" icon="plus"
            onClick={() => navigate(`/invoices/new?clientId=${clientId}&caseId=${caseId}`)}
          >
            New invoice
          </Button>
        )}
      />

      <AsyncSection
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        isEmpty={(data) => data.items.length === 0}
        onRetry={() => query.refetch()}
        skeleton={<TableSkeleton rows={3} columns={4} />}
        empty={(
          <EmptyState
            compact icon="invoices"
            title="Nothing billed"
            description="No invoice has been raised against this matter yet."
          />
        )}
      >
        {(data) => (
          <DataTable
            caption="Invoices for this matter"
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
                key: 'status', header: 'Status',
                cell: (invoice: Invoice) => <InvoiceStatusBadge status={invoice.status} />,
              },
              {
                key: 'issued', header: 'Issued',
                cell: (invoice: Invoice) => formatDate(invoice.issueDate),
              },
              {
                key: 'total', header: 'Total', numeric: true,
                cell: (invoice: Invoice) => formatMoney(invoice.totalAmount, invoice.currency),
              },
              {
                key: 'outstanding', header: 'Outstanding', numeric: true,
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
        )}
      </AsyncSection>
    </Card>
  );
}
