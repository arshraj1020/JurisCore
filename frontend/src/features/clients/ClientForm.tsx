import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Button, Field, Input, Select, Textarea } from '@/components/ui/primitives';
import { fieldErrorsOf, messageFor } from '@/lib/api/errors';
import { useUnsavedChangesWarning } from '@/lib/api/hooks';
import type { Client, ClientRequest } from '@/types/api';

const schema = z.object({
  displayName: z.string().min(1, 'Enter a name').max(200),
  clientType: z.enum(['INDIVIDUAL', 'CORPORATE']),
  email: z.string().email('That does not look like an email address').max(255).or(z.literal('')),
  phone: z.string().max(40).or(z.literal('')),
  addressLine1: z.string().max(255).or(z.literal('')),
  addressLine2: z.string().max(255).or(z.literal('')),
  city: z.string().max(120).or(z.literal('')),
  state: z.string().max(120).or(z.literal('')),
  country: z.string().max(120).or(z.literal('')),
  postalCode: z.string().max(20).or(z.literal('')),
  notes: z.string().max(2000).or(z.literal('')),
});
type Values = z.infer<typeof schema>;

/** Empty strings are what a blank input produces; the backend wants absence, not "". */
function toRequest(values: Values): ClientRequest {
  const blankToNull = (value: string) => (value.trim() === '' ? null : value.trim());
  return {
    displayName: values.displayName.trim(),
    clientType: values.clientType,
    email: blankToNull(values.email),
    phone: blankToNull(values.phone),
    addressLine1: blankToNull(values.addressLine1),
    addressLine2: blankToNull(values.addressLine2),
    city: blankToNull(values.city),
    state: blankToNull(values.state),
    country: blankToNull(values.country),
    postalCode: blankToNull(values.postalCode),
    notes: blankToNull(values.notes),
  };
}

export function ClientForm({ client, onSubmit, onCancel, submitLabel }: {
  client?: Client;
  onSubmit: (body: ClientRequest) => Promise<unknown>;
  onCancel: () => void;
  submitLabel: string;
}) {
  const {
    register, handleSubmit, setError,
    formState: { errors, isSubmitting, isDirty, isSubmitSuccessful },
  } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      displayName: client?.displayName ?? '',
      clientType: client?.clientType ?? 'INDIVIDUAL',
      email: client?.email ?? '',
      phone: client?.phone ?? '',
      addressLine1: client?.addressLine1 ?? '',
      addressLine2: client?.addressLine2 ?? '',
      city: client?.city ?? '',
      state: client?.state ?? '',
      country: client?.country ?? '',
      postalCode: client?.postalCode ?? '',
      notes: client?.notes ?? '',
    },
  });

  useUnsavedChangesWarning(isDirty && !isSubmitSuccessful);

  const submit = handleSubmit(async (values) => {
    try {
      await onSubmit(toRequest(values));
    } catch (error) {
      for (const [field, message] of Object.entries(fieldErrorsOf(error))) {
        if (field in schema.shape) setError(field as keyof Values, { message });
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

      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Name" error={errors.displayName?.message} required>
          {({ id, describedBy, invalid }) => (
            <Input id={id} autoFocus aria-describedby={describedBy} invalid={invalid}
              {...register('displayName')} />
          )}
        </Field>
        <Field label="Type" error={errors.clientType?.message} required>
          {({ id, describedBy, invalid }) => (
            <Select id={id} aria-describedby={describedBy} invalid={invalid} {...register('clientType')}>
              <option value="INDIVIDUAL">Individual</option>
              <option value="CORPORATE">Corporate</option>
            </Select>
          )}
        </Field>
        <Field label="Email" error={errors.email?.message}>
          {({ id, describedBy, invalid }) => (
            <Input id={id} type="email" aria-describedby={describedBy} invalid={invalid}
              {...register('email')} />
          )}
        </Field>
        <Field label="Phone" error={errors.phone?.message}>
          {({ id, describedBy, invalid }) => (
            <Input id={id} aria-describedby={describedBy} invalid={invalid} {...register('phone')} />
          )}
        </Field>
      </div>

      <fieldset className="space-y-4">
        <legend className="text-sm font-medium text-ink-800">Address</legend>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Address line 1" error={errors.addressLine1?.message}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} invalid={invalid} {...register('addressLine1')} />
            )}
          </Field>
          <Field label="Address line 2" error={errors.addressLine2?.message}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} invalid={invalid} {...register('addressLine2')} />
            )}
          </Field>
          <Field label="City" error={errors.city?.message}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} invalid={invalid} {...register('city')} />
            )}
          </Field>
          <Field label="State" error={errors.state?.message}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} invalid={invalid} {...register('state')} />
            )}
          </Field>
          <Field label="Country" error={errors.country?.message}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} invalid={invalid} {...register('country')} />
            )}
          </Field>
          <Field label="Postal code" error={errors.postalCode?.message}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} invalid={invalid} {...register('postalCode')} />
            )}
          </Field>
        </div>
      </fieldset>

      <Field label="Notes" error={errors.notes?.message}>
        {({ id, describedBy, invalid }) => (
          <Textarea id={id} rows={3} aria-describedby={describedBy} invalid={invalid}
            {...register('notes')} />
        )}
      </Field>

      <div className="flex justify-end gap-2 pt-2">
        <Button type="button" variant="secondary" onClick={onCancel} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" loading={isSubmitting}>{submitLabel}</Button>
      </div>
    </form>
  );
}
