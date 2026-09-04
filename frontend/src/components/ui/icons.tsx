import { cn } from '@/lib/cn';

/**
 * The icons the interface uses, drawn inline.
 *
 * An icon package would be a dependency carrying a thousand glyphs to ship the eighteen
 * this application needs, and a font would be a network request for the same. These are
 * a single stroke weight on a 24-unit grid so they sit together, and every one is
 * `aria-hidden`: an icon here always accompanies a label or a control that has its own
 * accessible name, and an icon that announces itself alongside that name is noise.
 */

export type IconName =
  | 'dashboard' | 'clients' | 'cases' | 'hearings' | 'courts' | 'invoices' | 'settings'
  | 'bell' | 'people' | 'audit' | 'menu' | 'close' | 'chevronRight' | 'chevronDown'
  | 'search' | 'plus' | 'upload' | 'download' | 'document' | 'trash' | 'edit' | 'check'
  | 'alert' | 'info' | 'warning' | 'clock' | 'calendar' | 'money' | 'logout' | 'user'
  | 'filter' | 'external' | 'refresh' | 'eye' | 'eyeOff' | 'scales';

const PATHS: Record<IconName, JSX.Element> = {
  dashboard: <path d="M4 13h6V4H4v9Zm0 7h6v-5H4v5Zm10 0h6v-9h-6v9Zm0-16v5h6V4h-6Z" />,
  clients: <path d="M16 19v-1a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v1M9.5 10.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7ZM21 19v-1a4 4 0 0 0-3-3.87M16.5 3.8a4 4 0 0 1 0 7.4" />,
  cases: <path d="M4 8h16a1 1 0 0 1 1 1v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a1 1 0 0 1 1-1Zm5 0V6a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2M3 13h18" />,
  hearings: <path d="M12 3v3m-7 3h14M6.5 9 4 15h5L6.5 9Zm11 0L15 15h5l-2.5-6ZM4 15a2.5 2.5 0 0 0 5 0m6 0a2.5 2.5 0 0 0 5 0M9 21h6m-3-15v15" />,
  courts: <path d="M3 21h18M4 21V10m4 11V10m4 11V10m4 11V10m4 11V10M2 10h20L12 4 2 10Z" />,
  invoices: <path d="M7 3h10a1 1 0 0 1 1 1v17l-3-2-3 2-3-2-3 2V4a1 1 0 0 1 1-1Zm2 5h6M9 12h6M9 16h3" />,
  settings: <path d="M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm7.4-3a7.4 7.4 0 0 0-.1-1.2l2-1.5-2-3.4-2.3.9a7.5 7.5 0 0 0-2-1.2L14.6 3H9.4L9 5.6a7.5 7.5 0 0 0-2 1.2l-2.3-.9-2 3.4 2 1.5a7.4 7.4 0 0 0 0 2.4l-2 1.5 2 3.4 2.3-.9a7.5 7.5 0 0 0 2 1.2l.4 2.6h5.2l.4-2.6a7.5 7.5 0 0 0 2-1.2l2.3.9 2-3.4-2-1.5c.07-.4.1-.8.1-1.2Z" />,
  bell: <path d="M15 17h5l-1.4-1.4A2 2 0 0 1 18 14.2V11a6 6 0 1 0-12 0v3.2a2 2 0 0 1-.6 1.4L4 17h5m6 0a3 3 0 1 1-6 0m6 0H9" />,
  people: <path d="M17 20v-1a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v1M10 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm11 9v-1a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" />,
  audit: <path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2M9 5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2M9 5a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2m-6 7h6m-6 4h4" />,
  menu: <path d="M4 7h16M4 12h16M4 17h16" />,
  close: <path d="m6 6 12 12M18 6 6 18" />,
  chevronRight: <path d="m9 5 7 7-7 7" />,
  chevronDown: <path d="m6 9 6 6 6-6" />,
  search: <path d="M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14Zm5-2 5 5" />,
  plus: <path d="M12 5v14M5 12h14" />,
  upload: <path d="M12 16V4m0 0L8 8m4-4 4 4M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" />,
  download: <path d="M12 4v12m0 0 4-4m-4 4-4-4M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" />,
  document: <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8m-5-5 5 5m-5-5v5h5" />,
  trash: <path d="M4 7h16M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2m3 0v12a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V7m4 4v6m4-6v6" />,
  edit: <path d="M4 20h4L20 8l-4-4L4 16v4Zm11-15 4 4" />,
  check: <path d="m5 13 4 4L19 7" />,
  alert: <path d="M12 8v5m0 3.5v.5M10.3 3.9 2.6 17a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z" />,
  info: <path d="M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Zm0-9v5m0-8.5v.5" />,
  warning: <path d="M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Zm0-13v5m0 3.5v.5" />,
  clock: <path d="M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Zm0-14v5l3.5 2" />,
  calendar: <path d="M4 8h16M7 4v3m10-3v3M5 20h14a1 1 0 0 0 1-1V7a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v12a1 1 0 0 0 1 1Z" />,
  money: <path d="M3 7h18a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1Zm9 8.5a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z" />,
  logout: <path d="M15 17l5-5-5-5m5 5H9m1 8H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h4" />,
  user: <path d="M20 21v-2a5 5 0 0 0-5-5H9a5 5 0 0 0-5 5v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z" />,
  filter: <path d="M3 5h18l-7 8v6l-4 2v-8L3 5Z" />,
  external: <path d="M14 5h5v5m0-5-8 8M18 14v5a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1h5" />,
  refresh: <path d="M20 12a8 8 0 1 1-2.5-5.8M20 4v5h-5" />,
  eye: <path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Zm9.5 2.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z" />,
  eyeOff: <path d="M3 3l18 18M10.6 6.1A8.6 8.6 0 0 1 12 6c6 0 9.5 6 9.5 6a15.6 15.6 0 0 1-3 3.6M6.3 8.5A15.4 15.4 0 0 0 2.5 12S6 18 12 18a8.9 8.9 0 0 0 3.3-.6M9.9 9.9a3 3 0 0 0 4.2 4.2" />,
  scales: <path d="M12 4v16m-6 0h12M8 8h8M6 8l-3 6h6L6 8Zm12 0-3 6h6l-3-6Z" />,
};

export interface IconProps {
  name: IconName;
  className?: string;
}

export function Icon({ name, className }: IconProps) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      className={cn('h-5 w-5 shrink-0', className)}
    >
      {PATHS[name]}
    </svg>
  );
}

/** The mark, drawn rather than imported. Scales with `className`. */
export function Wordmark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"
      className={cn('h-6 w-6', className)}>
      <rect width="24" height="24" rx="5" fill="currentColor" />
      <path
        d="M12 5.4v13.2M7.6 18.6h8.8M8.4 8.8h7.2"
        stroke="white" strokeWidth="1.5" strokeLinecap="round" opacity="0.95"
      />
      <path
        d="M8.4 8.8 6 14.2h4.8L8.4 8.8Zm7.2 0-2.4 5.4H18l-2.4-5.4Z"
        stroke="white" strokeWidth="1.4" strokeLinejoin="round" fill="none" opacity="0.95"
      />
    </svg>
  );
}
