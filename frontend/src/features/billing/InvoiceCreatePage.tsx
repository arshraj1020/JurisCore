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
import { Button, Card, CardHeader, Field, Input, Select, Textarea } from '@/components/ui/primitives';
import { estimateTotals, parseDecimal } from '@/lib/money';
import { fieldErrorsOf, messageFor } from '@/lib/api/errors';

const positiveDecimal = z.string().trim().min(1, 'Required').refine(
  (value) => { const parsed = parseDecimal(value); return parsed !== null && parsed > 0n; },
  'Enter a positive number',
);
const nonNegativeDecimal = z.string().trim().refine(
  (value) => {
    if (value === '') return true;
    const parsed = parseDecimal(value);
    return parsed !== null && parsed >= 0n;
  },
  'Enter a number of zero or more',
);
const MAX_RATE = parseDecimal('100') ?? 0n;
const ratePercent = z.string().trim().refine(
  (value) => {
    if (value === '') return true;
    const parsed = parseDecimal(value);
    return parsed !== null && parsed >= 0n && parsed <= MAX_RATE;
  },
  'Enter a rate between 0 and 100',
);

const baseSchema = z.object({
  clientId: z.string().uuid('Choose a client'),
  caseId: z.string(),
  currency: z.string().trim().regex(/^([A-Za-z]{3})?$/, 'Use a three-letter code'),
  issueDate: z.string(),
  dueDate: z.string(),
  discountAmount: nonNegativeDecimal,
  notes: z.string().max(2000),
  lineItems: z.array(z.object({
    description: z.string().trim().min(1, 'Describe the work').max(500),
    quantity: positiveDecimal,
    unitPrice: positiveDecimal,
    taxRate: ratePercent,
  })).min(1, 'An invoice needs at least one line'),
});

const schema = baseSchema.refine(
  (values) => !values.issueDate || !values.dueDate || values.dueDate >= values.issueDate,
  { path: ['dueDate'], message: 'The due date cannot fall before the issue date' },
);

type Values = z.infer<typeof schema>;

const EMPTY_LINE = { description: '', quantity: '1', unitPrice: '', taxRate: '' };

export function InvoiceCreatePage() {
  const navigate = useNavigate();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [searchParams] = useSearchParams();

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
  const currencyLabel = (watched.currency ?? '').trim().toUpperCase();

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

  const submit = handleSubmit(async (values) => {
    try {
      await create.mutateAsync(values);
    } catch (error) {
      for (const [field, message] of Object.entries(fieldErrorsOf(error))) {
        if (field in baseSchema.shape) {
          setError(field as keyof Values, { message });
        }
      }
      setError('root', { message: messageFor(error) });
    }
  });

  return (
    <>
      <PageHeader
        title="New invoice"
        description="Drafts are private until they are issued."
        breadcrumbs={[{ label: 'Invoices', to: '/invoices' }, { label: 'New invoice' }]}
      />

      <form onSubmit={submit} noValidate className="space-y-4">
        {errors.root && (
          <div role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800 ring-1 ring-inset ring-red-200">
            {errors.root.message}
          </div>
        )}

        <Card>
          <CardHeader title="Who is being billed" />
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
            description="Tax is applied per line, at the rate that line carries."
            actions={(
              <Button type="button" size="sm" variant="secondary"
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
              <fieldset key={field.id} className="rounded-md border border-ink-200 p-3">
                <legend className="px-1 text-xs font-medium uppercase tracking-wide text-ink-500">
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
                      type="button" variant="ghost" size="sm"
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
          <CardHeader title="Dates, discount and notes" />
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
            description="Indicative only — the invoice is totalled by the server when it is saved."
          />
          <dl className="space-y-1 p-4 text-sm">
            <div className="flex justify-between">
              <dt className="text-ink-600">Subtotal</dt>
              <dd className="tabular-nums text-ink-900">{currencyLabel} {estimate.subtotal}</dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-ink-600">Tax</dt>
              <dd className="tabular-nums text-ink-900">{currencyLabel} {estimate.taxAmount}</dd>
            </div>
            <div className="flex justify-between border-t border-ink-200 pt-1 font-medium">
              <dt className="text-ink-800">Estimated total</dt>
              <dd className="tabular-nums text-ink-900">{currencyLabel} {estimate.total}</dd>
            </div>
            {estimate.partial && (
              <p className="pt-1 text-xs text-amber-700">
                Some lines are incomplete, so this estimate covers only part of the invoice.
              </p>
            )}
          </dl>
        </Card>

        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={() => navigate('/invoices')}
            disabled={isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" loading={isSubmitting}>Save draft</Button>
        </div>
      </form>
    </>
  );
}
