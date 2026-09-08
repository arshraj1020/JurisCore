import { useEffect } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { billingProfileApi } from './api';
import { keys } from '@/lib/api/queryKeys';
import { useUnsavedChangesWarning } from '@/lib/api/hooks';
import { useToast } from '@/components/ui/Toast';
import { PageHeader } from '@/components/ui/PageHeader';
import {
  Button, Card, CardHeader, Field, Input, Textarea,
} from '@/components/ui/primitives';
import { ErrorState, TableSkeleton } from '@/components/ui/states';
import { applyServerErrors } from '@/lib/api/formErrors';
import { FormErrorSummary } from '@/components/ui/FormErrorSummary';
import { LIMITS, currencyCode, invoicePrefix, optionalText } from '@/lib/validation';

// Mirrors UpdateBillingProfileRequest. Three of these were wrong: legalName is 200 not
// 255, billingPhone is 40 not 32, and invoicePrefix is a @Pattern allowing up to twelve
// upper-case characters — the old 1-10 rule refused prefixes the platform accepts.
const schema = z.object({
  legalName: optionalText(LIMITS.NAME),
  taxRegistration: optionalText(LIMITS.TAX_REGISTRATION),
  billingEmail: z.string().max(LIMITS.EMAIL).refine(
    (value) => value === '' || z.string().email().safeParse(value).success,
    'Enter a valid email address',
  ),
  billingPhone: optionalText(LIMITS.PHONE),
  addressLine1: optionalText(LIMITS.ADDRESS),
  addressLine2: optionalText(LIMITS.ADDRESS),
  city: optionalText(LIMITS.LOCALITY),
  state: optionalText(LIMITS.LOCALITY),
  country: optionalText(LIMITS.LOCALITY),
  postalCode: optionalText(LIMITS.POSTAL_CODE),
  defaultCurrency: currencyCode,
  invoicePrefix,
  invoiceNotes: optionalText(LIMITS.NOTES),
});
type Values = z.infer<typeof schema>;

const BLANK: Values = {
  legalName: '', taxRegistration: '', billingEmail: '', billingPhone: '',
  addressLine1: '', addressLine2: '', city: '', state: '', country: '', postalCode: '',
  defaultCurrency: 'INR', invoicePrefix: 'INV', invoiceNotes: '',
};

export function BillingSettingsPage() {
  const toast = useToast();
  const queryClient = useQueryClient();

  const query = useQuery({
    queryKey: keys.billingProfile,
    queryFn: () => billingProfileApi.current(),
  });

  const {
    register, handleSubmit, reset, setError, formState: { errors, isSubmitting, isDirty },
  } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: BLANK });

  useUnsavedChangesWarning(isDirty && !isSubmitting);

  // The form is populated once the profile arrives; `reset` also clears the dirty flag,
  // so the unsaved-changes guard does not fire on a form nobody has touched.
  useEffect(() => {
    const profile = query.data;
    if (!profile) return;
    reset({
      legalName: profile.legalName ?? '',
      taxRegistration: profile.taxRegistration ?? '',
      billingEmail: profile.billingEmail ?? '',
      billingPhone: profile.billingPhone ?? '',
      addressLine1: profile.addressLine1 ?? '',
      addressLine2: profile.addressLine2 ?? '',
      city: profile.city ?? '',
      state: profile.state ?? '',
      country: profile.country ?? '',
      postalCode: profile.postalCode ?? '',
      defaultCurrency: profile.defaultCurrency,
      invoicePrefix: profile.invoicePrefix,
      invoiceNotes: profile.invoiceNotes ?? '',
    });
  }, [query.data, reset]);

  const save = useMutation({
    mutationFn: (values: Values) => billingProfileApi.update({
      legalName: values.legalName.trim() || null,
      taxRegistration: values.taxRegistration.trim() || null,
      billingEmail: values.billingEmail.trim() || null,
      billingPhone: values.billingPhone.trim() || null,
      addressLine1: values.addressLine1.trim() || null,
      addressLine2: values.addressLine2.trim() || null,
      city: values.city.trim() || null,
      state: values.state.trim() || null,
      country: values.country.trim() || null,
      postalCode: values.postalCode.trim() || null,
      defaultCurrency: values.defaultCurrency.trim().toUpperCase(),
      invoicePrefix: values.invoicePrefix.trim().toUpperCase(),
      invoiceNotes: values.invoiceNotes.trim() || null,
      // The optimistic lock, when the profile already exists.
      version: query.data?.version ?? null,
    }),
    onSuccess: async (profile) => {
      queryClient.setQueryData(keys.billingProfile, profile);
      await queryClient.invalidateQueries({ queryKey: keys.billingProfile });
      toast.success('Billing details saved');
    },
  });

  const submit = handleSubmit(async (values) => {
    try {
      await save.mutateAsync(values);
    } catch (error) {
      applyServerErrors(error, setError, Object.keys(schema.shape));
    }
  });

  return (
    <>
      <PageHeader
        title="Billing settings"
        description="What appears on the firm's invoices, and the defaults new invoices start from."
      />

      {query.isPending ? (
        <Card><TableSkeleton rows={6} columns={2} /></Card>
      ) : query.error ? (
        <Card><ErrorState error={query.error} onRetry={() => query.refetch()} /></Card>
      ) : (
        <form onSubmit={submit} noValidate className="space-y-4">
          <FormErrorSummary message={errors.root?.message} />

          <Card>
            <CardHeader title="The firm" icon="courts"
              description="How the firm is identified on an invoice." />
            <div className="grid gap-4 p-4 sm:grid-cols-2">
              <Field label="Legal name" error={errors.legalName?.message}>
                {({ id, describedBy, invalid }) => (
                  <Input id={id} aria-describedby={describedBy} invalid={invalid}
                    {...register('legalName')} />
                )}
              </Field>
              <Field label="Tax registration" error={errors.taxRegistration?.message}
                hint="Printed as supplied. JurisCore does not validate or file it.">
                {({ id, describedBy, invalid }) => (
                  <Input id={id} aria-describedby={describedBy} invalid={invalid}
                    {...register('taxRegistration')} />
                )}
              </Field>
              <Field label="Billing email" error={errors.billingEmail?.message}>
                {({ id, describedBy, invalid }) => (
                  <Input id={id} type="email" aria-describedby={describedBy} invalid={invalid}
                    {...register('billingEmail')} />
                )}
              </Field>
              <Field label="Billing phone" error={errors.billingPhone?.message}>
                {({ id, describedBy, invalid }) => (
                  <Input id={id} aria-describedby={describedBy} invalid={invalid}
                    {...register('billingPhone')} />
                )}
              </Field>
            </div>
          </Card>

          <Card>
            <CardHeader title="Address" icon="clients" />
            <div className="grid gap-4 p-4 sm:grid-cols-2">
              <div className="sm:col-span-2">
                <Field label="Address line 1" error={errors.addressLine1?.message}>
                  {({ id, describedBy, invalid }) => (
                    <Input id={id} aria-describedby={describedBy} invalid={invalid}
                      {...register('addressLine1')} />
                  )}
                </Field>
              </div>
              <div className="sm:col-span-2">
                <Field label="Address line 2" error={errors.addressLine2?.message}>
                  {({ id, describedBy, invalid }) => (
                    <Input id={id} aria-describedby={describedBy} invalid={invalid}
                      {...register('addressLine2')} />
                  )}
                </Field>
              </div>
              <Field label="City" error={errors.city?.message}>
                {({ id, describedBy, invalid }) => (
                  <Input id={id} aria-describedby={describedBy} invalid={invalid}
                    {...register('city')} />
                )}
              </Field>
              <Field label="State" error={errors.state?.message}>
                {({ id, describedBy, invalid }) => (
                  <Input id={id} aria-describedby={describedBy} invalid={invalid}
                    {...register('state')} />
                )}
              </Field>
              <Field label="Country" error={errors.country?.message}>
                {({ id, describedBy, invalid }) => (
                  <Input id={id} aria-describedby={describedBy} invalid={invalid}
                    {...register('country')} />
                )}
              </Field>
              <Field label="Postal code" error={errors.postalCode?.message}>
                {({ id, describedBy, invalid }) => (
                  <Input id={id} aria-describedby={describedBy} invalid={invalid}
                    {...register('postalCode')} />
                )}
              </Field>
            </div>
          </Card>

          <Card>
            <CardHeader
              title="Invoice defaults"
              icon="invoices"
              description="Applied to new invoices; existing invoices keep what they were raised with."
            />
            <div className="grid gap-4 p-4 sm:grid-cols-2">
              <Field label="Default currency" error={errors.defaultCurrency?.message} required>
                {({ id, describedBy, invalid }) => (
                  <Input id={id} maxLength={3} aria-describedby={describedBy} invalid={invalid}
                    {...register('defaultCurrency')} />
                )}
              </Field>
              <Field label="Invoice number prefix" error={errors.invoicePrefix?.message} required
                hint="Numbers are issued by the system; changing the prefix does not renumber past invoices.">
                {({ id, describedBy, invalid }) => (
                  <Input id={id} maxLength={10} aria-describedby={describedBy} invalid={invalid}
                    {...register('invoicePrefix')} />
                )}
              </Field>
              <div className="sm:col-span-2">
                <Field label="Standing notes" error={errors.invoiceNotes?.message}
                  hint="Payment terms or bank details you want on every invoice.">
                  {({ id, describedBy, invalid }) => (
                    <Textarea id={id} rows={4} aria-describedby={describedBy} invalid={invalid}
                      {...register('invoiceNotes')} />
                  )}
                </Field>
              </div>
            </div>
          </Card>

          <div className="flex items-center justify-end gap-3 pb-2">
            {isDirty && <p className="text-xs text-ink-500">You have unsaved changes.</p>}
            <Button type="submit" loading={isSubmitting} disabled={!isDirty}>Save</Button>
          </div>
        </form>
      )}
    </>
  );
}
