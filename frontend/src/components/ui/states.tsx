import type { ReactNode } from 'react';
import { ApiError } from '@/lib/api/errors';
import { Button } from './primitives';
import { Icon } from './icons';
import type { IconName } from './icons';
import { cn } from '@/lib/cn';

/**
 * The three things a data region can be instead of "showing data".
 *
 * Every list and panel in the application uses these rather than inventing its own
 * spinner and its own "nothing here" copy, because inconsistency between them is what
 * makes an application feel unfinished.
 */

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn('skeleton', className)} aria-hidden="true" />;
}

/**
 * A table-shaped placeholder, so the layout does not jump when rows arrive.
 *
 * The widths vary per column and per row rather than every bar being identical: a grid of
 * equal grey rectangles reads as a broken layout, while uneven ones read as text.
 */
export function TableSkeleton({ rows = 5, columns = 4 }: { rows?: number; columns?: number }) {
  const widths = ['w-full', 'w-4/5', 'w-2/3', 'w-3/4', 'w-1/2'];
  return (
    <div className="divide-y divide-ink-100" role="status" aria-label="Loading">
      <span className="sr-only">Loading…</span>
      {Array.from({ length: rows }).map((_, row) => (
        <div key={row} className="flex items-center gap-4 px-4 py-3">
          {Array.from({ length: columns }).map((__, column) => (
            <Skeleton
              key={column}
              className={cn(
                'h-3.5',
                column === 0 ? 'w-40 shrink-0' : cn('flex-1', widths[(row + column) % widths.length]),
              )}
            />
          ))}
        </div>
      ))}
    </div>
  );
}

/** A card-shaped placeholder for detail panels and dashboards. */
export function PanelSkeleton({ lines = 4 }: { lines?: number }) {
  return (
    <div className="space-y-3 p-4" role="status" aria-label="Loading">
      <span className="sr-only">Loading…</span>
      <Skeleton className="h-4 w-32" />
      {Array.from({ length: lines }).map((_, index) => (
        <Skeleton key={index} className={index % 2 === 0 ? 'h-3.5 w-full' : 'h-3.5 w-3/4'} />
      ))}
    </div>
  );
}

/**
 * Nothing to show, and what to do about it.
 *
 * The icon is muted and the copy carries the weight — an oversized illustration in a
 * legal case list would be the wrong register entirely.
 */
export function EmptyState({ title, description, action, icon = 'document', compact = false }: {
  title: string; description?: string; action?: ReactNode; icon?: IconName; compact?: boolean;
}) {
  return (
    <div className={cn('text-center', compact ? 'px-4 py-8' : 'px-6 py-14')}>
      <span className="mx-auto mb-3 grid h-10 w-10 place-items-center rounded-full bg-ink-100 text-ink-400">
        <Icon name={icon} className="h-5 w-5" />
      </span>
      <h3 className="text-sm font-semibold text-ink-900">{title}</h3>
      {description && (
        <p className="mx-auto mt-1 max-w-sm text-sm text-ink-500">{description}</p>
      )}
      {action && <div className="mt-4 flex justify-center">{action}</div>}
    </div>
  );
}

/**
 * A failure the user can act on.
 *
 * A 403 is separated from everything else on purpose: "you do not have permission" is a
 * different situation from "something broke", and offering a Retry button for the former
 * invites people to hammer a request that will never succeed.
 */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const apiError = error instanceof ApiError ? error : null;
  const forbidden = apiError?.isForbidden ?? false;
  const offline = apiError?.isNetwork ?? false;
  const message = apiError?.message ?? 'Something went wrong.';

  return (
    <div className="px-6 py-12 text-center" role="alert">
      <span className={cn(
        'mx-auto mb-3 grid h-10 w-10 place-items-center rounded-full',
        forbidden ? 'bg-ink-100 text-ink-500' : 'bg-red-50 text-red-600',
      )}>
        <Icon name={forbidden ? 'info' : 'alert'} className="h-5 w-5" />
      </span>
      <h3 className="text-sm font-semibold text-ink-900">
        {forbidden ? 'You do not have access to this'
          : offline ? 'Cannot reach the server'
            : 'This could not be loaded'}
      </h3>
      <p className="mx-auto mt-1 max-w-md text-sm text-ink-600">{message}</p>
      {apiError?.requestId && (
        <p className="mt-2 font-mono text-2xs text-ink-500">Reference: {apiError.requestId}</p>
      )}
      {onRetry && !forbidden && (
        <div className="mt-4 flex justify-center">
          <Button variant="secondary" size="sm" icon="refresh" onClick={onRetry}>Try again</Button>
        </div>
      )}
    </div>
  );
}

/** Renders whichever of loading / error / empty / content applies. */
export function AsyncSection<T>({
  isLoading, error, data, isEmpty, onRetry, empty, skeleton, children,
}: {
  isLoading: boolean;
  error: unknown;
  data: T | undefined;
  isEmpty?: (data: T) => boolean;
  onRetry?: () => void;
  empty?: ReactNode;
  skeleton?: ReactNode;
  children: (data: T) => ReactNode;
}) {
  if (isLoading) return <>{skeleton ?? <TableSkeleton />}</>;
  if (error) return <ErrorState error={error} onRetry={onRetry} />;
  if (data === undefined) return <>{empty ?? <EmptyState title="Nothing to show" />}</>;
  if (isEmpty?.(data)) return <>{empty ?? <EmptyState title="Nothing to show" />}</>;
  return <>{children(data)}</>;
}
