import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '@/lib/auth/AuthContext';
import { Alert, Button, Field, Input, PasswordInput } from '@/components/ui/primitives';
import { ApiError } from '@/lib/api/errors';
import { mapServerErrors } from '@/lib/api/formErrors';
import { LIMITS, boundedText, strongPassword } from '@/lib/validation';
import { AuthLayout } from './AuthLayout';

/**
 * `WEAK_PASSWORD` from the backend is still what decides — the rules mirrored in
 * `@/lib/validation` exist to save a round trip and to say *which* rule was missed while
 * the user is still in the field. The two are kept in one place precisely so they cannot
 * drift: `strongPassword` is a transcription of `StrongPasswordValidator`, cited line by
 * line, rather than a second opinion about what a good password is.
 */
const schema = z.object({
  firmName: boundedText(LIMITS.NAME, 'Enter your firm name'),
  firstName: boundedText(LIMITS.PERSON_NAME, 'Enter your first name'),
  lastName: boundedText(LIMITS.PERSON_NAME, 'Enter your last name'),
  email: z.string().trim().min(1, 'Enter an email address')
    .email('That does not look like an email address')
    .max(LIMITS.EMAIL, `Use at most ${LIMITS.EMAIL} characters`),
  password: strongPassword,
});
type Values = z.infer<typeof schema>;

export function RegisterPage() {
  const { user, register: registerFirm } = useAuth();
  const navigate = useNavigate();
  const [formError, setFormError] = useState<string | null>(null);

  const { register, handleSubmit, setError, formState: { errors, isSubmitting } } =
    useForm<Values>({ resolver: zodResolver(schema) });

  if (user) return <Navigate to="/" replace />;

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    try {
      await registerFirm({ ...values, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone });
      navigate('/', { replace: true });
    } catch (error) {
      // Field-level violations from Bean Validation go onto their fields; everything
      // else goes to the banner above the form. Nothing is dropped.
      const { fields, summary } = mapServerErrors(error, Object.keys(schema.shape));
      for (const { field, message } of fields) {
        setError(field as keyof Values, { message });
      }
      if (error instanceof ApiError && error.code === 'WEAK_PASSWORD') {
        setError('password', { message: error.message });
      } else if (summary.length > 0) {
        setFormError(summary.join(' '));
      } else if (fields.length === 0) {
        setFormError('Could not create the account.');
      }
    }
  });

  return (
    <AuthLayout
      title="Create your firm"
      subtitle="You will be its first administrator, and can invite colleagues afterwards."
      wide
    >
      <form onSubmit={onSubmit} noValidate className="space-y-5">
        {formError && <Alert tone="danger" live>{formError}</Alert>}

        <Field label="Firm name" error={errors.firmName?.message} required>
          {({ id, describedBy, invalid }) => (
            <Input id={id} autoFocus autoComplete="organization" aria-describedby={describedBy}
              invalid={invalid} {...register('firmName')} />
          )}
        </Field>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="First name" error={errors.firstName?.message} required>
            {({ id, describedBy, invalid }) => (
              <Input id={id} autoComplete="given-name" aria-describedby={describedBy}
                invalid={invalid} {...register('firstName')} />
            )}
          </Field>
          <Field label="Last name" error={errors.lastName?.message} required>
            {({ id, describedBy, invalid }) => (
              <Input id={id} autoComplete="family-name" aria-describedby={describedBy}
                invalid={invalid} {...register('lastName')} />
            )}
          </Field>
        </div>

        <Field label="Email address" error={errors.email?.message} required>
          {({ id, describedBy, invalid }) => (
            <Input id={id} type="email" autoComplete="email" aria-describedby={describedBy}
              invalid={invalid} {...register('email')} />
          )}
        </Field>

        <Field label="Password" error={errors.password?.message} required
          hint="At least 12 characters. The server applies the firm's full policy.">
          {({ id, describedBy, invalid }) => (
            <PasswordInput id={id} autoComplete="new-password"
              aria-describedby={describedBy} invalid={invalid} {...register('password')} />
          )}
        </Field>

        <Button type="submit" loading={isSubmitting} className="w-full">Create firm</Button>

        <p className="border-t border-ink-200 pt-5 text-center text-sm text-ink-600">
          Already have an account?{' '}
          <Link to="/login" className="rounded font-medium text-brand-700 hover:underline">
            Sign in
          </Link>
        </p>
      </form>
    </AuthLayout>
  );
}
