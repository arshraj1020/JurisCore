import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { deadlinesApi } from '@/features/case-management/api';
import { keys } from '@/lib/api/queryKeys';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { nextDeadlineStatuses } from '@/lib/lifecycle';
import { useToast } from '@/components/ui/Toast';
import { Badge, Button, Card, CardHeader, Field, Input, Select, Textarea } from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { Dialog } from '@/components/ui/Dialog';
import { DeadlineStatusBadge } from '@/components/ui/StatusBadge';
import { formatDateTime, formatRelative, humanise, isPast } from '@/lib/format';
import { fieldErrorsOf, messageFor } from '@/lib/api/errors';
import type { DeadlineStatus, DeadlineType } from '@/types/api';

const schema = z.object({
  title: z.string().min(1, 'Enter a title').max(300),
  description: z.string().max(2000).or(z.literal('')),
  deadlineType: z.enum(['COURT', 'INTERNAL', 'OTHER']),
  dueAt: z.string().min(1, 'Choose when it falls due'),
  source: z.string().max(255).or(z.literal('')),
});
type Values = z.infer<typeof schema>;

export function CaseDeadlinesTab({ caseId }: { caseId: string }) {
  const { user } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [creating, setCreating] = useState(false);

  const query = useQuery({
    queryKey: keys.deadlines.forCase(caseId, {}),
    queryFn: () => deadlinesApi.listForCase(caseId, { size: 50 }),
  });

  const { register, handleSubmit, reset, setError, formState: { errors, isSubmitting } } =
    useForm<Values>({
      resolver: zodResolver(schema),
      defaultValues: { title: '', description: '', deadlineType: 'COURT', dueAt: '', source: '' },
    });

  const create = useMutation({
    mutationFn: (values: Values) => deadlinesApi.create(caseId, {
      title: values.title.trim(),
      description: values.description.trim() || null,
      deadlineType: values.deadlineType,
      dueAt: new Date(values.dueAt).toISOString(),
      source: values.source.trim() || null,
    }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.deadlines.all });
      await queryClient.invalidateQueries({ queryKey: ['cases', caseId, 'timeline'] });
      setCreating(false);
      reset();
      toast.success('Deadline recorded');
    },
  });

  const changeStatus = useMutation({
    mutationFn: ({ deadlineId, next }: { deadlineId: string; next: DeadlineStatus }) =>
      deadlinesApi.changeStatus(deadlineId, next),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.deadlines.all });
      await queryClient.invalidateQueries({ queryKey: ['cases', caseId, 'timeline'] });
    },
    onError: (error) => toast.error(messageFor(error)),
  });

  const submit = handleSubmit(async (values) => {
    try {
      await create.mutateAsync(values);
    } catch (error) {
      for (const [field, message] of Object.entries(fieldErrorsOf(error))) {
        if (field in schema.shape) setError(field as keyof Values, { message });
      }
      setError('root', { message: messageFor(error) });
    }
  });

  return (
    <Card>
      <CardHeader
        title="Deadlines"
        description="Dates the matter must not miss."
        actions={can(user?.role, 'manageCaseWork') && (
          <Button size="sm" onClick={() => setCreating(true)}>Add deadline</Button>
        )}
      />

      <AsyncSection
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        isEmpty={(data) => data.items.length === 0}
        onRetry={() => query.refetch()}
        skeleton={<TableSkeleton rows={3} columns={3} />}
        empty={<EmptyState title="No deadlines" description="Nothing is scheduled against this matter." />}
      >
        {(data) => (
          <ul className="divide-y divide-ink-100">
            {data.items.map((deadline) => {
              const options = can(user?.role, 'manageCaseWork')
                ? nextDeadlineStatuses(deadline.status) : [];
              const missed = deadline.status === 'OPEN' && isPast(deadline.dueAt);
              return (
                <li key={deadline.id} className="flex flex-wrap items-start justify-between gap-3 px-4 py-3">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-sm font-medium text-ink-900">{deadline.title}</span>
                      <DeadlineStatusBadge status={deadline.status} />
                      <Badge tone={deadline.deadlineType === 'COURT' ? 'info' : 'neutral'}>
                        {humanise(deadline.deadlineType)}
                      </Badge>
                      {missed && <Badge tone="danger">Passed</Badge>}
                    </div>
                    {deadline.description && (
                      <p className="mt-1 text-sm text-ink-600">{deadline.description}</p>
                    )}
                    <p className="mt-1 text-xs text-ink-500">
                      Due {formatDateTime(deadline.dueAt)} ({formatRelative(deadline.dueAt)})
                      {deadline.source && ` · ${deadline.source}`}
                    </p>
                  </div>
                  {options.length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {options.map((next) => (
                        <Button
                          key={next} size="sm" variant="secondary"
                          disabled={changeStatus.isPending}
                          onClick={() => changeStatus.mutate({ deadlineId: deadline.id, next })}
                        >
                          {humanise(next)}
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

      <Dialog open={creating} onClose={() => setCreating(false)} title="Add a deadline" footer={<span />}>
        <form onSubmit={submit} noValidate className="space-y-4">
          {errors.root && (
            <div role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800 ring-1 ring-inset ring-red-200">
              {errors.root.message}
            </div>
          )}
          <Field label="Title" error={errors.title?.message} required>
            {({ id, describedBy, invalid }) => (
              <Input id={id} autoFocus aria-describedby={describedBy} invalid={invalid}
                {...register('title')} />
            )}
          </Field>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Type" error={errors.deadlineType?.message} required
              hint="Who imposed the date.">
              {({ id, describedBy, invalid }) => (
                <Select id={id} aria-describedby={describedBy} invalid={invalid}
                  {...register('deadlineType')}>
                  {(['COURT', 'INTERNAL', 'OTHER'] as DeadlineType[]).map((value) => (
                    <option key={value} value={value}>{humanise(value)}</option>
                  ))}
                </Select>
              )}
            </Field>
            <Field label="Falls due" error={errors.dueAt?.message} required>
              {({ id, describedBy, invalid }) => (
                <Input id={id} type="datetime-local" aria-describedby={describedBy}
                  invalid={invalid} {...register('dueAt')} />
              )}
            </Field>
          </div>
          <Field label="Source" error={errors.source?.message}
            hint="Where the date comes from — an order, a statute, a client instruction.">
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} invalid={invalid} {...register('source')} />
            )}
          </Field>
          <Field label="Description" error={errors.description?.message}>
            {({ id, describedBy, invalid }) => (
              <Textarea id={id} rows={2} aria-describedby={describedBy} invalid={invalid}
                {...register('description')} />
            )}
          </Field>
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="secondary" onClick={() => setCreating(false)}
              disabled={isSubmitting}>Cancel</Button>
            <Button type="submit" loading={isSubmitting}>Add deadline</Button>
          </div>
        </form>
      </Dialog>
    </Card>
  );
}
