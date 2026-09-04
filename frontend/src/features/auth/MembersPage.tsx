import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { usersApi } from './api';
import { keys } from '@/lib/api/queryKeys';
import { useDebounced, useListParams } from '@/lib/api/hooks';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { useToast } from '@/components/ui/Toast';
import { PageHeader } from '@/components/ui/PageHeader';
import {
  Avatar, Badge, Button, Card, Field, Input, SearchInput, Select, Toolbar,
} from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { UserStatusBadge } from '@/components/ui/StatusBadge';
import { DataTable } from '@/components/ui/DataTable';
import { Dialog } from '@/components/ui/Dialog';
import { Pagination } from '@/components/ui/Pagination';
import { formatDateTime, humanise } from '@/lib/format';
import { fieldErrorsOf, messageFor } from '@/lib/api/errors';
import type { Role, User, UserStatus } from '@/types/api';

/** Roles a firm administrator can hand out. SUPER_ADMIN is not one of them. */
const ASSIGNABLE_ROLES: Role[] = ['FIRM_ADMIN', 'LAWYER', 'CLERK', 'CLIENT'];

/** What a firm administrator may do to a member's status, per the backend's own rules. */
const NEXT_STATUS: Record<UserStatus, UserStatus[]> = {
  INVITED: ['DEACTIVATED'],
  ACTIVE: ['SUSPENDED', 'DEACTIVATED'],
  SUSPENDED: ['ACTIVE', 'DEACTIVATED'],
  DEACTIVATED: ['ACTIVE'],
};

/**
 * Buttons are labelled with the verb, not the destination state.
 *
 * A button reading "Suspended" beside a badge reading "Active" is genuinely ambiguous —
 * it looks like a second status label rather than something that will happen when it is
 * pressed.
 */
const STATUS_VERB: Record<UserStatus, string> = {
  ACTIVE: 'Reinstate',
  SUSPENDED: 'Suspend',
  DEACTIVATED: 'Deactivate',
  INVITED: 'Re-invite',
};

const inviteSchema = z.object({
  email: z.string().trim().email('Enter a valid email address'),
  firstName: z.string().trim().min(1, 'Enter a first name').max(100),
  lastName: z.string().trim().min(1, 'Enter a last name').max(100),
  phone: z.string().max(32),
  role: z.enum(['FIRM_ADMIN', 'LAWYER', 'CLERK', 'CLIENT']),
});
type InviteValues = z.infer<typeof inviteSchema>;

export function MembersPage() {
  const { user } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const { params, page, update, setPage } = useListParams({ search: '', role: '' });
  const [searchInput, setSearchInput] = useState(params.search);
  const debouncedSearch = useDebounced(searchInput);
  const [inviting, setInviting] = useState(false);

  useEffect(() => {
    if (debouncedSearch !== params.search) update({ search: debouncedSearch });
    // `update` is recreated on every render; depending on it would loop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedSearch, params.search]);

  const mayManage = can(user?.role, 'manageMembers');

  const query = useQuery({
    queryKey: keys.users.list({ ...params, page }),
    queryFn: () => usersApi.list({
      search: params.search || undefined,
      role: params.role || undefined,
      page,
    }),
  });

  const refresh = () => queryClient.invalidateQueries({ queryKey: keys.users.all });

  const changeStatus = useMutation({
    mutationFn: ({ userId, status }: { userId: string; status: UserStatus }) =>
      usersApi.changeStatus(userId, status),
    onSuccess: async (updated) => {
      await refresh();
      toast.success(`${updated.fullName} is now ${humanise(updated.status).toLowerCase()}`);
    },
    onError: (error) => toast.error(messageFor(error)),
  });

  const changeRole = useMutation({
    mutationFn: ({ userId, role }: { userId: string; role: Role }) =>
      usersApi.changeRole(userId, role),
    onSuccess: async (updated) => {
      await refresh();
      toast.success(`${updated.fullName} is now ${humanise(updated.role).toLowerCase()}`);
    },
    onError: (error) => toast.error(messageFor(error)),
  });

  return (
    <>
      <PageHeader
        title="People"
        description="Who has access to this firm's workspace. What each person may do is enforced by the server."
        actions={mayManage && (
          <Button icon="plus" onClick={() => setInviting(true)}>Invite someone</Button>
        )}
      />

      <Card>
        <Toolbar>
          <div className="min-w-[12rem] flex-1 sm:max-w-xs">
            <Field label="Search people" srOnlyLabel>
              {({ id }) => (
                <SearchInput id={id} placeholder="Name or email"
                  value={searchInput}
                  onChange={(event) => setSearchInput(event.target.value)} />
              )}
            </Field>
          </div>
          <Field label="Role" srOnlyLabel>
            {({ id }) => (
              <Select id={id} value={params.role} aria-label="Filter by role"
                onChange={(event) => update({ role: event.target.value })}>
                <option value="">All roles</option>
                {ASSIGNABLE_ROLES.map((role) => (
                  <option key={role} value={role}>{humanise(role)}</option>
                ))}
              </Select>
            )}
          </Field>
        </Toolbar>

        <AsyncSection
          isLoading={query.isPending}
          error={query.error}
          data={query.data}
          isEmpty={(data) => data.items.length === 0}
          onRetry={() => query.refetch()}
          skeleton={<TableSkeleton columns={5} />}
          empty={<EmptyState icon="people" title="Nobody found"
            description="Try widening the filters." />}
        >
          {(data) => (
            <>
              <DataTable
                caption="People"
                rows={data.items}
                rowKey={(member) => member.id}
                columns={[
                  {
                    key: 'name', header: 'Name', primary: true,
                    cell: (member: User) => (
                      <span className="flex items-center gap-2.5">
                        <Avatar name={member.fullName} size="sm" />
                        <span className="min-w-0">
                          <span className="block truncate font-medium text-ink-900">
                            {member.fullName}
                          </span>
                          <span className="block truncate text-xs text-ink-500">
                            {member.email}
                          </span>
                        </span>
                      </span>
                    ),
                  },
                  {
                    key: 'role', header: 'Role',
                    cell: (member: User) => (
                      mayManage && member.id !== user?.id && member.role !== 'SUPER_ADMIN' ? (
                        <Select
                          aria-label={`Role for ${member.fullName}`}
                          value={member.role}
                          disabled={changeRole.isPending}
                          onChange={(event) => changeRole.mutate({
                            userId: member.id, role: event.target.value as Role,
                          })}
                        >
                          {ASSIGNABLE_ROLES.map((role) => (
                            <option key={role} value={role}>{humanise(role)}</option>
                          ))}
                        </Select>
                      ) : <Badge tone="info">{humanise(member.role)}</Badge>
                    ),
                  },
                  {
                    key: 'status', header: 'Status',
                    cell: (member: User) => <UserStatusBadge status={member.status} />,
                  },
                  {
                    key: 'lastLogin', header: 'Last signed in',
                    cell: (member: User) => member.lastLoginAt
                      ? <span className="whitespace-nowrap text-ink-600">
                        {formatDateTime(member.lastLoginAt)}
                      </span>
                      : <span className="text-ink-500">Never</span>,
                  },
                  ...(mayManage ? [{
                    key: 'actions', header: 'Actions', numeric: true,
                    cell: (member: User) => (
                      // Nobody can suspend or deactivate themselves out of the firm.
                      member.id === user?.id ? (
                        <span className="text-xs text-ink-500">That is you</span>
                      ) : (
                        <span className="flex flex-wrap justify-end gap-1">
                          {NEXT_STATUS[member.status].map((status) => (
                            <Button
                              key={status} size="sm"
                              disabled={changeStatus.isPending}
                              variant={status === 'DEACTIVATED' ? 'ghost' : 'secondary'}
                              onClick={() => changeStatus.mutate({ userId: member.id, status })}
                            >
                              {STATUS_VERB[status]}
                            </Button>
                          ))}
                        </span>
                      )
                    ),
                  }] : []),
                ]}
              />
              <Pagination page={data} onPageChange={setPage} label="people" />
            </>
          )}
        </AsyncSection>
      </Card>

      {inviting && (
        <InviteDialog
          onClose={() => setInviting(false)}
          onInvited={async (invited) => {
            await refresh();
            setInviting(false);
            toast.success(`${invited.fullName} invited`);
          }}
        />
      )}
    </>
  );
}

function InviteDialog({ onClose, onInvited }: {
  onClose: () => void; onInvited: (user: User) => void | Promise<void>;
}) {
  const { register, handleSubmit, setError, formState: { errors, isSubmitting } } =
    useForm<InviteValues>({
      resolver: zodResolver(inviteSchema),
      defaultValues: { email: '', firstName: '', lastName: '', phone: '', role: 'LAWYER' },
    });

  const invite = useMutation({
    mutationFn: (values: InviteValues) => usersApi.invite({
      email: values.email.trim(),
      firstName: values.firstName.trim(),
      lastName: values.lastName.trim(),
      phone: values.phone.trim() || undefined,
      role: values.role,
    }),
    onSuccess: (invited) => void onInvited(invited),
  });

  const submit = handleSubmit(async (values) => {
    try {
      await invite.mutateAsync(values);
    } catch (error) {
      for (const [field, message] of Object.entries(fieldErrorsOf(error))) {
        if (field in inviteSchema.shape) setError(field as keyof InviteValues, { message });
      }
      setError('root', { message: messageFor(error) });
    }
  });

  return (
    <Dialog
      open
      onClose={onClose}
      title="Invite someone"
      description="They receive an invitation and set their own password. JurisCore never shows you theirs."
      footer={<span />}
    >
      <form onSubmit={submit} noValidate className="space-y-4">
        {errors.root && (
          <div role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800 ring-1 ring-inset ring-red-200">
            {errors.root.message}
          </div>
        )}
        <Field label="Email" error={errors.email?.message} required>
          {({ id, describedBy, invalid }) => (
            <Input id={id} type="email" autoFocus autoComplete="off"
              aria-describedby={describedBy} invalid={invalid} {...register('email')} />
          )}
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="First name" error={errors.firstName?.message} required>
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} invalid={invalid}
                {...register('firstName')} />
            )}
          </Field>
          <Field label="Last name" error={errors.lastName?.message} required>
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} invalid={invalid}
                {...register('lastName')} />
            )}
          </Field>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Phone" error={errors.phone?.message}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} invalid={invalid}
                {...register('phone')} />
            )}
          </Field>
          <Field label="Role" error={errors.role?.message} required
            hint="What they may do is enforced by the server, not by this list.">
            {({ id, describedBy, invalid }) => (
              <Select id={id} aria-describedby={describedBy} invalid={invalid}
                {...register('role')}>
                {ASSIGNABLE_ROLES.map((role) => (
                  <option key={role} value={role}>{humanise(role)}</option>
                ))}
              </Select>
            )}
          </Field>
        </div>
        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="secondary" onClick={onClose} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" loading={isSubmitting}>Send invitation</Button>
        </div>
      </form>
    </Dialog>
  );
}
