import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

/**
 * A table on wide screens, a list of cards on narrow ones.
 *
 * Not a table that shrinks: a seven-column table at 375px is unusable however it is
 * styled, and horizontal scrolling hides the columns people came for. Each column
 * declares how it appears in the stacked form, so the mobile layout is a real design
 * rather than a fallback.
 */

export interface Column<T> {
  key: string;
  header: string;
  cell: (row: T) => ReactNode;
  /** Hidden on the stacked mobile layout — for columns that repeat the card's title. */
  primary?: boolean;
  className?: string;
  headerClassName?: string;
}

export function DataTable<T>({ columns, rows, rowKey, onRowClick, caption }: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  onRowClick?: (row: T) => void;
  caption: string;
}) {
  return (
    <>
      <div className="hidden overflow-x-auto md:block">
        <table className="min-w-full divide-y divide-ink-200 text-sm">
          <caption className="sr-only">{caption}</caption>
          <thead>
            <tr className="bg-ink-50">
              {columns.map((column) => (
                <th
                  key={column.key} scope="col"
                  className={cn(
                    'px-4 py-2.5 text-left text-xs font-semibold uppercase tracking-wide text-ink-600',
                    column.headerClassName,
                  )}
                >
                  {column.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100 bg-white">
            {rows.map((row) => (
              <tr
                key={rowKey(row)}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                className={cn(onRowClick && 'cursor-pointer hover:bg-ink-50')}
              >
                {columns.map((column) => (
                  <td key={column.key} className={cn('px-4 py-3 align-middle text-ink-800', column.className)}>
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
              className={cn('px-4 py-3', onRowClick && 'cursor-pointer active:bg-ink-50')}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              onKeyDown={onRowClick ? (event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  onRowClick(row);
                }
              } : undefined}
              role={onRowClick ? 'button' : undefined}
              tabIndex={onRowClick ? 0 : undefined}
            >
              {columns.filter((column) => column.primary).map((column) => (
                <div key={column.key} className="text-sm font-medium text-ink-900">
                  {column.cell(row)}
                </div>
              ))}
              <dl className="mt-1.5 grid grid-cols-[auto,1fr] gap-x-3 gap-y-1 text-xs">
                {columns.filter((column) => !column.primary).map((column) => (
                  <div key={column.key} className="contents">
                    <dt className="text-ink-500">{column.header}</dt>
                    <dd className="text-ink-800">{column.cell(row)}</dd>
                  </div>
                ))}
              </dl>
            </div>
          </li>
        ))}
      </ul>
    </>
  );
}
