import type { Role } from '@/types/api';

/**
 * What each role may see in the interface.
 *
 * **This is not a security boundary.** Every rule below mirrors a `@PreAuthorize` on the
 * backend, and the backend is what actually enforces it — a user who forges their way to
 * a hidden route gets a 403 from the server, not access. What this file buys is an
 * interface that does not offer people buttons that will fail, which is a usability
 * property, not a safety one.
 *
 * Two roles are deliberately barren:
 *
 * - **CLIENT** reaches nothing here. There is no client portal in the product; an invoice
 *   or a case referencing a client is a firm-side record *about* them.
 * - **SUPER_ADMIN** reaches nothing tenant-scoped either, and not because this list says
 *   so: it has no organization, so `CurrentUser.requireOrganizationId()` refuses it
 *   server-side before any handler body runs. Showing it a case list would produce a
 *   screenful of errors.
 */

export const FIRM_STAFF: readonly Role[] = ['FIRM_ADMIN', 'LAWYER', 'CLERK'];

export const permissions = {
  /** Casework, matters, documents — everything a firm's staff works in day to day. */
  viewCasework: FIRM_STAFF,
  /** Adding and editing clients: administrators and clerks, matching ClientController. */
  manageClients: ['FIRM_ADMIN', 'CLERK'] as const,
  deleteClients: ['FIRM_ADMIN'] as const,
  createCases: FIRM_STAFF,
  changeCaseStatus: ['FIRM_ADMIN', 'LAWYER'] as const,
  manageAssignments: ['FIRM_ADMIN'] as const,
  manageCaseWork: FIRM_STAFF,
  changeHearingStatus: ['FIRM_ADMIN', 'LAWYER'] as const,
  deleteCaseWork: ['FIRM_ADMIN'] as const,
  manageCourts: ['FIRM_ADMIN', 'CLERK'] as const,
  uploadDocuments: FIRM_STAFF,
  deleteDocuments: ['FIRM_ADMIN'] as const,
  viewBilling: FIRM_STAFF,
  draftInvoices: ['FIRM_ADMIN', 'CLERK'] as const,
  /** Issuing, cancelling and recording payments: the three consequential billing verbs. */
  administerBilling: ['FIRM_ADMIN'] as const,
  viewBillingSettings: ['FIRM_ADMIN'] as const,
  viewAudit: ['FIRM_ADMIN'] as const,
  manageMembers: ['FIRM_ADMIN'] as const,
  viewMembers: FIRM_STAFF,
} as const;

export type Permission = keyof typeof permissions;

export function can(role: Role | undefined, permission: Permission): boolean {
  if (!role) return false;
  return (permissions[permission] as readonly Role[]).includes(role);
}

/** True for a role that has an organization and therefore a working tenant API. */
export function isFirmStaff(role: Role | undefined): boolean {
  return !!role && FIRM_STAFF.includes(role);
}
