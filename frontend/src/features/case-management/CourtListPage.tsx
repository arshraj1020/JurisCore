import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { courtsApi } from './api';
import { keys } from '@/lib/api/queryKeys';
import { useListParams } from '@/lib/api/hooks';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { useToast } from '@/components/ui/Toast';
import { PageHeader } from '@/components/ui/PageHeader';
import { Badge, Button, Card, Field, Input, Select } from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { DataTable } from '@/components/ui/DataTable';
import { ConfirmDialog, Dialog } from '@/components/ui/Dialog';
import { Pagination } from '@/components/ui/Pagination';
import { humanise } from '@/lib/format';
import { fieldErrorsOf, messageFor } from '@/lib/api/errors';
import type { Court, CourtType } from '@/types/api';

const COURT_TYPES: CourtType[] = ['SUPREME', 'HIGH', 'DISTRICT', 'TRIBUNAL', 'OTHER'];

const schema = z.object({
  name: z.string().trim().min(1, 'Enter a name').max(255),
  courtType: z.enum(['SUPREME', 'HIGH', 'DISTRICT', 'TRIBUNAL', 'OTHER']),
  addressLine1: z.string().max(255),
  addressLine2: z.string().max(255),
  city: z.string().max(120),
  state: z.string().max(120),
  country: z.string().max(120),
  timezone: z.string().max(64),
});
type Values = z.infer<typeof schema>;

export function CourtListPage() {
  const { user } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const { params, page, update, setPage } = useListParams({ includeRetired: '' });
  const [editing, setEditing] = useState<Court | 'new' | null>(null);
  const [retiring, setRetiring] = useState<Court | null>(null);

  const includeRetired = params.includeRetired === 'true';
  const mayManage = can(user?.role, 'manageCourts');
  const mayRetire = can(user?.role, 'deleteCaseWork');

  const query = useQuery({
    queryKey: keys.courts.list({ includeRetired, page }),
    queryFn: () => courtsApi.list({ includeRetired, page }),
  });

  const retire = useMutation({
    mutationFn: (courtId: string) => courtsApi.retire(courtId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.courts.all });
      setRetiring(null);
      toast.success('Court retired');
    },
    onError: (error) => toast.error(messageFor(error)),
  });

  return (
    <>
      <PageHeader
        title="Courts"
        description="Venues hearings can be listed at."
        actions={mayManage && <Button onClick={() => setEditing('new')}>Add court</Button>}
      />

      <Card>
        <div className="flex flex-wrap items-center gap-3 border-b border-ink-200 p-3">
          <label className="flex items-center gap-2 text-sm text-ink-700">
            <input
              type="checkbox"
              className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500"
              checked={includeRetired}
              onChange={(event) => update({ includeRetired: event.target.checked ? 'true' : '' })}
            />
            Show retired courts
          </label>
        </div>

        <AsyncSection
          isLoading={query.isPending}
          error={query.error}
          data={query.data}
          isEmpty={(data) => data.items.length === 0}
          onRetry={() => query.refetch()}
          skeleton={<TableSkeleton columns={4} />}
          empty={(
            <EmptyState
              title="No courts"
              description={mayManage
                ? 'Add the courts your matters are listed at so hearings can reference them.'
                : 'No court has been set up yet.'}
            />
          )}
        >
          {(data) => (
            <>
              <DataTable
                caption="Courts"
                rows={data.items}
                rowKey={(court) => court.id}
                columns={[
                  {
                    key: 'name', header: 'Court', primary: true,
                    cell: (court: Court) => (
                      <span className="flex flex-wrap items-center gap-2">
                        <span className="font-medium text-ink-900">{court.name}</span>
                        {!court.active && <Badge tone="neutral">Retired</Badge>}
                      </span>
                    ),
                  },
                  {
                    key: 'type', header: 'Type',
                    cell: (court: Court) => humanise(court.courtType),
                  },
                  {
                    key: 'where', header: 'Location',
                    cell: (court: Court) =>
                      [court.city, court.state, court.country].filter(Boolean).join(', ') || '—',
                  },
                  ...(mayManage ? [{
                    key: 'actions', header: 'Actions',
                    cell: (court: Court) => (
                      <span className="flex flex-wrap gap-1">
                        <Button size="sm" variant="ghost" onClick={() => setEditing(court)}>
                          Edit
                        </Button>
                        {mayRetire && court.active && (
                          <Button size="sm" variant="ghost" onClick={() => setRetiring(court)}>
                            Retire
                          </Button>
                        )}
                      </span>
                    ),
                  }] : []),
                ]}
              />
              <Pagination page={data} onPageChange={setPage} label="courts" />
            </>
          )}
        </AsyncSection>
      </Card>

      {editing && (
        <CourtDialog
          court={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={async (message) => {
            await queryClient.invalidateQueries({ queryKey: keys.courts.all });
            setEditing(null);
            toast.success(message);
          }}
        />
      )}

      <ConfirmDialog
        open={retiring !== null}
        onClose={() => setRetiring(null)}
        onConfirm={() => retiring && retire.mutate(retiring.id)}
        title="Retire this court?"
        description={retiring
          ? `“${retiring.name}” stays on past hearings but can no longer be chosen for new ones.`
          : ''}
        confirmLabel="Retire"
        busy={retire.isPending}
      />
    </>
  );
}

function CourtDialog({ court, onClose, onSaved }: {
  court: Court | null;
  onClose: () => void;
  onSaved: (message: string) => void | Promise<void>;
}) {
  const { register, handleSubmit, setError, formState: { errors, isSubmitting } } =
    useForm<Values>({
      resolver: zodResolver(schema),
      defaultValues: {
        name: court?.name ?? '',
        courtType: court?.courtType ?? 'DISTRICT',
        addressLine1: court?.addressLine1 ?? '',
        addressLine2: court?.addressLine2 ?? '',
        city: court?.city ?? '',
        state: court?.state ?? '',
        country: court?.country ?? '',
        timezone: court?.timezone ?? '',
      },
    });

  const save = useMutation({
    mutationFn: (values: Values) => {
      const body = {
        name: values.name.trim(),
        courtType: values.courtType,
        addressLine1: values.addressLine1.trim() || null,
        addressLine2: values.addressLine2.trim() || null,
        city: values.city.trim() || null,
        state: values.state.trim() || null,
        country: values.country.trim() || null,
        timezone: values.timezone.trim() || null,
      };
      // `version` carries the optimistic lock on an update; a new court has none.
      return court
        ? courtsApi.update(court.id, { ...body, version: court.version })
        : courtsApi.create(body);
    },
    onSuccess: () => void onSaved(court ? 'Court updated' : 'Court added'),
  });

  const submit = handleSubmit(async (values) => {
    try {
      await save.mutateAsync(values);
    } catch (error) {
      for (const [field, message] of Object.entries(fieldErrorsOf(error))) {
        if (field in schema.shape) setError(field as keyof Values, { message });
      }
      setError('root', { message: messageFor(error) });
    }
  });

  return (
    <Dialog open onClose={onClose} title={court ? 'Edit court' : 'Add a court'} footer={<span />}>
      <form onSubmit={submit} noValidate className="space-y-4">
        {errors.root && (
          <div role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800 ring-1 ring-inset ring-red-200">
            {errors.root.message}
          </div>
        )}
        <Field label="Name" error={errors.name?.message} required>
          {({ id, describedBy, invalid }) => (
            <Input id={id} autoFocus aria-describedby={describedBy} invalid={invalid}
              {...register('name')} />
          )}
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Type" error={errors.courtType?.message} required>
            {({ id, describedBy, invalid }) => (
              <Select id={id} aria-describedby={describedBy} invalid={invalid}
                {...register('courtType')}>
                {COURT_TYPES.map((value) => (
                  <option key={value} value={value}>{humanise(value)}</option>
                ))}
              </Select>
            )}
          </Field>
          <Field label="Timezone" error={errors.timezone?.message}
            hint="IANA name, e.g. Asia/Kolkata.">
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} invalid={invalid}
                {...register('timezone')} />
            )}
          </Field>
        </div>
        <Field label="Address line 1" error={errors.addressLine1?.message}>
          {({ id, describedBy, invalid }) => (
            <Input id={id} aria-describedby={describedBy} invalid={invalid}
              {...register('addressLine1')} />
          )}
        </Field>
        <Field label="Address line 2" error={errors.addressLine2?.message}>
          {({ id, describedBy, invalid }) => (
            <Input id={id} aria-describedby={describedBy} invalid={invalid}
              {...register('addressLine2')} />
          )}
        </Field>
        <div className="grid gap-4 sm:grid-cols-3">
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
        </div>
        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="secondary" onClick={onClose} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" loading={isSubmitting}>{court ? 'Save' : 'Add court'}</Button>
        </div>
      </form>
    </Dialog>
  );
}
