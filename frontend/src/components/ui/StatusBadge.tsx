import { Badge } from './primitives';
import type { Tone } from './primitives';
import { humanise } from '@/lib/format';
import type {
  CaseStatus, DeadlineStatus, DocumentStatus, HearingStatus, InvoiceStatus, TaskStatus,
} from '@/types/api';

/**
 * One colour vocabulary across every lifecycle in the product.
 *
 * Green always means settled or done, amber means needs attention, red means failed or
 * overdue, grey means not started or withdrawn. Keeping that consistent across six
 * unrelated state machines is what lets somebody scan a dashboard without a legend.
 */

const INVOICE: Record<InvoiceStatus, Tone> = {
  DRAFT: 'neutral', ISSUED: 'info', PARTIALLY_PAID: 'warning',
  PAID: 'success', OVERDUE: 'danger', CANCELLED: 'neutral',
};
const CASE: Record<CaseStatus, Tone> = {
  OPEN: 'info', IN_PROGRESS: 'info', ON_HOLD: 'warning', CLOSED: 'neutral',
};
const TASK: Record<TaskStatus, Tone> = {
  TODO: 'neutral', IN_PROGRESS: 'info', COMPLETED: 'success', CANCELLED: 'neutral',
};
const DEADLINE: Record<DeadlineStatus, Tone> = {
  OPEN: 'info', COMPLETED: 'success', CANCELLED: 'neutral',
};
const HEARING: Record<HearingStatus, Tone> = {
  SCHEDULED: 'info', COMPLETED: 'success', ADJOURNED: 'warning', CANCELLED: 'neutral',
};
const DOCUMENT: Record<DocumentStatus, Tone> = {
  UPLOADING: 'warning', AVAILABLE: 'success', DELETED: 'neutral', FAILED: 'danger',
};

export const InvoiceStatusBadge = ({ status }: { status: InvoiceStatus }) =>
  <Badge tone={INVOICE[status]}>{humanise(status)}</Badge>;
export const CaseStatusBadge = ({ status }: { status: CaseStatus }) =>
  <Badge tone={CASE[status]}>{humanise(status)}</Badge>;
export const TaskStatusBadge = ({ status }: { status: TaskStatus }) =>
  <Badge tone={TASK[status]}>{humanise(status)}</Badge>;
export const DeadlineStatusBadge = ({ status }: { status: DeadlineStatus }) =>
  <Badge tone={DEADLINE[status]}>{humanise(status)}</Badge>;
export const HearingStatusBadge = ({ status }: { status: HearingStatus }) =>
  <Badge tone={HEARING[status]}>{humanise(status)}</Badge>;
export const DocumentStatusBadge = ({ status }: { status: DocumentStatus }) =>
  <Badge tone={DOCUMENT[status]}>{humanise(status)}</Badge>;
