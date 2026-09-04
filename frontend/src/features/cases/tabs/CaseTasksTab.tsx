import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { tasksApi } from '@/features/case-management/api';
import { usersApi } from '@/features/auth/api';
import { keys } from '@/lib/api/queryKeys';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { nextTaskStatuses } from '@/lib/lifecycle';
import { useToast } from '@/components/ui/Toast';
import { Badge, Button, Card, CardHeader, Field, Input, Select, Textarea } from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { Dialog } from '@/components/ui/Dialog';
import { TaskStatusBadge } from '@/components/ui/StatusBadge';
import { formatDateTime, humanise, isPast } from '@/lib/format';
import { fieldErrorsOf, messageFor } from '@/lib/api/errors';
import type { Tone } from '@/components/ui/primitives';
import type { TaskPriority, TaskStatus } from '@/types/api';

const PRIORITY_TONE: Record<TaskPriority, Tone> = {
  LOW: 'neutral', MEDIUM: 'neutral', HIGH: 'warning', URGENT: 'danger',
};

const schema = z.object({
  title: z.string().min(1, 'Enter a title').max(300),
  description: z.string().max(2000).or(z.literal('')),
  priority: z.enum(['LOW', 'MEDIUM', 'HIGH', 'URGENT']),
  assignedToUserId: z.string().or(z.literal('')),
  dueAt: z.string().or(z.literal('')),
});
type Values = z.infer<typeof schema>;

export function CaseTasksTab({ caseId }: { caseId: string }) {
  const { user } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [creating, setCreating] = useState(false);
  const [status, setStatus] = useState('');

  const query = useQuery({
    queryKey: keys.tasks.forCase(caseId, { status }),
    queryFn: () => tasksApi.listForCase(caseId, { status: status || undefined, size: 50 }),
  });

  const members = useQuery({
    queryKey: keys.users.list({ all: true }),
    queryFn: () => usersApi.list({ size: 200 }),
  });

  const { register, handleSubmit, reset, setError, formState: { errors, isSubmitting } } =
    useForm<Values>({
      resolver: zodResolver(schema),
      defaultValues: { title: '', description: '', priority: 'MEDIUM', assignedToUserId: '', dueAt: '' },
    });

  const create = useMutation({
    mutationFn: (values: Values) => tasksApi.create(caseId, {
      title: values.title.trim(),
      description: values.description.trim() || null,
      priority: values.priority,
      assignedToUserId: values.assignedToUserId || null,
      // datetime-local gives a local wall-clock string; the API wants an instant.
      dueAt: values.dueAt ? new Date(values.dueAt).toISOString() : null,
    }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.tasks.all });
      await queryClient.invalidateQueries({ queryKey: ['cases', caseId, 'timeline'] });
      setCreating(false);
      reset();
      toast.success('Task created');
    },
  });

  const changeStatus = useMutation({
    mutationFn: ({ taskId, next }: { taskId: string; next: TaskStatus }) =>
      tasksApi.changeStatus(taskId, next),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.tasks.all });
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

  const nameOf = (userId: string | null | undefined) =>
    userId ? members.data?.items.find((member) => member.id === userId)?.fullName ?? 'Assigned' : 'Unassigned';

  return (
    <Card>
      <CardHeader
        icon="check"
        title="Tasks"
        description="Work to be done on this matter."
        actions={
          <div className="flex items-center gap-2">
            <label htmlFor="task-status" className="sr-only">Filter tasks by status</label>
            <Select id="task-status" value={status} className="h-8 py-0 text-xs"
              onChange={(event) => setStatus(event.target.value)}>
              <option value="">All</option>
              {(['TODO', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'] as TaskStatus[]).map((value) => (
                <option key={value} value={value}>{humanise(value)}</option>
              ))}
            </Select>
            {can(user?.role, 'manageCaseWork') && (
              <Button size="sm" icon="plus" onClick={() => setCreating(true)}>Add task</Button>
            )}
          </div>
        }
      />

      <AsyncSection
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        isEmpty={(data) => data.items.length === 0}
        onRetry={() => query.refetch()}
        skeleton={<TableSkeleton rows={3} columns={3} />}
        empty={<EmptyState compact icon="check" title="No tasks" description="Nothing is outstanding on this matter." />}
      >
        {(data) => (
          <ul className="divide-y divide-ink-100">
            {data.items.map((task) => {
              const options = can(user?.role, 'manageCaseWork') ? nextTaskStatuses(task.status) : [];
              const overdue = task.status !== 'COMPLETED' && task.status !== 'CANCELLED'
                && isPast(task.dueAt);
              return (
                <li key={task.id} className="flex flex-wrap items-start justify-between gap-3 px-4 py-3">
                  <div className="min-w-0 basis-full sm:flex-1 sm:basis-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-sm font-medium text-ink-900">{task.title}</span>
                      <TaskStatusBadge status={task.status} />
                      <Badge tone={PRIORITY_TONE[task.priority]}>{humanise(task.priority)}</Badge>
                      {overdue && <Badge tone="danger">Past due</Badge>}
                    </div>
                    {task.description && (
                      <p className="mt-1 text-sm text-ink-600">{task.description}</p>
                    )}
                    <p className="mt-1 text-xs text-ink-500">
                      {nameOf(task.assignedToUserId)}
                      {task.dueAt && ` · due ${formatDateTime(task.dueAt)}`}
                    </p>
                  </div>
                  {options.length > 0 && (
                    <div className="flex w-full flex-wrap gap-1 sm:w-auto sm:justify-end">
                      {options.map((next) => (
                        <Button
                          key={next} size="sm" variant="secondary"
                          disabled={changeStatus.isPending}
                          onClick={() => changeStatus.mutate({ taskId: task.id, next })}
                        >
                          {next === 'TODO' ? 'Reopen' : `Mark ${humanise(next).toLowerCase()}`}
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

      <Dialog open={creating} onClose={() => setCreating(false)} title="Add a task" footer={<span />}>
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
          <Field label="Description" error={errors.description?.message}>
            {({ id, describedBy, invalid }) => (
              <Textarea id={id} rows={2} aria-describedby={describedBy} invalid={invalid}
                {...register('description')} />
            )}
          </Field>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Priority" error={errors.priority?.message} required>
              {({ id, describedBy, invalid }) => (
                <Select id={id} aria-describedby={describedBy} invalid={invalid} {...register('priority')}>
                  {(['LOW', 'MEDIUM', 'HIGH', 'URGENT'] as TaskPriority[]).map((value) => (
                    <option key={value} value={value}>{humanise(value)}</option>
                  ))}
                </Select>
              )}
            </Field>
            <Field label="Due" error={errors.dueAt?.message}>
              {({ id, describedBy, invalid }) => (
                <Input id={id} type="datetime-local" aria-describedby={describedBy}
                  invalid={invalid} {...register('dueAt')} />
              )}
            </Field>
          </div>
          <Field label="Assign to" error={errors.assignedToUserId?.message}
            hint="Only active staff of your firm can be assigned.">
            {({ id, describedBy, invalid }) => (
              <Select id={id} aria-describedby={describedBy} invalid={invalid}
                {...register('assignedToUserId')}>
                <option value="">Unassigned</option>
                {(members.data?.items ?? [])
                  .filter((member) => member.status === 'ACTIVE' && member.role !== 'CLIENT')
                  .map((member) => (
                    <option key={member.id} value={member.id}>{member.fullName}</option>
                  ))}
              </Select>
            )}
          </Field>
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="secondary" onClick={() => setCreating(false)}
              disabled={isSubmitting}>Cancel</Button>
            <Button type="submit" loading={isSubmitting}>Add task</Button>
          </div>
        </form>
      </Dialog>
    </Card>
  );
}
