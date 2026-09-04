import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Link } from 'react-router-dom';
import { courtsApi, hearingsApi } from '@/features/case-management/api';
import { keys } from '@/lib/api/queryKeys';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { nextHearingStatuses } from '@/lib/lifecycle';
import { useToast } from '@/components/ui/Toast';
import { Button, Card, CardHeader, Field, Input, Select, Textarea } from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { Dialog } from '@/components/ui/Dialog';
import { HearingStatusBadge } from '@/components/ui/StatusBadge';
import { formatDateTime, formatRelative, humanise } from '@/lib/format';
import { fieldErrorsOf, messageFor } from '@/lib/api/errors';
import type { HearingStatus, HearingType } from '@/types/api';

const HEARING_TYPES: HearingType[] = ['MENTION', 'EVIDENCE', 'ARGUMENTS', 'JUDGMENT', 'OTHER'];

const schema = z.object({
  courtId: z.string().min(1, 'Choose the court'),
  hearingType: z.enum(['MENTION', 'EVIDENCE', 'ARGUMENTS', 'JUDGMENT', 'OTHER']),
  scheduledAt: z.string().min(1, 'Choose the date and time'),
  durationMinutes: z.string().regex(/^\d*$/, 'Whole minutes only').max(4),
  judgeName: z.string().max(255).or(z.literal('')),
  courtroom: z.string().max(100).or(z.literal('')),
  purpose: z.string().max(2000).or(z.literal('')),
});
type Values = z.infer<typeof schema>;

/**
 * A hearing that has happened wants a note of what happened. The backend accepts an
 * optional `outcome` alongside the status change for exactly that, so COMPLETED and
 * ADJOURNED are asked for one rather than being fired straight off a button.
 */
const WANTS_OUTCOME: HearingStatus[] = ['COMPLETED', 'ADJOURNED'];

export function CaseHearingsTab({ caseId }: { caseId: string }) {
  const { user } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [scheduling, setScheduling] = useState(false);
  const [outcomeFor, setOutcomeFor] = useState<{ id: string; next: HearingStatus } | null>(null);
  const [outcome, setOutcome] = useState('');

  const query = useQuery({
    queryKey: keys.hearings.list({ caseId }),
    queryFn: () => hearingsApi.list({ caseId, size: 50 }),
  });

  // Only staff who can schedule need the court list; everybody else never opens the dialog.
  const mayManage = can(user?.role, 'manageCaseWork');
  const courts = useQuery({
    queryKey: keys.courts.list({ forHearings: true }),
    queryFn: () => courtsApi.list({ size: 200 }),
    enabled: mayManage,
  });
  const courtName = (courtId: string) =>
    courts.data?.items.find((court) => court.id === courtId)?.name;

  const { register, handleSubmit, reset, setError, formState: { errors, isSubmitting } } =
    useForm<Values>({
      resolver: zodResolver(schema),
      defaultValues: {
        courtId: '', hearingType: 'MENTION', scheduledAt: '',
        durationMinutes: '', judgeName: '', courtroom: '', purpose: '',
      },
    });

  const invalidate = async () => {
    await queryClient.invalidateQueries({ queryKey: keys.hearings.all });
    await queryClient.invalidateQueries({ queryKey: ['cases', caseId, 'timeline'] });
  };

  const schedule = useMutation({
    mutationFn: (values: Values) => hearingsApi.schedule({
      caseId,
      courtId: values.courtId,
      hearingType: values.hearingType,
      // datetime-local yields a local wall-clock string; the API wants an instant.
      scheduledAt: new Date(values.scheduledAt).toISOString(),
      durationMinutes: values.durationMinutes ? Number(values.durationMinutes) : null,
      judgeName: values.judgeName.trim() || null,
      courtroom: values.courtroom.trim() || null,
      purpose: values.purpose.trim() || null,
    }),
    onSuccess: async () => {
      await invalidate();
      setScheduling(false);
      reset();
      toast.success('Hearing scheduled');
    },
  });

  const changeStatus = useMutation({
    mutationFn: ({ id, next, note }: { id: string; next: HearingStatus; note?: string }) =>
      hearingsApi.changeStatus(id, next, note),
    onSuccess: async (_data, variables) => {
      await invalidate();
      setOutcomeFor(null);
      setOutcome('');
      toast.success(`Hearing marked ${humanise(variables.next).toLowerCase()}`);
    },
    onError: (error) => toast.error(messageFor(error)),
  });

  const submit = handleSubmit(async (values) => {
    try {
      await schedule.mutateAsync(values);
    } catch (error) {
      for (const [field, message] of Object.entries(fieldErrorsOf(error))) {
        if (field in schema.shape) setError(field as keyof Values, { message });
      }
      setError('root', { message: messageFor(error) });
    }
  });

  const applyStatus = (id: string, next: HearingStatus) => {
    if (WANTS_OUTCOME.includes(next)) {
      setOutcome('');
      setOutcomeFor({ id, next });
      return;
    }
    changeStatus.mutate({ id, next });
  };

  return (
    <Card>
      <CardHeader
        icon="hearings"
        title="Hearings"
        description="Listed appearances for this matter."
        actions={mayManage && (
          <Button size="sm" icon="plus" onClick={() => setScheduling(true)}>Schedule hearing</Button>
        )}
      />

      <AsyncSection
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        isEmpty={(data) => data.items.length === 0}
        onRetry={() => query.refetch()}
        skeleton={<TableSkeleton rows={3} columns={3} />}
        empty={(
          <EmptyState
            compact icon="calendar"
            title="No hearings"
            description="Nothing has been listed against this matter yet."
          />
        )}
      >
        {(data) => (
          <ul className="divide-y divide-ink-100">
            {data.items.map((hearing) => {
              const options = can(user?.role, 'changeHearingStatus')
                ? nextHearingStatuses(hearing.status) : [];
              const court = courtName(hearing.courtId);
              return (
                <li key={hearing.id} className="flex flex-wrap items-start justify-between gap-3 px-4 py-3">
                  <div className="min-w-0 basis-full sm:flex-1 sm:basis-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-sm font-medium text-ink-900">
                        {humanise(hearing.hearingType)}
                      </span>
                      <HearingStatusBadge status={hearing.status} />
                    </div>
                    <p className="mt-1 text-sm text-ink-600">
                      {formatDateTime(hearing.scheduledAt)}
                      <span className="text-ink-500"> · </span>
                      {formatRelative(hearing.scheduledAt)}
                      {typeof hearing.durationMinutes === 'number' && (
                        <>
                          <span className="text-ink-500"> · </span>
                          {hearing.durationMinutes} min
                        </>
                      )}
                    </p>
                    <p className="mt-1 text-xs text-ink-500">
                      {court ?? 'Court'}
                      {hearing.courtroom && ` · ${hearing.courtroom}`}
                      {hearing.judgeName && ` · ${hearing.judgeName}`}
                    </p>
                    {hearing.purpose && (
                      <p className="mt-1 text-sm text-ink-600">{hearing.purpose}</p>
                    )}
                    {hearing.outcome && (
                      <p className="mt-1 text-sm text-ink-700">
                        <span className="font-medium">Outcome: </span>{hearing.outcome}
                      </p>
                    )}
                  </div>
                  {options.length > 0 && (
                    <div className="flex w-full flex-wrap gap-1 sm:w-auto sm:justify-end">
                      {options.map((next) => (
                        <Button
                          key={next} size="sm" variant="secondary"
                          disabled={changeStatus.isPending}
                          onClick={() => applyStatus(hearing.id, next)}
                        >
                          {next === 'SCHEDULED' ? 'Relist' : `Mark ${humanise(next).toLowerCase()}`}
                        </Button>
                      ))}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </AsyncSection>

      <Dialog
        open={scheduling}
        onClose={() => setScheduling(false)}
        title="Schedule a hearing"
        footer={<span />}
      >
        <form onSubmit={submit} noValidate className="space-y-4">
          {errors.root && (
            <div role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800 ring-1 ring-inset ring-red-200">
              {errors.root.message}
            </div>
          )}

          {courts.data && courts.data.items.length === 0 ? (
            <p className="rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-900 ring-1 ring-inset ring-amber-200">
              No courts are on file yet. Add one under{' '}
              <Link to="/courts" className="font-medium underline">Courts</Link> first.
            </p>
          ) : null}

          <Field label="Court" error={errors.courtId?.message} required>
            {({ id, describedBy, invalid }) => (
              <Select id={id} autoFocus aria-describedby={describedBy} invalid={invalid}
                disabled={courts.isPending} {...register('courtId')}>
                <option value="">{courts.isPending ? 'Loading courts…' : 'Select a court'}</option>
                {courts.data?.items.map((court) => (
                  <option key={court.id} value={court.id}>
                    {court.name}{court.city ? ` — ${court.city}` : ''}
                  </option>
                ))}
              </Select>
            )}
          </Field>

          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Type" error={errors.hearingType?.message} required>
              {({ id, describedBy, invalid }) => (
                <Select id={id} aria-describedby={describedBy} invalid={invalid}
                  {...register('hearingType')}>
                  {HEARING_TYPES.map((value) => (
                    <option key={value} value={value}>{humanise(value)}</option>
                  ))}
                </Select>
              )}
            </Field>
            <Field label="Listed for" error={errors.scheduledAt?.message} required>
              {({ id, describedBy, invalid }) => (
                <Input id={id} type="datetime-local" aria-describedby={describedBy}
                  invalid={invalid} {...register('scheduledAt')} />
              )}
            </Field>
          </div>

          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="Duration" error={errors.durationMinutes?.message} hint="Minutes.">
              {({ id, describedBy, invalid }) => (
                <Input id={id} inputMode="numeric" aria-describedby={describedBy}
                  invalid={invalid} {...register('durationMinutes')} />
              )}
            </Field>
            <Field label="Courtroom" error={errors.courtroom?.message}>
              {({ id, describedBy, invalid }) => (
                <Input id={id} aria-describedby={describedBy} invalid={invalid}
                  {...register('courtroom')} />
              )}
            </Field>
            <Field label="Judge" error={errors.judgeName?.message}>
              {({ id, describedBy, invalid }) => (
                <Input id={id} aria-describedby={describedBy} invalid={invalid}
                  {...register('judgeName')} />
              )}
            </Field>
          </div>

          <Field label="Purpose" error={errors.purpose?.message}
            hint="What the matter is listed for.">
            {({ id, describedBy, invalid }) => (
              <Textarea id={id} rows={2} aria-describedby={describedBy} invalid={invalid}
                {...register('purpose')} />
            )}
          </Field>

          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="secondary" onClick={() => setScheduling(false)}
              disabled={isSubmitting}>Cancel</Button>
            <Button type="submit" loading={isSubmitting}>Schedule</Button>
          </div>
        </form>
      </Dialog>

      <Dialog
        open={outcomeFor !== null}
        onClose={() => setOutcomeFor(null)}
        title={outcomeFor ? `Mark hearing ${humanise(outcomeFor.next).toLowerCase()}` : ''}
        footer={<span />}
      >
        <div className="space-y-4">
          <Field label="Outcome" hint="Optional — what the court did.">
            {({ id, describedBy }) => (
              <Textarea id={id} rows={3} autoFocus aria-describedby={describedBy}
                value={outcome} onChange={(event) => setOutcome(event.target.value)} />
            )}
          </Field>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" onClick={() => setOutcomeFor(null)}
              disabled={changeStatus.isPending}>Cancel</Button>
            <Button
              type="button"
              loading={changeStatus.isPending}
              onClick={() => {
                if (!outcomeFor) return;
                changeStatus.mutate({
                  id: outcomeFor.id,
                  next: outcomeFor.next,
                  note: outcome.trim() || undefined,
                });
              }}
            >
              Save
            </Button>
          </div>
        </div>
      </Dialog>
    </Card>
  );
}
