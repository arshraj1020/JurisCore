import type {
  CaseStatus, DeadlineStatus, HearingStatus, InvoiceStatus, TaskStatus,
} from '@/types/api';

/**
 * The backend's state machines, transcribed so the interface never offers an action the
 * server will refuse.
 *
 * **These are not the rules — they are a copy of the rules.** `InvoiceStatusPolicy`,
 * `CaseStatusPolicy` and their siblings on the server decide what actually happens, and
 * they answer 409 for anything they do not allow. What this file prevents is a dropdown
 * that lists "Paid" for a cancelled invoice: an option that exists only to fail is worse
 * than no option.
 *
 * If a transition here and the backend ever disagree, the backend is right and this file
 * is the bug.
 */

const CASE: Record<CaseStatus, CaseStatus[]> = {
  OPEN: ['IN_PROGRESS', 'ON_HOLD', 'CLOSED'],
  IN_PROGRESS: ['ON_HOLD', 'CLOSED'],
  ON_HOLD: ['IN_PROGRESS', 'CLOSED'],
  CLOSED: [],
};

const TASK: Record<TaskStatus, TaskStatus[]> = {
  TODO: ['IN_PROGRESS', 'COMPLETED', 'CANCELLED'],
  IN_PROGRESS: ['TODO', 'COMPLETED', 'CANCELLED'],
  COMPLETED: [],
  CANCELLED: [],
};

const DEADLINE: Record<DeadlineStatus, DeadlineStatus[]> = {
  OPEN: ['COMPLETED', 'CANCELLED'],
  COMPLETED: [],
  CANCELLED: [],
};

const HEARING: Record<HearingStatus, HearingStatus[]> = {
  SCHEDULED: ['COMPLETED', 'ADJOURNED', 'CANCELLED'],
  ADJOURNED: ['SCHEDULED', 'COMPLETED', 'CANCELLED'],
  COMPLETED: [],
  CANCELLED: [],
};

export const nextCaseStatuses = (status: CaseStatus) => CASE[status];
export const nextTaskStatuses = (status: TaskStatus) => TASK[status];
export const nextDeadlineStatuses = (status: DeadlineStatus) => DEADLINE[status];
export const nextHearingStatuses = (status: HearingStatus) => HEARING[status];

// ----------------------------------------------------------------- invoices

/** A DRAFT is a working document; everything past it is financially frozen. */
export const isInvoiceEditable = (status: InvoiceStatus) => status === 'DRAFT';

/** Only a draft can be sent out, and issuing is one-way. */
export const canIssueInvoice = (status: InvoiceStatus) => status === 'DRAFT';

/** PAID is settled history; CANCELLED is already withdrawn. Neither can be cancelled. */
export const canCancelInvoice = (status: InvoiceStatus) =>
  status !== 'PAID' && status !== 'CANCELLED';

/**
 * Money may be recorded against an invoice that has gone out and is not settled.
 *
 * A draft is excluded because nobody has been asked to pay it yet, and the backend says
 * so explicitly rather than merely refusing the transition.
 */
export const canRecordPayment = (status: InvoiceStatus) =>
  status === 'ISSUED' || status === 'PARTIALLY_PAID' || status === 'OVERDUE';

/** Whether an invoice still has an outstanding balance worth showing as due. */
export const hasOutstanding = (status: InvoiceStatus) =>
  status !== 'PAID' && status !== 'CANCELLED' && status !== 'DRAFT';
