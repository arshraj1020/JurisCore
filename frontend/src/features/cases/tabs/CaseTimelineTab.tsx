import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { casesApi } from '../api';
import { usersApi } from '@/features/auth/api';
import { keys } from '@/lib/api/queryKeys';
import { useToast } from '@/components/ui/Toast';
import {
  Avatar, Badge, Button, Card, CardHeader, Field, Textarea,
} from '@/components/ui/primitives';
import type { Tone } from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { Pagination } from '@/components/ui/Pagination';
import { formatDateTime, humanise } from '@/lib/format';
import { messageFor } from '@/lib/api/errors';
import type { CaseEventType } from '@/types/api';

/** Timeline entries are grouped by what they are about, not by which module wrote them. */
const TONE: Partial<Record<CaseEventType, Tone>> = {
  CASE_CREATED: 'info',
  CASE_STATUS_CHANGED: 'info',
  LAWYER_ASSIGNED: 'info',
  LAWYER_UNASSIGNED: 'neutral',
  MANUAL_NOTE: 'neutral',
  HEARING_SCHEDULED: 'info',
  HEARING_COMPLETED: 'success',
  HEARING_ADJOURNED: 'warning',
  HEARING_CANCELLED: 'neutral',
  TASK_CREATED: 'neutral',
  TASK_COMPLETED: 'success',
  TASK_CANCELLED: 'neutral',
  DEADLINE_CREATED: 'warning',
  DEADLINE_COMPLETED: 'success',
  DEADLINE_CANCELLED: 'neutral',
  DOCUMENT_UPLOADED: 'info',
  DOCUMENT_DELETED: 'neutral',
};

export function CaseTimelineTab({ caseId }: { caseId: string }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [page, setPage] = useState(0);
  const [note, setNote] = useState('');

  const query = useQuery({
    queryKey: keys.cases.timeline(caseId, page),
    queryFn: () => casesApi.timeline(caseId, page),
  });

  const members = useQuery({
    queryKey: keys.users.list({ all: true }),
    queryFn: () => usersApi.list({ size: 200 }),
  });

  const addNote = useMutation({
    mutationFn: (summary: string) => casesApi.addNote(caseId, summary),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['cases', caseId, 'timeline'] });
      setNote('');
      setPage(0);
      toast.success('Note added to the timeline');
    },
    onError: (error) => toast.error(messageFor(error)),
  });

  const actorName = (userId: string | null | undefined) => {
    if (!userId) return 'System';
    return members.data?.items.find((member) => member.id === userId)?.fullName ?? 'A colleague';
  };

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader title="Add a note" icon="edit"
          description="Notes join the matter's history alongside hearings, tasks and documents." />
        <form
          className="space-y-3 p-4"
          onSubmit={(event) => {
            event.preventDefault();
            if (note.trim()) addNote.mutate(note.trim());
          }}
        >
          <Field label="Note" required>
            {({ id }) => (
              <Textarea id={id} rows={2} value={note} maxLength={1000}
                placeholder="What happened, and what it means for the matter"
                onChange={(event) => setNote(event.target.value)} />
            )}
          </Field>
          <div className="flex justify-end">
            <Button type="submit" size="sm" loading={addNote.isPending} disabled={!note.trim()}>
              Add note
            </Button>
          </div>
        </form>
      </Card>

      <Card>
        <CardHeader title="History" icon="clock"
          description="Newest first. The timeline is append-only." />
        <AsyncSection
          isLoading={query.isPending}
          error={query.error}
          data={query.data}
          isEmpty={(data) => data.items.length === 0}
          onRetry={() => query.refetch()}
          skeleton={<TableSkeleton rows={4} columns={2} />}
          empty={<EmptyState compact icon="clock" title="Nothing recorded yet"
            description="Everything that happens on this matter is written here." />}
        >
          {(data) => (
            <>
              {/* A real timeline: one rail down the left, a node per entry. The rail is
                  drawn on the list item so it stops at the last entry rather than running
                  into the pagination row. */}
              <ol className="px-4 py-3">
                {data.items.map((event, index) => (
                  <li key={event.id} className="relative flex gap-3 pb-4 last:pb-0">
                    {index < data.items.length - 1 && (
                      <span aria-hidden="true"
                        className="absolute bottom-0 left-[0.4375rem] top-5 w-px bg-ink-200" />
                    )}
                    <span aria-hidden="true"
                      className="relative mt-1.5 h-3.5 w-3.5 shrink-0 rounded-full border-2 border-white bg-ink-300 ring-1 ring-ink-200" />
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone={TONE[event.eventType] ?? 'neutral'}>
                          {humanise(event.eventType)}
                        </Badge>
                        <span className="text-xs text-ink-500">
                          {formatDateTime(event.occurredAt)}
                        </span>
                      </div>
                      <p className="mt-1 text-sm text-ink-800">{event.summary}</p>
                      <p className="mt-1 flex items-center gap-1.5 text-xs text-ink-500">
                        <Avatar name={actorName(event.actorUserId)} size="sm"
                          className="h-4 w-4 text-[9px]" />
                        {actorName(event.actorUserId)}
                      </p>
                    </div>
                  </li>
                ))}
              </ol>
              <Pagination page={data} onPageChange={setPage} label="entries" />
            </>
          )}
        </AsyncSection>
      </Card>
    </div>
  );
}
