import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/lib/auth/AuthContext';
import { Button, Field, Input } from '@/components/ui/primitives';
import { ApiError } from '@/lib/api/errors';
import { AuthLayout } from './AuthLayout';

const schema = z.object({
  email: z.string().min(1, 'Enter your email address').email('That does not look like an email address'),
  password: z.string().min(1, 'Enter your password'),
});
type Values = z.infer<typeof schema>;

export function LoginPage() {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [formError, setFormError] = useState<string | null>(null);

  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { email: '', password: '' },
  });

  // Somebody already signed in has no business on this page; send them where they were
  // headed, or to the dashboard.
  const from = (location.state as { from?: Location } | null)?.from?.pathname ?? '/';
  if (user) return <Navigate to={from} replace />;

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    try {
      await login(values);
      navigate(from, { replace: true });
    } catch (error) {
      // Deliberately not distinguishing "no such account" from "wrong password": the
      // backend does not, because doing so turns the login form into a way to find out
      // who has an account.
      setFormError(error instanceof ApiError ? error.message : 'Could not sign you in.');
    }
  });

  return (
    <AuthLayout title="Sign in" subtitle="Access your firm's workspace.">
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {formError && (
          <div role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800 ring-1 ring-inset ring-red-200">
            {formError}
          </div>
        )}

        <Field label="Email address" error={errors.email?.message} required>
          {({ id, describedBy, invalid }) => (
            <Input id={id} type="email" autoComplete="email" autoFocus
              aria-describedby={describedBy} invalid={invalid} {...register('email')} />
          )}
        </Field>

        <Field label="Password" error={errors.password?.message} required>
          {({ id, describedBy, invalid }) => (
            <Input id={id} type="password" autoComplete="current-password"
              aria-describedby={describedBy} invalid={invalid} {...register('password')} />
          )}
        </Field>

        <Button type="submit" loading={isSubmitting} className="w-full">Sign in</Button>

        <p className="text-center text-sm text-ink-600">
          New firm?{' '}
          <Link to="/register" className="font-medium text-brand-700 hover:underline">
            Create an account
          </Link>
        </p>
      </form>
    </AuthLayout>
  );
}
