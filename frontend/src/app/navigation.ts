import type { Permission } from '@/lib/auth/roles';
import type { IconName } from '@/components/ui/icons';

export interface NavItem {
  to: string;
  label: string;
  icon: IconName;
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
      { to: '/', label: 'Dashboard', icon: 'dashboard' },
      { to: '/clients', label: 'Clients', icon: 'clients', permission: 'viewCasework' },
      { to: '/cases', label: 'Matters', icon: 'cases', permission: 'viewCasework' },
      { to: '/hearings', label: 'Hearings', icon: 'hearings', permission: 'viewCasework' },
      { to: '/courts', label: 'Courts', icon: 'courts', permission: 'viewCasework' },
    ],
  },
  {
    label: 'Billing',
    items: [
      { to: '/invoices', label: 'Invoices', icon: 'invoices', permission: 'viewBilling' },
      {
        to: '/billing/settings', label: 'Billing settings', icon: 'settings',
        permission: 'viewBillingSettings',
      },
    ],
  },
  {
    label: 'Firm',
    items: [
      { to: '/notifications', label: 'Notifications', icon: 'bell' },
      { to: '/members', label: 'People', icon: 'people', permission: 'viewMembers' },
      { to: '/audit', label: 'Audit trail', icon: 'audit', permission: 'viewAudit' },
    ],
  },
];
