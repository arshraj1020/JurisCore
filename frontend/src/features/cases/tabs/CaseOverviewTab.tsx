import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { casesApi } from '../api';
import { usersApi } from '@/features/auth/api';
import { keys } from '@/lib/api/queryKeys';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { useToast } from '@/components/ui/Toast';
import {
  Alert, Avatar, Badge, Button, Card, CardHeader, Detail, DetailList, Field, Select,
} from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { Dialog } from '@/components/ui/Dialog';
import { formatDate, formatDateTime } from '@/lib/format';
import { messageFor } from '@/lib/api/errors';
import type { CaseAssignment, LegalCase } from '@/types/api';

/**
 * Removing a lawyer, including the one leading the matter.
 *
 * The backend keeps a hard invariant: a staffed matter has exactly one lead. `unassign`
 * therefore refuses to remove the lead unless the same request names a successor already
 * assigned to the matter, and refuses outright when the lead is the only lawyer on it.
 *
 * The old confirmation dialog knew none of that. It offered "Remove" on the lead, said the
 * matter would be "left without a lead until another is named" — which is a state the
 * platform will not enter — and collected a 400 for its trouble. Presenting an action that
 * is guaranteed to fail is worse than not offering it: the user reasonably concludes the
 * application is broken.
 *
 * So the dialog now asks for what the server needs. Removing a non-lead is a plain
 * confirmation; removing the lead requires picking the successor, and where there is
 * nobody to pick, the action is explained rather than offered. The invariant is unchanged
 * and still enforced server-side — this only stops the interface from lying about it.
 */
function RemoveLawyerDialog({ caseId, assignment, others, nameOf, onClose }: {
  caseId: string;
  assignment: CaseAssignment | null;
  others: CaseAssignment[];
  nameOf: (userId: string) => string;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [successor, setSuccessor] = useState('');
  const [problem, setProblem] = useState<string | null>(null);

  const removingLead = assignment?.lead ?? false;
  const noSuccessor = removingLead && others.length === 0;

  const unassign = useMutation({
    mutationFn: () => casesApi.unassign(
      caseId,
      assignment!.lawyerUserId,
      removingLead ? successor : undefined,
    ),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.cases.assignments(caseId) });
      await queryClient.invalidateQueries({ queryKey: keys.cases.timeline(caseId, 0) });
      toast.success(removingLead
        ? `Removed; ${nameOf(successor)} now leads the matter`
        : 'Lawyer removed from the matter');
      close();
    },
    onError: (error) => setProblem(messageFor(error)),
  });

  function close() {
    setSuccessor('');
    setProblem(null);
    onClose();
  }

  if (!assignment) return null;

  return (
    <Dialog
      open
      onClose={close}
      title="Remove from this matter?"
      description={removingLead
        ? 'They lead this matter, so somebody else has to take it on.'
        : 'They will no longer be assigned to this matter. The change is recorded on its timeline.'}
      footer={<span />}
    >
      <div className="space-y-4">
        {problem && <Alert tone="danger" live>{problem}</Alert>}

        {noSuccessor && (
          <Alert tone="warning" title="There is nobody to take the lead">
            {nameOf(assignment.lawyerUserId)} is the only lawyer on this matter, and a
            matter cannot be left staffed without a lead. Assign another lawyer first, then
            remove this one.
          </Alert>
        )}

        {removingLead && others.length > 0 && (
          <Field label="New lead lawyer" required
            hint="They must already be assigned to this matter.">
            {({ id, describedBy }) => (
              <Select id={id} aria-describedby={describedBy} value={successor}
                onChange={(event) => setSuccessor(event.target.value)}>
                <option value="">Choose who takes the lead…</option>
                {others.map((other) => (
                  <option key={other.id} value={other.lawyerUserId}>
                    {nameOf(other.lawyerUserId)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        )}

        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={close} disabled={unassign.isPending}>
            Cancel
          </Button>
          <Button
            variant="danger"
            loading={unassign.isPending}
            // Disabled exactly when the server would refuse: no successor available, or
            // one is required and has not been chosen.
            disabled={noSuccessor || (removingLead && successor === '')}
            onClick={() => unassign.mutate()}
          >
            Remove
          </Button>
        </div>
      </div>
    </Dialog>
  );
}

function AssignLawyerDialog({ caseId, open, onClose, assigned }: {
  caseId: string; open: boolean; onClose: () => void; assigned: CaseAssignment[];
}) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [lawyerUserId, setLawyerUserId] = useState('');
  const [lead, setLead] = useState(false);

  // Only LAWYER accounts can be assigned; the backend's LawyerDirectory refuses anything
  // else, so offering other roles here would only produce a 400.
  const lawyers = useQuery({
    queryKey: keys.users.list({ role: 'LAWYER' }),
    queryFn: () => usersApi.list({ role: 'LAWYER', size: 100 }),
    enabled: open,
  });

  const assign = useMutation({
    mutationFn: () => casesApi.assign(caseId, lawyerUserId, lead),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.cases.assignments(caseId) });
      await queryClient.invalidateQueries({ queryKey: keys.cases.timeline(caseId, 0) });
      toast.success('Lawyer assigned');
      setLawyerUserId('');
      setLead(false);
      onClose();
    },
    onError: (error) => toast.error(messageFor(error)),
  });

  const alreadyAssigned = new Set(assigned.map((assignment) => assignment.lawyerUserId));
  const available = (lawyers.data?.items ?? []).filter(
    (candidate) => candidate.status === 'ACTIVE' && !alreadyAssigned.has(candidate.id),
  );

  return (
    <Dialog
      open={open} onClose={onClose} title="Assign a lawyer"
      description="Only active lawyers in your firm can be put on a matter."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={assign.isPending}>Cancel</Button>
          <Button onClick={() => assign.mutate()} loading={assign.isPending} disabled={!lawyerUserId}>
            Assign
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <Field label="Lawyer" required>
          {({ id }) => (
            <Select id={id} value={lawyerUserId} disabled={lawyers.isPending}
              onChange={(event) => setLawyerUserId(event.target.value)}>
              <option value="">Choose a lawyer…</option>
              {available.map((candidate) => (
                <option key={candidate.id} value={candidate.id}>{candidate.fullName}</option>
              ))}
            </Select>
          )}
        </Field>
        {available.length === 0 && !lawyers.isPending && (
          <p className="text-sm text-ink-600">
            Every active lawyer is already on this matter.
          </p>
        )}
        <label className="flex items-center gap-2 text-sm text-ink-800">
          <input type="checkbox" checked={lead} onChange={(event) => setLead(event.target.checked)}
            className="h-4 w-4 rounded border-ink-300 text-brand-600" />
          Make them the lead lawyer
        </label>
        <p className="text-xs text-ink-500">
          A matter has at most one lead. Naming a new one moves the role over.
        </p>
      </div>
    </Dialog>
  );
}

export function CaseOverviewTab({ legalCase }: { legalCase: LegalCase }) {
  const { user } = useAuth();
  const [assigning, setAssigning] = useState(false);
  const [removing, setRemoving] = useState<CaseAssignment | null>(null);

  const assignments = useQuery({
    queryKey: keys.cases.assignments(legalCase.id),
    queryFn: () => casesApi.assignments(legalCase.id),
  });

  const members = useQuery({
    queryKey: keys.users.list({ all: true }),
    queryFn: () => usersApi.list({ size: 200 }),
  });

  const nameOf = (userId: string) =>
    members.data?.items.find((member) => member.id === userId)?.fullName ?? userId;

  const mayManage = can(user?.role, 'manageAssignments');

  return (
    <div className="grid items-start gap-4 lg:grid-cols-3">
      <Card className="lg:col-span-1">
        <CardHeader title="Matter details" icon="info" />
        <DetailList columns={2} className="sm:grid-cols-2 lg:grid-cols-1">
          <Detail label="Matter number">
            <span className="font-mono">{legalCase.caseNumber}</span>
          </Detail>
          <Detail label="Opened">{formatDate(legalCase.openedAt)}</Detail>
          <Detail label="Closed">
            {legalCase.closedAt
              ? formatDate(legalCase.closedAt)
              : <span className="text-ink-500">Still open</span>}
          </Detail>
          <Detail label="Last updated">
            <span className="text-ink-600">{formatDateTime(legalCase.updatedAt)}</span>
          </Detail>
          <Detail label="Description" className="sm:col-span-2 lg:col-span-1">
            {legalCase.description
              ? <span className="whitespace-pre-wrap text-ink-700">{legalCase.description}</span>
              : <span className="text-ink-500">No description recorded</span>}
          </Detail>
        </DetailList>
      </Card>

      <Card className="lg:col-span-2">
        <CardHeader
          title="Assigned lawyers"
          icon="people"
          description="Who is working this matter, and who leads it."
          actions={mayManage && (
            <Button size="sm" variant="secondary" icon="plus" onClick={() => setAssigning(true)}>
              Assign lawyer
            </Button>
          )}
        />
        <AsyncSection
          isLoading={assignments.isPending}
          error={assignments.error}
          data={assignments.data}
          isEmpty={(data) => data.length === 0}
          onRetry={() => assignments.refetch()}
          skeleton={<TableSkeleton rows={2} columns={2} />}
          empty={<EmptyState compact icon="people" title="Nobody assigned yet"
            description="Assign a lawyer so the matter shows up in their work." />}
        >
          {(data) => (
            <ul className="divide-y divide-ink-100">
              {data.map((assignment) => (
                <li key={assignment.id}
                  className="flex flex-wrap items-center justify-between gap-3 px-4 py-2.5">
                  <span className="flex min-w-0 items-center gap-2.5">
                    <Avatar name={nameOf(assignment.lawyerUserId)} size="sm" />
                    <span className="min-w-0">
                      <span className="flex items-center gap-2">
                        <span className="truncate text-sm font-medium text-ink-900">
                          {nameOf(assignment.lawyerUserId)}
                        </span>
                        {assignment.lead && <Badge tone="info">Lead</Badge>}
                      </span>
                      <span className="mt-0.5 block text-xs text-ink-500">
                        Assigned {formatDate(assignment.assignedAt)}
                      </span>
                    </span>
                  </span>
                  {mayManage && (
                    <Button size="sm" variant="ghost" onClick={() => setRemoving(assignment)}>
                      Remove
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </AsyncSection>
      </Card>

      <AssignLawyerDialog
        caseId={legalCase.id} open={assigning} onClose={() => setAssigning(false)}
        assigned={assignments.data ?? []}
      />

      <RemoveLawyerDialog
        caseId={legalCase.id}
        assignment={removing}
        others={(assignments.data ?? []).filter(
          (other) => other.lawyerUserId !== removing?.lawyerUserId)}
        nameOf={nameOf}
        onClose={() => setRemoving(null)}
      />
    </div>
  );
}
