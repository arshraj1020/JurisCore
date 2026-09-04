import { useMutation, useQuery } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { organizationApi, usersApi } from './api';
import { keys } from '@/lib/api/queryKeys';
import { useAuth } from '@/lib/auth/AuthContext';
import { isFirmStaff } from '@/lib/auth/roles';
import { useToast } from '@/components/ui/Toast';
import { PageHeader } from '@/components/ui/PageHeader';
import { Badge, Button, Card, CardHeader, Field, Input } from '@/components/ui/primitives';
import { formatDateTime, humanise } from '@/lib/format';
import { fieldErrorsOf, messageFor } from '@/lib/api/errors';

const profileSchema = z.object({
  firstName: z.string().trim().min(1, 'Enter your first name').max(100),
  lastName: z.string().trim().min(1, 'Enter your last name').max(100),
  phone: z.string().max(32),
});
type ProfileValues = z.infer<typeof profileSchema>;

const passwordSchema = z.object({
  currentPassword: z.string().min(1, 'Enter your current password'),
  newPassword: z.string().min(12, 'Use at least 12 characters'),
  confirmPassword: z.string(),
}).refine((values) => values.newPassword === values.confirmPassword, {
  path: ['confirmPassword'],
  message: 'The two passwords do not match',
});
type PasswordValues = z.infer<typeof passwordSchema>;

export function ProfilePage() {
  const { user, setUser } = useAuth();
  const toast = useToast();

  const organization = useQuery({
    queryKey: keys.organization,
    queryFn: () => organizationApi.current(),
    enabled: isFirmStaff(user?.role),
  });

  const profileForm = useForm<ProfileValues>({
    resolver: zodResolver(profileSchema),
    defaultValues: {
      firstName: user?.firstName ?? '',
      lastName: user?.lastName ?? '',
      phone: user?.phone ?? '',
    },
  });

  const passwordForm = useForm<PasswordValues>({
    resolver: zodResolver(passwordSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  });

  const saveProfile = useMutation({
    mutationFn: (values: ProfileValues) => usersApi.updateMe({
      firstName: values.firstName.trim(),
      lastName: values.lastName.trim(),
      phone: values.phone.trim() || undefined,
    }),
    onSuccess: (updated) => {
      setUser(updated);
      toast.success('Profile updated');
    },
  });

  const changePassword = useMutation({
    mutationFn: (values: PasswordValues) => usersApi.changePassword({
      currentPassword: values.currentPassword,
      newPassword: values.newPassword,
    }),
    onSuccess: () => {
      // Nothing about the password is kept in component state after this point.
      passwordForm.reset();
      toast.success('Password changed');
    },
  });

  const submitProfile = profileForm.handleSubmit(async (values) => {
    try {
      await saveProfile.mutateAsync(values);
    } catch (error) {
      for (const [field, message] of Object.entries(fieldErrorsOf(error))) {
        if (field in profileSchema.shape) {
          profileForm.setError(field as keyof ProfileValues, { message });
        }
      }
      profileForm.setError('root', { message: messageFor(error) });
    }
  });

  const submitPassword = passwordForm.handleSubmit(async (values) => {
    try {
      await changePassword.mutateAsync(values);
    } catch (error) {
      passwordForm.setError('root', { message: messageFor(error) });
    }
  });

  return (
    <>
      <PageHeader title="Your profile" description="Your details and how you sign in." />

      <div className="space-y-4">
        <Card>
          <CardHeader title="Account" />
          <dl className="grid gap-x-6 gap-y-3 p-4 text-sm sm:grid-cols-2">
            <div>
              <dt className="text-xs uppercase tracking-wide text-ink-500">Email</dt>
              <dd className="mt-0.5 text-ink-900">{user?.email}</dd>
            </div>
            <div>
              <dt className="text-xs uppercase tracking-wide text-ink-500">Role</dt>
              <dd className="mt-0.5">
                <Badge tone="info">{humanise(user?.role ?? '')}</Badge>
              </dd>
            </div>
            {organization.data && (
              <div>
                <dt className="text-xs uppercase tracking-wide text-ink-500">Firm</dt>
                <dd className="mt-0.5 text-ink-900">{organization.data.name}</dd>
              </div>
            )}
            {user?.lastLoginAt && (
              <div>
                <dt className="text-xs uppercase tracking-wide text-ink-500">Last signed in</dt>
                <dd className="mt-0.5 text-ink-900">{formatDateTime(user.lastLoginAt)}</dd>
              </div>
            )}
          </dl>
        </Card>

        <Card>
          <CardHeader title="Your details" />
          <form onSubmit={submitProfile} noValidate className="space-y-4 p-4">
            {profileForm.formState.errors.root && (
              <div role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800 ring-1 ring-inset ring-red-200">
                {profileForm.formState.errors.root.message}
              </div>
            )}
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="First name" required
                error={profileForm.formState.errors.firstName?.message}>
                {({ id, describedBy, invalid }) => (
                  <Input id={id} aria-describedby={describedBy} invalid={invalid}
                    autoComplete="given-name" {...profileForm.register('firstName')} />
                )}
              </Field>
              <Field label="Last name" required
                error={profileForm.formState.errors.lastName?.message}>
                {({ id, describedBy, invalid }) => (
                  <Input id={id} aria-describedby={describedBy} invalid={invalid}
                    autoComplete="family-name" {...profileForm.register('lastName')} />
                )}
              </Field>
            </div>
            <Field label="Phone" error={profileForm.formState.errors.phone?.message}>
              {({ id, describedBy, invalid }) => (
                <Input id={id} aria-describedby={describedBy} invalid={invalid}
                  autoComplete="tel" {...profileForm.register('phone')} />
              )}
            </Field>
            <div className="flex justify-end">
              <Button type="submit" loading={profileForm.formState.isSubmitting}>Save</Button>
            </div>
          </form>
        </Card>

        <Card>
          <CardHeader
            title="Password"
            description="Changing your password does not sign out your other sessions."
          />
          <form onSubmit={submitPassword} noValidate className="space-y-4 p-4">
            {passwordForm.formState.errors.root && (
              <div role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800 ring-1 ring-inset ring-red-200">
                {passwordForm.formState.errors.root.message}
              </div>
            )}
            <Field label="Current password" required
              error={passwordForm.formState.errors.currentPassword?.message}>
              {({ id, describedBy, invalid }) => (
                <Input id={id} type="password" autoComplete="current-password"
                  aria-describedby={describedBy} invalid={invalid}
                  {...passwordForm.register('currentPassword')} />
              )}
            </Field>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="New password" required
                hint="At least 12 characters."
                error={passwordForm.formState.errors.newPassword?.message}>
                {({ id, describedBy, invalid }) => (
                  <Input id={id} type="password" autoComplete="new-password"
                    aria-describedby={describedBy} invalid={invalid}
                    {...passwordForm.register('newPassword')} />
                )}
              </Field>
              <Field label="Confirm new password" required
                error={passwordForm.formState.errors.confirmPassword?.message}>
                {({ id, describedBy, invalid }) => (
                  <Input id={id} type="password" autoComplete="new-password"
                    aria-describedby={describedBy} invalid={invalid}
                    {...passwordForm.register('confirmPassword')} />
                )}
              </Field>
            </div>
            <div className="flex justify-end">
              <Button type="submit" loading={passwordForm.formState.isSubmitting}>
                Change password
              </Button>
            </div>
          </form>
        </Card>
      </div>
    </>
  );
}
