import type { Permission } from '@/lib/auth/roles';

export interface NavItem {
  to: string;
  label: string;
  /** Omitted for items every signed-in firm user can reach. */
  permission?: Permission;
}

export interface NavSection {
  label: string;
  items: NavItem[];
}

/**
 * The sidebar, as data.
 *
 * Each entry names the permission that governs it, so navigation and route guards read
 * from the same source. Adding a page without a permission is then a deliberate act
 * rather than an omission nobody notices until a clerk finds an audit link.
 */
export const NAVIGATION: NavSection[] = [
  {
    label: 'Practice',
    items: [
      { to: '/', label: 'Dashboard' },
      { to: '/clients', label: 'Clients', permission: 'viewCasework' },
      { to: '/cases', label: 'Matters', permission: 'viewCasework' },
      { to: '/hearings', label: 'Hearings', permission: 'viewCasework' },
      { to: '/courts', label: 'Courts', permission: 'viewCasework' },
    ],
  },
  {
    label: 'Billing',
    items: [
      { to: '/invoices', label: 'Invoices', permission: 'viewBilling' },
      { to: '/billing/settings', label: 'Billing settings', permission: 'viewBillingSettings' },
    ],
  },
  {
    label: 'Firm',
    items: [
      { to: '/notifications', label: 'Notifications' },
      { to: '/members', label: 'Members', permission: 'viewMembers' },
      { to: '/audit', label: 'Audit trail', permission: 'viewAudit' },
    ],
  },
];
