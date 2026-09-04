import { describe, expect, it } from 'vitest';
import { can, isFirmStaff, permissions } from './roles';
import type { Permission } from './roles';
import type { Role } from '@/types/api';

const ALL_PERMISSIONS = Object.keys(permissions) as Permission[];

describe('role capability map', () => {
  /**
   * The requirement is explicit: SUPER_ADMIN and CLIENT must not be handed casework,
   * billing or document functionality merely because a route exists. Asserting it across
   * *every* permission means a permission added later cannot quietly reach them.
   */
  it('gives SUPER_ADMIN and CLIENT nothing in the firm workspace', () => {
    for (const permission of ALL_PERMISSIONS) {
      expect(can('SUPER_ADMIN', permission), `SUPER_ADMIN / ${permission}`).toBe(false);
      expect(can('CLIENT', permission), `CLIENT / ${permission}`).toBe(false);
    }
    expect(isFirmStaff('SUPER_ADMIN')).toBe(false);
    expect(isFirmStaff('CLIENT')).toBe(false);
  });

  it('treats a missing role as having no capability at all', () => {
    for (const permission of ALL_PERMISSIONS) {
      expect(can(undefined, permission), permission).toBe(false);
    }
  });

  it('separates drafting an invoice from issuing one', () => {
    // CLERK drafts, FIRM_ADMIN issues, cancels and records payments — mirroring
    // InvoiceController's @PreAuthorize annotations.
    expect(can('CLERK', 'draftInvoices')).toBe(true);
    expect(can('CLERK', 'administerBilling')).toBe(false);
    expect(can('FIRM_ADMIN', 'administerBilling')).toBe(true);
    expect(can('LAWYER', 'draftInvoices')).toBe(false);
  });

  it('keeps the audit trail to firm administrators', () => {
    const others: Role[] = ['LAWYER', 'CLERK', 'CLIENT', 'SUPER_ADMIN'];
    expect(can('FIRM_ADMIN', 'viewAudit')).toBe(true);
    for (const role of others) expect(can(role, 'viewAudit'), role).toBe(false);
  });

  it('lets every firm role see billing but only some act on it', () => {
    for (const role of ['FIRM_ADMIN', 'LAWYER', 'CLERK'] as Role[]) {
      expect(can(role, 'viewBilling'), role).toBe(true);
    }
    expect(can('LAWYER', 'viewBillingSettings')).toBe(false);
  });

  it('restricts destructive casework actions to a firm administrator', () => {
    for (const permission of ['deleteClients', 'deleteDocuments', 'deleteCaseWork', 'manageAssignments'] as Permission[]) {
      expect(can('FIRM_ADMIN', permission), permission).toBe(true);
      expect(can('LAWYER', permission), permission).toBe(false);
      expect(can('CLERK', permission), permission).toBe(false);
    }
  });
});
