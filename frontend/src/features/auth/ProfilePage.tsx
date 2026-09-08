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
import {
  Avatar, Badge, Button, Card, CardHeader, Detail, DetailList, Field, Input,
  PasswordInput,
} from '@/components/ui/primitives';
import { formatDateTime, humanise } from '@/lib/format';
import { applyServerErrors } from '@/lib/api/formErrors';
import { FormErrorSummary } from '@/components/ui/FormErrorSummary';
import { LIMITS, boundedText, optionalText, strongPassword } from '@/lib/validation';

// Mirrors UpdateProfileRequest. Phone is 40 on the backend, not 32.
const profileSchema = z.object({
  firstName: boundedText(LIMITS.PERSON_NAME, 'Enter your first name'),
  lastName: boundedText(LIMITS.PERSON_NAME, 'Enter your last name'),
  phone: optionalText(LIMITS.PHONE),
});
type ProfileValues = z.infer<typeof profileSchema>;

// `newPassword` carries @StrongPassword on the backend: length is only one of its four
// rules, so a 12-character all-lowercase password used to pass here and be refused there
// with a message the form had nowhere to put.
const passwordSchema = z.object({
  currentPassword: z.string().min(1, 'Enter your current password'),
  newPassword: strongPassword,
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
      applyServerErrors(error, profileForm.setError, Object.keys(profileSchema.shape));
    }
  });

  const submitPassword = passwordForm.handleSubmit(async (values) => {
    try {
      await changePassword.mutateAsync(values);
    } catch (error) {
      // Includes WEAK_PASSWORD, whose message is the only explanation of *why* the
      // password was refused. It lands on newPassword when the backend names that field
      // and in the summary otherwise — either way it is on screen.
      applyServerErrors(error, passwordForm.setError,
        ['currentPassword', 'newPassword', 'confirmPassword']);
    }
  });

  return (
    <>
      <PageHeader title="Your profile" description="Your details and how you sign in." />

      <div className="space-y-4">
        <Card>
          <CardHeader title="Account" icon="user" />
          <div className="flex items-center gap-3 border-b border-ink-100 px-4 py-3">
            <Avatar name={user?.fullName ?? ''} />
            <div className="min-w-0">
              <p className="truncate text-sm font-medium text-ink-900">{user?.fullName}</p>
              <p className="truncate text-xs text-ink-500">{user?.email}</p>
            </div>
          </div>
          <DetailList columns={3}>
            <Detail label="Role">
              <Badge tone="info" dot>{humanise(user?.role ?? '')}</Badge>
            </Detail>
            {organization.data && (
              <Detail label="Firm">{organization.data.name}</Detail>
            )}
            <Detail label="Last signed in">
              {user?.lastLoginAt
                ? formatDateTime(user.lastLoginAt)
                : <span className="text-ink-500">This is your first session</span>}
            </Detail>
          </DetailList>
        </Card>

        <Card>
          <CardHeader title="Your details" icon="edit" />
          <form onSubmit={submitProfile} noValidate className="space-y-4 p-4">
            <FormErrorSummary message={profileForm.formState.errors.root?.message} />
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
            icon="settings"
            description="Changing your password does not sign out your other sessions."
          />
          <form onSubmit={submitPassword} noValidate className="space-y-4 p-4">
            <FormErrorSummary message={passwordForm.formState.errors.root?.message} />
            <Field label="Current password" required
              error={passwordForm.formState.errors.currentPassword?.message}>
              {({ id, describedBy, invalid }) => (
                <PasswordInput id={id} autoComplete="current-password"
                  aria-describedby={describedBy} invalid={invalid}
                  {...passwordForm.register('currentPassword')} />
              )}
            </Field>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="New password" required
                hint="At least 12 characters."
                error={passwordForm.formState.errors.newPassword?.message}>
                {({ id, describedBy, invalid }) => (
                  <PasswordInput id={id} autoComplete="new-password"
                    aria-describedby={describedBy} invalid={invalid}
                    {...passwordForm.register('newPassword')} />
                )}
              </Field>
              <Field label="Confirm new password" required
                error={passwordForm.formState.errors.confirmPassword?.message}>
                {({ id, describedBy, invalid }) => (
                  <PasswordInput id={id} autoComplete="new-password"
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
