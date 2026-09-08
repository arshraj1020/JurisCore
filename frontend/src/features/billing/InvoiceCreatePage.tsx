import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useFieldArray, useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { invoicesApi } from './api';
import { clientsApi } from '@/features/clients/api';
import { casesApi } from '@/features/cases/api';
import { keys } from '@/lib/api/queryKeys';
import { useUnsavedChangesWarning } from '@/lib/api/hooks';
import { useToast } from '@/components/ui/Toast';
import { PageHeader } from '@/components/ui/PageHeader';
import {
  Alert, Button, Card, CardBody, CardHeader, Field, Input, Select, Textarea,
} from '@/components/ui/primitives';
import { estimateTotals } from '@/lib/money';
import { applyServerErrors } from '@/lib/api/formErrors';
import { FormErrorSummary } from '@/components/ui/FormErrorSummary';
import {
  LIMITS, boundedText, compareDecimals, invoiceDiscount, invoiceQuantity, invoiceTaxRate,
  invoiceUnitPrice, optionalCurrencyCode, optionalText,
} from '@/lib/validation';

/**
 * The bounds are the backend's, from `CreateInvoiceRequest` and `InvoiceLineItemRequest` —
 * see `@/lib/validation`. What changed here is not strictness for its own sake: `currency`
 * used to accept any three letters, so `ZZZ` reached the server, and the field is a plain
 * text input, so `122` passed the old `[A-Za-z]{3}` check only by being rejected — with an
 * error the form never displayed.
 */
const baseSchema = z.object({
  clientId: z.string().uuid('Choose a client'),
  caseId: z.string(),
  currency: optionalCurrencyCode,
  issueDate: z.string(),
  dueDate: z.string(),
  discountAmount: invoiceDiscount,
  notes: optionalText(LIMITS.NOTES),
  lineItems: z.array(z.object({
    description: boundedText(LIMITS.LINE_DESCRIPTION, 'Describe the work'),
    quantity: invoiceQuantity,
    unitPrice: invoiceUnitPrice,
    taxRate: invoiceTaxRate,
  })).min(1, 'An invoice needs at least one line'),
});

const schema = baseSchema
  .refine(
    (values) => !values.issueDate || !values.dueDate || values.dueDate >= values.issueDate,
    { path: ['dueDate'], message: 'The due date cannot fall before the issue date' },
  )
  /**
   * `InvoiceCalculator` refuses a discount larger than the gross, and it is right to: the
   * alternative is a negative invoice. Checking it here does not move the decision — the
   * server still recomputes the gross from the lines and re-applies the rule — it stops the
   * form showing a *negative estimated total*, which reads as though the firm owes the
   * client, and it explains the refusal before the round trip rather than after it.
   */
  .refine(
    (values) => {
      const discount = values.discountAmount.trim();
      if (discount === '') return true;
      const gross = estimateTotals(values.lineItems, '0');
      // A partial estimate means some line could not be read, so there is no gross to
      // compare against yet; the line's own error is the one worth showing.
      if (gross.partial) return true;
      return compareDecimals(discount, gross.total) <= 0;
    },
    {
      path: ['discountAmount'],
      message: 'The discount is more than the invoice comes to. The server will refuse it.',
    },
  );

type Values = z.infer<typeof schema>;

const EMPTY_LINE = { description: '', quantity: '1', unitPrice: '', taxRate: '' };

/**
 * Flattens react-hook-form's error tree into messages a person can read.
 *
 * The tree is nested to match the form's shape (`lineItems[2].unitPrice`), and a summary
 * that said "lineItems has an error" would be worse than saying nothing.
 */
function collectMessages(errors: unknown, path: string[] = []): string[] {
  if (!errors || typeof errors !== 'object') return [];
  const node = errors as Record<string, unknown> & { message?: unknown };
  if (typeof node.message === 'string' && node.message !== '') {
    const line = path.findIndex((part) => part === 'lineItems');
    const label = line >= 0 && path[line + 1] !== undefined
      ? `Line ${Number(path[line + 1]) + 1}: ${node.message}`
      : node.message;
    return [label];
  }
  return Object.entries(node)
    .filter(([key]) => key !== 'ref' && key !== 'type')
    .flatMap(([key, value]) => collectMessages(value, [...path, key]));
}

export function InvoiceCreatePage() {
  const navigate = useNavigate();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [searchParams] = useSearchParams();

  const [blockedBy, setBlockedBy] = useState<string[]>([]);

  const {
    register, handleSubmit, control, setError, formState: { errors, isSubmitting, isDirty },
  } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      clientId: searchParams.get('clientId') ?? '',
      caseId: searchParams.get('caseId') ?? '',
      currency: '',
      issueDate: '',
      dueDate: '',
      discountAmount: '',
      notes: '',
      lineItems: [{ ...EMPTY_LINE }],
    },
  });

  const { fields, append, remove } = useFieldArray({ control, name: 'lineItems' });
  const watched = useWatch({ control });
  useUnsavedChangesWarning(isDirty && !isSubmitting);

  const clients = useQuery({
    queryKey: keys.clients.list({ all: true }),
    queryFn: () => clientsApi.list({ size: 200 }),
  });

  const selectedClient = watched.clientId ?? '';
  const cases = useQuery({
    queryKey: keys.cases.list({ clientId: selectedClient, forInvoice: true }),
    queryFn: () => casesApi.list({ clientId: selectedClient, size: 200 }),
    enabled: selectedClient !== '',
  });

  /**
   * A preview, not the invoice. The server recomputes every figure on save and its answer
   * is the one that is stored and billed — see `src/lib/money.ts`.
   */
  const estimate = estimateTotals(
    (watched.lineItems ?? []).map((line) => ({
      quantity: line?.quantity ?? '',
      unitPrice: line?.unitPrice ?? '',
      taxRate: line?.taxRate ?? '',
    })),
    watched.discountAmount ?? '',
  );
  /**
   * Whether the typed discount is larger than the invoice comes to.
   *
   * Worth its own variable because the estimate must not render a *negative* total. A
   * figure like "-5,000.00" beside the words "Estimated total" reads as though the firm
   * owes the client money, which is both alarming and impossible: `InvoiceCalculator`
   * refuses the discount outright, so no such invoice can exist. The card shows the
   * undiscounted figure and says the discount is the problem.
   */
  const grossEstimate = estimateTotals(
    (watched.lineItems ?? []).map((line) => ({
      quantity: line?.quantity ?? '',
      unitPrice: line?.unitPrice ?? '',
      taxRate: line?.taxRate ?? '',
    })),
    '0',
  );
  const typedDiscount = (watched.discountAmount ?? '').trim();
  const discountExceedsGross = typedDiscount !== ''
    && !grossEstimate.partial
    && /^\d+(\.\d+)?$/.test(typedDiscount)
    && compareDecimals(typedDiscount, grossEstimate.total) > 0;

  const currencyLabel = (watched.currency ?? '').trim().toUpperCase();
  // Nothing has been typed yet, so "some lines are incomplete" is not news.
  const started = (watched.lineItems ?? []).some(
    (line) => (line?.description ?? '') !== '' || (line?.unitPrice ?? '') !== '',
  );

  const create = useMutation({
    mutationFn: (values: Values) => invoicesApi.create({
      clientId: values.clientId,
      caseId: values.caseId || null,
      // Blank means "whatever the firm's billing profile says" — the backend fills it in.
      currency: values.currency.trim().toUpperCase() || null,
      issueDate: values.issueDate || null,
      dueDate: values.dueDate || null,
      discountAmount: values.discountAmount.trim() || null,
      notes: values.notes.trim() || null,
      lineItems: values.lineItems.map((line) => ({
        description: line.description.trim(),
        quantity: line.quantity.trim(),
        unitPrice: line.unitPrice.trim(),
        taxRate: line.taxRate.trim() || null,
      })),
    }),
    onSuccess: async (invoice) => {
      await queryClient.invalidateQueries({ queryKey: keys.invoices.all });
      toast.success(`${invoice.invoiceNumber} drafted`);
      navigate(`/invoices/${invoice.id}`, { replace: true });
    },
  });

  /**
   * "Save draft" used to appear to do nothing.
   *
   * When the resolver rejected a field that was scrolled out of view — a line item's unit
   * price, three cards down — react-hook-form quietly refused to submit and the button
   * looked broken. The `onInvalid` branch below is the fix: it collects every message into
   * one summary rendered right next to the button that was just pressed, so the reason is
   * where the user is already looking. `shouldFocusError` (the default) still moves focus
   * to the first invalid control.
   */
  const submit = handleSubmit(async (values) => {
    setBlockedBy([]);
    try {
      await create.mutateAsync(values);
    } catch (error) {
      // Nested violations (`lineItems[0].quantity`) do not match a top-level field, so
      // they go to the summary with their line number rather than being dropped.
      applyServerErrors(error, setError, Object.keys(baseSchema.shape));
    }
  }, (invalid) => setBlockedBy(collectMessages(invalid)));

  return (
    <>
      <PageHeader
        title="New invoice"
        description="Drafts are private until they are issued."
        breadcrumbs={[{ label: 'Invoices', to: '/invoices' }, { label: 'New invoice' }]}
      />

      <form onSubmit={submit} noValidate className="space-y-4">
        {errors.root && <Alert tone="danger" live>{errors.root.message}</Alert>}

        <Card>
          <CardHeader title="Who is being billed" icon="clients" />
          <div className="grid gap-4 p-4 sm:grid-cols-2">
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
            <Field label="Matter" error={errors.caseId?.message}
              hint="Optional — leave blank for work not tied to one matter.">
              {({ id, describedBy, invalid }) => (
                <Select id={id} aria-describedby={describedBy} invalid={invalid}
                  disabled={selectedClient === '' || cases.isPending} {...register('caseId')}>
                  <option value="">No specific matter</option>
                  {cases.data?.items.map((legalCase) => (
                    <option key={legalCase.id} value={legalCase.id}>
                      {legalCase.caseNumber} — {legalCase.title}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
          </div>
        </Card>

        <Card>
          <CardHeader
            title="Lines"
            icon="invoices"
            description="Tax is applied per line, at the rate that line carries."
            actions={(
              <Button size="sm" variant="secondary" icon="plus"
                onClick={() => append({ ...EMPTY_LINE })}>
                Add line
              </Button>
            )}
          />
          <div className="space-y-4 p-4">
            {errors.lineItems?.message && (
              <p role="alert" className="text-sm text-red-700">{errors.lineItems.message}</p>
            )}
            {fields.map((field, index) => (
              <fieldset key={field.id} className="rounded-md border border-ink-200 bg-ink-50/40 p-3">
                <legend className="px-1 text-2xs font-semibold uppercase tracking-wide text-ink-500">
                  Line {index + 1}
                </legend>
                <div className="grid gap-3 sm:grid-cols-12">
                  <div className="sm:col-span-12">
                    <Field label="Description"
                      error={errors.lineItems?.[index]?.description?.message} required>
                      {({ id, describedBy, invalid }) => (
                        <Input id={id} aria-describedby={describedBy} invalid={invalid}
                          {...register(`lineItems.${index}.description`)} />
                      )}
                    </Field>
                  </div>
                  <div className="sm:col-span-3">
                    <Field label="Quantity"
                      error={errors.lineItems?.[index]?.quantity?.message} required>
                      {({ id, describedBy, invalid }) => (
                        <Input id={id} inputMode="decimal" aria-describedby={describedBy}
                          invalid={invalid} {...register(`lineItems.${index}.quantity`)} />
                      )}
                    </Field>
                  </div>
                  <div className="sm:col-span-4">
                    <Field label="Unit price"
                      error={errors.lineItems?.[index]?.unitPrice?.message} required>
                      {({ id, describedBy, invalid }) => (
                        <Input id={id} inputMode="decimal" aria-describedby={describedBy}
                          invalid={invalid} {...register(`lineItems.${index}.unitPrice`)} />
                      )}
                    </Field>
                  </div>
                  <div className="sm:col-span-3">
                    <Field label="Tax %" error={errors.lineItems?.[index]?.taxRate?.message}>
                      {({ id, describedBy, invalid }) => (
                        <Input id={id} inputMode="decimal" aria-describedby={describedBy}
                          invalid={invalid} {...register(`lineItems.${index}.taxRate`)} />
                      )}
                    </Field>
                  </div>
                  <div className="flex items-end sm:col-span-2">
                    <Button
                      variant="ghost" size="sm" icon="trash"
                      disabled={fields.length === 1}
                      onClick={() => remove(index)}
                    >
                      Remove
                    </Button>
                  </div>
                </div>
              </fieldset>
            ))}
          </div>
        </Card>

        <Card>
          <CardHeader title="Dates, discount and notes" icon="calendar" />
          <div className="grid gap-4 p-4 sm:grid-cols-2">
            <Field label="Issue date" error={errors.issueDate?.message}
              hint="Optional on a draft; required to issue.">
              {({ id, describedBy, invalid }) => (
                <Input id={id} type="date" aria-describedby={describedBy} invalid={invalid}
                  {...register('issueDate')} />
              )}
            </Field>
            <Field label="Due date" error={errors.dueDate?.message}>
              {({ id, describedBy, invalid }) => (
                <Input id={id} type="date" aria-describedby={describedBy} invalid={invalid}
                  {...register('dueDate')} />
              )}
            </Field>
            <Field label="Currency" error={errors.currency?.message}
              hint="Leave blank to use the firm's default.">
              {({ id, describedBy, invalid }) => (
                <Input id={id} maxLength={3} placeholder="INR" aria-describedby={describedBy}
                  invalid={invalid} {...register('currency')} />
              )}
            </Field>
            <Field label="Discount" error={errors.discountAmount?.message}
              hint="A flat amount off the total.">
              {({ id, describedBy, invalid }) => (
                <Input id={id} inputMode="decimal" aria-describedby={describedBy}
                  invalid={invalid} {...register('discountAmount')} />
              )}
            </Field>
            <div className="sm:col-span-2">
              <Field label="Notes" error={errors.notes?.message}>
                {({ id, describedBy, invalid }) => (
                  <Textarea id={id} rows={3} aria-describedby={describedBy} invalid={invalid}
                    {...register('notes')} />
                )}
              </Field>
            </div>
          </div>
        </Card>

        <Card>
          <CardHeader
            title="Estimate"
            icon="money"
            description="Indicative only — the server totals the invoice when it is saved, and its figures are the ones that count."
          />
          <CardBody className="flex justify-end">
            <dl className="w-full max-w-xs space-y-1.5 text-sm">
              <div className="flex items-baseline justify-between gap-6">
                <dt className="text-ink-600">Subtotal</dt>
                <dd className="tabular-nums text-ink-800">{currencyLabel} {estimate.subtotal}</dd>
              </div>
              <div className="flex items-baseline justify-between gap-6">
                <dt className="text-ink-600">Tax</dt>
                <dd className="tabular-nums text-ink-800">{currencyLabel} {estimate.taxAmount}</dd>
              </div>
              <div className="flex items-baseline justify-between gap-6 border-t border-ink-300 pt-1.5">
                <dt className="font-medium text-ink-800">Estimated total</dt>
                <dd className="font-semibold tabular-nums text-ink-900">
                  {currencyLabel} {discountExceedsGross ? grossEstimate.total : estimate.total}
                </dd>
              </div>
              {discountExceedsGross && (
                <p className="pt-1 text-xs text-amber-700">
                  The discount is more than the invoice comes to, so it is not applied here.
                  The server refuses a discount larger than the total.
                </p>
              )}
              {estimate.partial && started && (
                <p className="pt-1 text-xs text-amber-700">
                  Some lines are incomplete, so this estimate covers only part of the invoice.
                </p>
              )}
            </dl>
          </CardBody>
        </Card>

        {blockedBy.length > 0 && (
          <FormErrorSummary message={blockedBy.join('\n')} />
        )}

        <div className="flex flex-wrap justify-end gap-2 pb-2">
          <Button variant="secondary" onClick={() => navigate('/invoices')}
            disabled={isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" loading={isSubmitting}>Save draft</Button>
        </div>
      </form>
    </>
  );
}
