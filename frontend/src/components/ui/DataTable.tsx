import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { Icon } from './icons';

/**
 * A table on wide screens, a list of records on narrow ones.
 *
 * Not a table that shrinks: a seven-column table at 375px is unusable however it is
 * styled, and horizontal scrolling hides the columns people came for. Each column
 * declares how it appears in the stacked form, so the mobile layout is a real design
 * rather than a fallback.
 *
 * Rows are compact — 13px text, 10px vertical padding — because these are working lists
 * that people scan for a matter number, not marketing tables.
 *
 * <h2>Clickable rows and the keyboard</h2>
 *
 * A row that opens a record is a control, and on desktop it used to be a control only a
 * mouse could reach: `<tr onClick>` with nothing else. Keyboard and screen-reader users
 * could see every matter in the list and open none of them.
 *
 * The fix is to say what the widget actually is. With `onRowClick`, the table becomes
 * `role="grid"` and each row is focusable with Enter and Space bound to the same action —
 * the ARIA grid pattern, which exists for exactly this: tabular data whose rows are
 * interactive. The alternative, wrapping every cell's content in a link, produces one tab
 * stop per cell and a screen-reader announcement per column, which is worse to use even
 * though each part is individually "semantic".
 *
 * Rows that contain their own buttons still work: both handlers ignore events that came
 * from a control inside the row, so pressing Enter on a "Delete" button deletes rather
 * than also opening the record.
 */

export interface Column<T> {
  key: string;
  header: string;
  cell: (row: T) => ReactNode;
  /** The record's identity: shown as the card title on mobile, first column on desktop. */
  primary?: boolean;
  /** Right-aligns the column and its header — for money and counts. */
  numeric?: boolean;
  /** Kept out of the stacked mobile layout entirely (row actions, usually). */
  desktopOnly?: boolean;
  className?: string;
  headerClassName?: string;
}

/**
 * True when the event started on a control *inside* the row, which owns its own behaviour.
 *
 * The row itself is excluded deliberately: the mobile row carries `role="button"`, so a
 * naive `closest()` would match the row and swallow every click on it.
 */
function isFromNestedControl(
  event: { target: EventTarget | null; currentTarget: EventTarget | null },
): boolean {
  const { target, currentTarget } = event;
  if (!(target instanceof Element)) return false;
  const control = target.closest('a, button, input, select, textarea, [role="button"]');
  return control !== null && control !== currentTarget;
}

export function DataTable<T>({ columns, rows, rowKey, onRowClick, caption }: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  onRowClick?: (row: T) => void;
  caption: string;
}) {
  const primary = columns.filter((column) => column.primary);
  const secondary = columns.filter((column) => !column.primary && !column.desktopOnly);

  return (
    <>
      <div className="scroll-x hidden md:block">
        <table
          className="min-w-full text-sm"
          // `grid` only when the rows do something. A plain table of read-only data is a
          // table, and calling it a grid would tell assistive technology to expect
          // interaction that is not there.
          role={onRowClick ? 'grid' : undefined}
        >
          <caption className="sr-only">{caption}</caption>
          <thead>
            <tr className="border-b border-ink-200 bg-ink-50">
              {columns.map((column) => (
                <th
                  key={column.key} scope="col"
                  className={cn(
                    'whitespace-nowrap px-4 py-2 text-left text-2xs font-semibold uppercase tracking-wide text-ink-500',
                    column.numeric && 'text-right',
                    column.headerClassName,
                  )}
                >
                  {column.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {rows.map((row) => (
              <tr
                key={rowKey(row)}
                onClick={onRowClick ? (event) => {
                  if (isFromNestedControl(event)) return;
                  onRowClick(row);
                } : undefined}
                onKeyDown={onRowClick ? (event) => {
                  if (event.key !== 'Enter' && event.key !== ' ') return;
                  // A button inside the row handles its own keys.
                  if (event.target !== event.currentTarget) return;
                  // Space scrolls the page by default, which is not what was asked for.
                  event.preventDefault();
                  onRowClick(row);
                } : undefined}
                tabIndex={onRowClick ? 0 : undefined}
                className={cn(
                  'transition-colors',
                  onRowClick && 'cursor-pointer hover:bg-brand-50/40',
                  // A focus ring that is actually visible: without it a keyboard user can
                  // move through the table with no idea which row is selected.
                  onRowClick && 'focus-visible:bg-brand-50/60 focus-visible:outline-none'
                    + ' focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-500',
                )}
              >
                {columns.map((column) => (
                  <td
                    key={column.key}
                    className={cn(
                      'px-4 py-2.5 align-middle text-ink-700',
                      column.numeric && 'text-right tabular-nums',
                      column.className,
                    )}
                  >
                    {column.cell(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <ul className="divide-y divide-ink-100 md:hidden">
        {rows.map((row) => (
          <li key={rowKey(row)}>
            <div
              className={cn(
                'flex items-start gap-3 px-4 py-3',
                onRowClick && 'cursor-pointer active:bg-ink-50',
              )}
              onClick={onRowClick ? (event) => {
                if (isFromNestedControl(event)) return;
                onRowClick(row);
              } : undefined}
              onKeyDown={onRowClick ? (event) => {
                if (event.key !== 'Enter' && event.key !== ' ') return;
                if (event.target !== event.currentTarget) return;
                event.preventDefault();
                onRowClick(row);
              } : undefined}
              role={onRowClick ? 'button' : undefined}
              tabIndex={onRowClick ? 0 : undefined}
            >
              <div className="min-w-0 flex-1">
                {primary.map((column) => (
                  <div key={column.key} className="text-sm font-medium text-ink-900">
                    {column.cell(row)}
                  </div>
                ))}
                <dl className="mt-2 grid grid-cols-[minmax(5rem,auto),1fr] gap-x-3 gap-y-1 text-xs">
                  {secondary.map((column) => (
                    <div key={column.key} className="contents">
                      <dt className="truncate text-ink-500">{column.header}</dt>
                      <dd className="min-w-0 text-ink-800">{column.cell(row)}</dd>
                    </div>
                  ))}
                </dl>
              </div>
              {onRowClick && (
                <Icon name="chevronRight" className="mt-1 h-4 w-4 shrink-0 text-ink-300" />
              )}
            </div>
          </li>
        ))}
      </ul>
    </>
  );
}
