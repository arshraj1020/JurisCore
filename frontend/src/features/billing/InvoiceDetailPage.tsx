import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { invoicesApi } from './api';
import { clientsApi } from '@/features/clients/api';
import { casesApi } from '@/features/cases/api';
import { keys } from '@/lib/api/queryKeys';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import {
  canCancelInvoice, canIssueInvoice, canRecordPayment, isInvoiceEditable,
} from '@/lib/lifecycle';
import { useToast } from '@/components/ui/Toast';
import { PageHeader } from '@/components/ui/PageHeader';
import { Button, Card, CardHeader, Field, Input, Select, Textarea } from '@/components/ui/primitives';
import { AsyncSection, ErrorState, TableSkeleton } from '@/components/ui/states';
import { Dialog } from '@/components/ui/Dialog';
import { InvoiceStatusBadge } from '@/components/ui/StatusBadge';
import { formatDate, formatDateTime, formatMoney, formatPercent, formatQuantity, humanise } from '@/lib/format';
import { parseDecimal } from '@/lib/money';
import { messageFor } from '@/lib/api/errors';
import type { Invoice, PaymentMethod } from '@/types/api';

const METHODS: PaymentMethod[] = ['BANK_TRANSFER', 'UPI', 'CARD', 'CHEQUE', 'CASH', 'OTHER'];

export function InvoiceDetailPage() {
  const { invoiceId = '' } = useParams();
  const { user } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<'edit' | 'issue' | 'cancel' | 'payment' | null>(null);

  const query = useQuery({
    queryKey: keys.invoices.detail(invoiceId),
    queryFn: () => invoicesApi.byId(invoiceId),
    enabled: invoiceId !== '',
  });

  const payments = useQuery({
    queryKey: keys.invoices.payments(invoiceId, 0),
    queryFn: () => invoicesApi.payments(invoiceId, 0),
    enabled: invoiceId !== '',
  });

  const client = useQuery({
    queryKey: keys.clients.detail(query.data?.clientId ?? ''),
    queryFn: () => clientsApi.byId(query.data?.clientId ?? ''),
    enabled: Boolean(query.data?.clientId) && can(user?.role, 'viewCasework'),
  });

  const legalCase = useQuery({
    queryKey: keys.cases.detail(query.data?.caseId ?? ''),
    queryFn: () => casesApi.byId(query.data?.caseId ?? ''),
    enabled: Boolean(query.data?.caseId) && can(user?.role, 'viewCasework'),
  });

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: keys.invoices.all });
  };

  if (query.error) {
    return (
      <>
        <PageHeader title="Invoice" breadcrumbs={[{ label: 'Invoices', to: '/invoices' }, { label: 'Invoice' }]} />
        <Card>
          <ErrorState error={query.error} onRetry={() => query.refetch()} />
        </Card>
      </>
    );
  }

  const invoice = query.data;
  const mayDraft = can(user?.role, 'draftInvoices');
  const mayAdminister = can(user?.role, 'administerBilling');

  return (
    <>
      <PageHeader
        title={invoice ? invoice.invoiceNumber : 'Invoice'}
        description={invoice ? `Raised ${formatDate(invoice.createdAt)}` : undefined}
        breadcrumbs={[
          { label: 'Invoices', to: '/invoices' },
          { label: invoice?.invoiceNumber ?? 'Invoice' },
        ]}
        actions={invoice && (
          <div className="flex flex-wrap items-center gap-2">
            <InvoiceStatusBadge status={invoice.status} />
            {mayDraft && isInvoiceEditable(invoice.status) && (
              <Button variant="secondary" onClick={() => setDialog('edit')}>Edit draft</Button>
            )}
            {mayAdminister && canIssueInvoice(invoice.status) && (
              <Button onClick={() => setDialog('issue')}>Issue</Button>
            )}
            {mayAdminister && canRecordPayment(invoice.status) && (
              <Button onClick={() => setDialog('payment')}>Record payment</Button>
            )}
            {mayAdminister && canCancelInvoice(invoice.status) && (
              <Button variant="danger" onClick={() => setDialog('cancel')}>Cancel</Button>
            )}
          </div>
        )}
      />

      {!invoice ? (
        <Card><TableSkeleton rows={5} columns={4} /></Card>
      ) : (
        <div className="space-y-4">
          <Card>
            <CardHeader title="Details" />
            <dl className="grid gap-x-6 gap-y-3 p-4 text-sm sm:grid-cols-2">
              <div>
                <dt className="text-xs uppercase tracking-wide text-ink-500">Client</dt>
                <dd className="mt-0.5 text-ink-900">
                  {can(user?.role, 'viewCasework') ? (
                    <Link to={`/clients/${invoice.clientId}`} className="text-brand-700 hover:underline">
                      {client.data?.displayName ?? 'View client'}
                    </Link>
                  ) : (client.data?.displayName ?? '—')}
                </dd>
              </div>
              <div>
                <dt className="text-xs uppercase tracking-wide text-ink-500">Matter</dt>
                <dd className="mt-0.5 text-ink-900">
                  {invoice.caseId ? (
                    can(user?.role, 'viewCasework') ? (
                      <Link to={`/cases/${invoice.caseId}`} className="text-brand-700 hover:underline">
                        {legalCase.data?.caseNumber ?? 'View matter'}
                      </Link>
                    ) : (legalCase.data?.caseNumber ?? '—')
                  ) : 'Not tied to a matter'}
                </dd>
              </div>
              <div>
                <dt className="text-xs uppercase tracking-wide text-ink-500">Issue date</dt>
                <dd className="mt-0.5 text-ink-900">{formatDate(invoice.issueDate)}</dd>
              </div>
              <div>
                <dt className="text-xs uppercase tracking-wide text-ink-500">Due date</dt>
                <dd className="mt-0.5 text-ink-900">{formatDate(invoice.dueDate)}</dd>
              </div>
              {invoice.paidAt && (
                <div>
                  <dt className="text-xs uppercase tracking-wide text-ink-500">Settled</dt>
                  <dd className="mt-0.5 text-ink-900">{formatDateTime(invoice.paidAt)}</dd>
                </div>
              )}
              {invoice.cancelledAt && (
                <div>
                  <dt className="text-xs uppercase tracking-wide text-ink-500">Cancelled</dt>
                  <dd className="mt-0.5 text-ink-900">{formatDateTime(invoice.cancelledAt)}</dd>
                </div>
              )}
              {invoice.notes && (
                <div className="sm:col-span-2">
                  <dt className="text-xs uppercase tracking-wide text-ink-500">Notes</dt>
                  <dd className="mt-0.5 whitespace-pre-wrap text-ink-800">{invoice.notes}</dd>
                </div>
              )}
            </dl>
          </Card>

          <Card>
            <CardHeader title="Lines" />
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-ink-200 text-sm">
                <caption className="sr-only">Invoice lines</caption>
                <thead>
                  <tr className="bg-ink-50 text-xs uppercase tracking-wide text-ink-600">
                    <th scope="col" className="px-4 py-2.5 text-left font-semibold">Description</th>
                    <th scope="col" className="px-4 py-2.5 text-right font-semibold">Qty</th>
                    <th scope="col" className="px-4 py-2.5 text-right font-semibold">Unit price</th>
                    <th scope="col" className="px-4 py-2.5 text-right font-semibold">Amount</th>
                    <th scope="col" className="px-4 py-2.5 text-right font-semibold">Tax</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-ink-100">
                  {(invoice.lineItems ?? []).map((line) => (
                    <tr key={line.id}>
                      <td className="px-4 py-3 text-ink-800">{line.description}</td>
                      <td className="px-4 py-3 text-right tabular-nums text-ink-800">
                        {formatQuantity(line.quantity)}
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums text-ink-800">
                        {formatMoney(line.unitPrice, invoice.currency)}
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums text-ink-800">
                        {formatMoney(line.amount, invoice.currency)}
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums text-ink-800">
                        {formatMoney(line.taxAmount, invoice.currency)}
                        <span className="ml-1 text-xs text-ink-500">
                          ({formatPercent(line.taxRate)})
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Every figure below is the server's. Nothing here is recomputed in the browser. */}
            <dl className="space-y-1 border-t border-ink-200 p-4 text-sm">
              <div className="flex justify-between">
                <dt className="text-ink-600">Subtotal</dt>
                <dd className="tabular-nums text-ink-900">
                  {formatMoney(invoice.subtotal, invoice.currency)}
                </dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-ink-600">Tax</dt>
                <dd className="tabular-nums text-ink-900">
                  {formatMoney(invoice.taxAmount, invoice.currency)}
                </dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-ink-600">Discount</dt>
                <dd className="tabular-nums text-ink-900">
                  {formatMoney(invoice.discountAmount, invoice.currency)}
                </dd>
              </div>
              <div className="flex justify-between border-t border-ink-200 pt-1 font-medium">
                <dt className="text-ink-800">Total</dt>
                <dd className="tabular-nums text-ink-900">
                  {formatMoney(invoice.totalAmount, invoice.currency)}
                </dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-ink-600">Paid</dt>
                <dd className="tabular-nums text-ink-900">
                  {formatMoney(invoice.amountPaid, invoice.currency)}
                </dd>
              </div>
              <div className="flex justify-between font-medium">
                <dt className="text-ink-800">Outstanding</dt>
                <dd className="tabular-nums text-ink-900">
                  {formatMoney(invoice.amountDue, invoice.currency)}
                </dd>
              </div>
            </dl>
          </Card>

          <Card>
            <CardHeader title="Payments" description="Amounts received against this invoice." />
            <AsyncSection
              isLoading={payments.isPending}
              error={payments.error}
              data={payments.data}
              isEmpty={(data) => data.items.length === 0}
              onRetry={() => payments.refetch()}
              skeleton={<TableSkeleton rows={2} columns={4} />}
              empty={(
                <p className="px-4 py-6 text-center text-sm text-ink-600">
                  Nothing has been received yet.
                </p>
              )}
            >
              {(data) => (
                <ul className="divide-y divide-ink-100">
                  {data.items.map((payment) => (
                    <li key={payment.id} className="flex flex-wrap items-baseline justify-between gap-2 px-4 py-3">
                      <div>
                        <p className="text-sm font-medium text-ink-900">
                          {formatMoney(payment.amount, payment.currency)}
                        </p>
                        <p className="text-xs text-ink-500">
                          {formatDate(payment.paymentDate)}
                          <span className="text-ink-400"> · </span>
                          {humanise(payment.method)}
                          {payment.reference && ` · ${payment.reference}`}
                        </p>
                        {payment.notes && (
                          <p className="mt-1 text-sm text-ink-600">{payment.notes}</p>
                        )}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </AsyncSection>
          </Card>
        </div>
      )}

      {invoice && dialog === 'edit' && (
        <EditDraftDialog
          invoice={invoice}
          onClose={() => setDialog(null)}
          onSaved={async () => { await refresh(); setDialog(null); toast.success('Draft updated'); }}
        />
      )}
      {invoice && dialog === 'issue' && (
        <IssueDialog
          invoice={invoice}
          onClose={() => setDialog(null)}
          onIssued={async () => { await refresh(); setDialog(null); toast.success('Invoice issued'); }}
        />
      )}
      {invoice && dialog === 'cancel' && (
        <CancelDialog
          invoice={invoice}
          onClose={() => setDialog(null)}
          onCancelled={async () => {
            await refresh();
            setDialog(null);
            toast.success('Invoice cancelled');
            navigate('/invoices');
          }}
        />
      )}
      {invoice && dialog === 'payment' && (
        <PaymentDialog
          invoice={invoice}
          onClose={() => setDialog(null)}
          onRecorded={async () => { await refresh(); setDialog(null); toast.success('Payment recorded'); }}
        />
      )}
    </>
  );
}

// ------------------------------------------------------------------ dialogs

interface DraftLineRow {
  description: string;
  quantity: string;
  unitPrice: string;
  taxRate: string;
}

function EditDraftDialog({ invoice, onClose, onSaved }: {
  invoice: Invoice; onClose: () => void; onSaved: () => void | Promise<void>;
}) {
  const toast = useToast();
  const [issueDate, setIssueDate] = useState(invoice.issueDate ?? '');
  const [dueDate, setDueDate] = useState(invoice.dueDate ?? '');
  const [discount, setDiscount] = useState(invoice.discountAmount ?? '');
  const [notes, setNotes] = useState(invoice.notes ?? '');
  const [lines, setLines] = useState<DraftLineRow[]>(
    (invoice.lineItems ?? []).map((line) => ({
      description: line.description,
      quantity: line.quantity,
      unitPrice: line.unitPrice,
      taxRate: line.taxRate,
    })),
  );
  const [problem, setProblem] = useState<string | null>(null);

  const setLine = (index: number, patch: Partial<DraftLineRow>) =>
    setLines((current) => current.map((line, i) => (i === index ? { ...line, ...patch } : line)));

  const save = useMutation({
    // `version` is the optimistic lock; a stale draft is refused rather than overwritten.
    mutationFn: () => invoicesApi.update(invoice.id, {
      version: invoice.version,
      issueDate: issueDate || null,
      dueDate: dueDate || null,
      discountAmount: discount.trim() || null,
      notes: notes.trim() || null,
      lineItems: lines.map((line) => ({
        description: line.description.trim(),
        quantity: line.quantity.trim(),
        unitPrice: line.unitPrice.trim(),
        taxRate: line.taxRate.trim() || null,
      })),
    }),
    onSuccess: () => void onSaved(),
    onError: (error) => toast.error(messageFor(error)),
  });

  const submit = () => {
    if (lines.length === 0) {
      setProblem('An invoice needs at least one line.');
      return;
    }
    for (const [index, line] of lines.entries()) {
      const quantity = parseDecimal(line.quantity);
      const unitPrice = parseDecimal(line.unitPrice);
      if (line.description.trim() === '') {
        setProblem(`Line ${index + 1} needs a description.`);
        return;
      }
      if (quantity === null || quantity <= 0n || unitPrice === null || unitPrice <= 0n) {
        setProblem(`Line ${index + 1} needs a positive quantity and unit price.`);
        return;
      }
    }
    if (issueDate && dueDate && dueDate < issueDate) {
      setProblem('The due date cannot fall before the issue date.');
      return;
    }
    setProblem(null);
    save.mutate();
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title="Edit draft"
      description="Only a draft can be changed; issuing freezes the figures."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={save.isPending}>Cancel</Button>
          <Button loading={save.isPending} onClick={submit}>Save</Button>
        </>
      }
    >
      <div className="space-y-4">
        {problem && (
          <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800 ring-1 ring-inset ring-red-200">
            {problem}
          </p>
        )}

        <div className="space-y-3">
          {lines.map((line, index) => (
            <fieldset key={index} className="rounded-md border border-ink-200 p-3">
              <legend className="px-1 text-xs font-medium uppercase tracking-wide text-ink-500">
                Line {index + 1}
              </legend>
              <div className="space-y-2">
                <Field label="Description" required>
                  {({ id }) => (
                    <Input id={id} value={line.description}
                      onChange={(event) => setLine(index, { description: event.target.value })} />
                  )}
                </Field>
                <div className="grid grid-cols-3 gap-2">
                  <Field label="Qty" required>
                    {({ id }) => (
                      <Input id={id} inputMode="decimal" value={line.quantity}
                        onChange={(event) => setLine(index, { quantity: event.target.value })} />
                    )}
                  </Field>
                  <Field label="Unit price" required>
                    {({ id }) => (
                      <Input id={id} inputMode="decimal" value={line.unitPrice}
                        onChange={(event) => setLine(index, { unitPrice: event.target.value })} />
                    )}
                  </Field>
                  <Field label="Tax %">
                    {({ id }) => (
                      <Input id={id} inputMode="decimal" value={line.taxRate}
                        onChange={(event) => setLine(index, { taxRate: event.target.value })} />
                    )}
                  </Field>
                </div>
                <Button
                  type="button" size="sm" variant="ghost" disabled={lines.length === 1}
                  onClick={() => setLines((current) => current.filter((_, i) => i !== index))}
                >
                  Remove line
                </Button>
              </div>
            </fieldset>
          ))}
          <Button
            type="button" size="sm" variant="secondary"
            onClick={() => setLines((current) => [
              ...current, { description: '', quantity: '1', unitPrice: '', taxRate: '' },
            ])}
          >
            Add line
          </Button>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <Field label="Issue date">
            {({ id }) => (
              <Input id={id} type="date" value={issueDate}
                onChange={(event) => setIssueDate(event.target.value)} />
            )}
          </Field>
          <Field label="Due date">
            {({ id }) => (
              <Input id={id} type="date" value={dueDate}
                onChange={(event) => setDueDate(event.target.value)} />
            )}
          </Field>
        </div>
        <Field label="Discount">
          {({ id }) => (
            <Input id={id} inputMode="decimal" value={discount}
              onChange={(event) => setDiscount(event.target.value)} />
          )}
        </Field>
        <Field label="Notes">
          {({ id }) => (
            <Textarea id={id} rows={3} value={notes}
              onChange={(event) => setNotes(event.target.value)} />
          )}
        </Field>
      </div>
    </Dialog>
  );
}

function IssueDialog({ invoice, onClose, onIssued }: {
  invoice: Invoice; onClose: () => void; onIssued: () => void | Promise<void>;
}) {
  const toast = useToast();
  const today = new Date().toISOString().slice(0, 10);
  const [issueDate, setIssueDate] = useState(invoice.issueDate ?? today);
  const [dueDate, setDueDate] = useState(invoice.dueDate ?? '');
  const [problem, setProblem] = useState<string | null>(null);

  const issue = useMutation({
    mutationFn: () => invoicesApi.issue(invoice.id, {
      version: invoice.version,
      issueDate: issueDate || null,
      dueDate: dueDate || null,
    }),
    onSuccess: () => void onIssued(),
    onError: (error) => toast.error(messageFor(error)),
  });

  return (
    <Dialog
      open
      onClose={onClose}
      title="Issue this invoice"
      description="Issuing is one-way: the figures are frozen and the client becomes liable."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={issue.isPending}>Cancel</Button>
          <Button
            loading={issue.isPending}
            onClick={() => {
              if (!issueDate || !dueDate) {
                setProblem('An issued invoice needs both an issue date and a due date.');
                return;
              }
              if (dueDate < issueDate) {
                setProblem('The due date cannot fall before the issue date.');
                return;
              }
              setProblem(null);
              issue.mutate();
            }}
          >
            Issue
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {problem && (
          <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800 ring-1 ring-inset ring-red-200">
            {problem}
          </p>
        )}
        <p className="text-sm text-ink-600">
          Total {formatMoney(invoice.totalAmount, invoice.currency)}.
        </p>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Issue date" required>
            {({ id }) => (
              <Input id={id} type="date" value={issueDate}
                onChange={(event) => setIssueDate(event.target.value)} />
            )}
          </Field>
          <Field label="Due date" required>
            {({ id }) => (
              <Input id={id} type="date" value={dueDate}
                onChange={(event) => setDueDate(event.target.value)} />
            )}
          </Field>
        </div>
      </div>
    </Dialog>
  );
}

function CancelDialog({ invoice, onClose, onCancelled }: {
  invoice: Invoice; onClose: () => void; onCancelled: () => void | Promise<void>;
}) {
  const toast = useToast();
  const [reason, setReason] = useState('');

  const cancel = useMutation({
    mutationFn: () => invoicesApi.cancel(invoice.id, {
      version: invoice.version,
      reason: reason.trim() || null,
    }),
    onSuccess: () => void onCancelled(),
    onError: (error) => toast.error(messageFor(error)),
  });

  return (
    <Dialog
      open
      onClose={onClose}
      title="Cancel this invoice"
      description="A cancelled invoice stays on record; it is withdrawn, not deleted."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={cancel.isPending}>Keep it</Button>
          <Button variant="danger" loading={cancel.isPending} onClick={() => cancel.mutate()}>
            Cancel invoice
          </Button>
        </>
      }
    >
      <Field label="Reason" hint="Recorded on the audit trail.">
        {({ id }) => (
          <Textarea id={id} rows={3} autoFocus value={reason}
            onChange={(event) => setReason(event.target.value)} />
        )}
      </Field>
    </Dialog>
  );
}

function PaymentDialog({ invoice, onClose, onRecorded }: {
  invoice: Invoice; onClose: () => void; onRecorded: () => void | Promise<void>;
}) {
  const toast = useToast();
  const today = new Date().toISOString().slice(0, 10);
  const [amount, setAmount] = useState(invoice.amountDue);
  const [paymentDate, setPaymentDate] = useState(today);
  const [method, setMethod] = useState<PaymentMethod>('BANK_TRANSFER');
  const [reference, setReference] = useState('');
  const [notes, setNotes] = useState('');
  const [problem, setProblem] = useState<string | null>(null);

  const record = useMutation({
    mutationFn: () => invoicesApi.recordPayment(invoice.id, {
      amount: amount.trim(),
      currency: invoice.currency,
      paymentDate,
      method,
      reference: reference.trim() || null,
      notes: notes.trim() || null,
    }),
    onSuccess: () => void onRecorded(),
    onError: (error) => toast.error(messageFor(error)),
  });

  const submit = () => {
    const value = parseDecimal(amount);
    const outstanding = parseDecimal(invoice.amountDue);
    if (value === null || value <= 0n) {
      setProblem('Enter a positive amount.');
      return;
    }
    // The backend refuses an overpayment anyway; catching it here keeps somebody from
    // filling in a form that was never going to be accepted.
    if (outstanding !== null && value > outstanding) {
      setProblem(
        `That is more than the ${formatMoney(invoice.amountDue, invoice.currency)} outstanding.`,
      );
      return;
    }
    setProblem(null);
    record.mutate();
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title="Record a payment"
      description="Money already received. This does not take a payment."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={record.isPending}>Cancel</Button>
          <Button loading={record.isPending} onClick={submit}>Record</Button>
        </>
      }
    >
      <div className="space-y-4">
        {problem && (
          <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800 ring-1 ring-inset ring-red-200">
            {problem}
          </p>
        )}
        <p className="text-sm text-ink-600">
          Outstanding: <span className="font-medium text-ink-900">
            {formatMoney(invoice.amountDue, invoice.currency)}
          </span>
        </p>
        <div className="grid grid-cols-2 gap-3">
          <Field label={`Amount (${invoice.currency})`} required>
            {({ id }) => (
              <Input id={id} autoFocus inputMode="decimal" value={amount}
                onChange={(event) => setAmount(event.target.value)} />
            )}
          </Field>
          <Field label="Date received" required>
            {({ id }) => (
              <Input id={id} type="date" max={today} value={paymentDate}
                onChange={(event) => setPaymentDate(event.target.value)} />
            )}
          </Field>
        </div>
        <Field label="Method" required>
          {({ id }) => (
            <Select id={id} value={method}
              onChange={(event) => setMethod(event.target.value as PaymentMethod)}>
              {METHODS.map((value) => (
                <option key={value} value={value}>{humanise(value)}</option>
              ))}
            </Select>
          )}
        </Field>
        <Field label="Reference"
          hint="A transaction or cheque number — never card or account details.">
          {({ id }) => (
            <Input id={id} value={reference} maxLength={100}
              onChange={(event) => setReference(event.target.value)} />
          )}
        </Field>
        <Field label="Notes">
          {({ id }) => (
            <Textarea id={id} rows={2} value={notes}
              onChange={(event) => setNotes(event.target.value)} />
          )}
        </Field>
      </div>
    </Dialog>
  );
}
