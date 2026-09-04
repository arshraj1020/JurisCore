import { describe, expect, it } from 'vitest';
import {
  canCancelInvoice, canIssueInvoice, canRecordPayment, isInvoiceEditable, nextCaseStatuses,
  nextDeadlineStatuses, nextHearingStatuses, nextTaskStatuses,
} from './lifecycle';
import type { CaseStatus, DeadlineStatus, HearingStatus, InvoiceStatus, TaskStatus } from '@/types/api';

/**
 * These assertions are a transcription check, not a design decision: the backend's
 * `*StatusPolicy` classes own the rules, and if one of them changes, the corresponding
 * assertion here should fail so the interface stops offering an action that will 409.
 */

describe('terminal states offer nothing', () => {
  it('closed cases, finished tasks, deadlines and hearings are the end of the line', () => {
    expect(nextCaseStatuses('CLOSED')).toEqual([]);
    expect(nextTaskStatuses('COMPLETED')).toEqual([]);
    expect(nextTaskStatuses('CANCELLED')).toEqual([]);
    expect(nextDeadlineStatuses('COMPLETED')).toEqual([]);
    expect(nextDeadlineStatuses('CANCELLED')).toEqual([]);
    expect(nextHearingStatuses('COMPLETED')).toEqual([]);
    expect(nextHearingStatuses('CANCELLED')).toEqual([]);
  });

  it('never offers a transition back into a terminal state from itself', () => {
    const cases: CaseStatus[] = ['OPEN', 'IN_PROGRESS', 'ON_HOLD', 'CLOSED'];
    for (const status of cases) expect(nextCaseStatuses(status)).not.toContain(status);

    const tasks: TaskStatus[] = ['TODO', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'];
    for (const status of tasks) expect(nextTaskStatuses(status)).not.toContain(status);

    const deadlines: DeadlineStatus[] = ['OPEN', 'COMPLETED', 'CANCELLED'];
    for (const status of deadlines) expect(nextDeadlineStatuses(status)).not.toContain(status);

    const hearings: HearingStatus[] = ['SCHEDULED', 'COMPLETED', 'ADJOURNED', 'CANCELLED'];
    for (const status of hearings) expect(nextHearingStatuses(status)).not.toContain(status);
  });
});

describe('case transitions', () => {
  it('lets an open matter start, pause or close', () => {
    expect(nextCaseStatuses('OPEN')).toEqual(['IN_PROGRESS', 'ON_HOLD', 'CLOSED']);
  });

  it('does not reopen an in-progress matter back to OPEN', () => {
    expect(nextCaseStatuses('IN_PROGRESS')).not.toContain('OPEN');
  });

  it('lets a held matter resume', () => {
    expect(nextCaseStatuses('ON_HOLD')).toContain('IN_PROGRESS');
  });
});

describe('hearing transitions', () => {
  it('lets an adjourned hearing be relisted', () => {
    expect(nextHearingStatuses('ADJOURNED')).toContain('SCHEDULED');
  });
});

describe('invoice lifecycle gates', () => {
  const all: InvoiceStatus[] =
    ['DRAFT', 'ISSUED', 'PARTIALLY_PAID', 'PAID', 'OVERDUE', 'CANCELLED'];

  it('only a draft can be edited or issued', () => {
    for (const status of all) {
      expect(isInvoiceEditable(status), status).toBe(status === 'DRAFT');
      expect(canIssueInvoice(status), status).toBe(status === 'DRAFT');
    }
  });

  it('never offers to take money against a draft or a settled invoice', () => {
    expect(canRecordPayment('DRAFT')).toBe(false);
    expect(canRecordPayment('PAID')).toBe(false);
    expect(canRecordPayment('CANCELLED')).toBe(false);
    expect(canRecordPayment('ISSUED')).toBe(true);
    expect(canRecordPayment('PARTIALLY_PAID')).toBe(true);
    expect(canRecordPayment('OVERDUE')).toBe(true);
  });

  it('never offers to cancel settled history', () => {
    expect(canCancelInvoice('PAID')).toBe(false);
    expect(canCancelInvoice('CANCELLED')).toBe(false);
    expect(canCancelInvoice('DRAFT')).toBe(true);
    expect(canCancelInvoice('OVERDUE')).toBe(true);
  });
});
