import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Button, Field, Input, Select, Textarea } from '@/components/ui/primitives';
import { applyServerErrors } from '@/lib/api/formErrors';
import { FormErrorSummary } from '@/components/ui/FormErrorSummary';
import { LIMITS, boundedText, optionalEmail, optionalText } from '@/lib/validation';
import { useUnsavedChangesWarning } from '@/lib/api/hooks';
import type { Client, ClientRequest } from '@/types/api';

// Mirrors CreateClientRequest/UpdateClientRequest — see @/lib/validation for the limits.
const schema = z.object({
  displayName: boundedText(LIMITS.NAME, 'Enter a name'),
  clientType: z.enum(['INDIVIDUAL', 'CORPORATE']),
  email: optionalEmail,
  phone: optionalText(LIMITS.PHONE),
  addressLine1: optionalText(LIMITS.ADDRESS),
  addressLine2: optionalText(LIMITS.ADDRESS),
  city: optionalText(LIMITS.LOCALITY),
  state: optionalText(LIMITS.LOCALITY),
  country: optionalText(LIMITS.LOCALITY),
  postalCode: optionalText(LIMITS.POSTAL_CODE),
  notes: optionalText(LIMITS.NOTES),
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
      applyServerErrors(error, setError, Object.keys(schema.shape));
    }
  });

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      <FormErrorSummary message={errors.root?.message} />

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
