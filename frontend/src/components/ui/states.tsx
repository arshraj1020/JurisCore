import type { ReactNode } from 'react';
import { ApiError } from '@/lib/api/errors';
import { Button } from './primitives';
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

/** A table-shaped placeholder, so the layout does not jump when rows arrive. */
export function TableSkeleton({ rows = 5, columns = 4 }: { rows?: number; columns?: number }) {
  return (
    <div className="space-y-2 p-4" role="status" aria-label="Loading">
      <span className="sr-only">Loading…</span>
      {Array.from({ length: rows }).map((_, row) => (
        <div key={row} className="flex gap-3">
          {Array.from({ length: columns }).map((__, column) => (
            <Skeleton key={column} className={cn('h-5 flex-1', column === 0 && 'max-w-[14rem]')} />
          ))}
        </div>
      ))}
    </div>
  );
}

export function EmptyState({ title, description, action }: {
  title: string; description?: string; action?: ReactNode;
}) {
  return (
    <div className="px-6 py-12 text-center">
      <h3 className="text-sm font-semibold text-ink-900">{title}</h3>
      {description && <p className="mx-auto mt-1 max-w-md text-sm text-ink-500">{description}</p>}
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
  const message = apiError?.message ?? 'Something went wrong.';

  return (
    <div className="px-6 py-10 text-center" role="alert">
      <h3 className="text-sm font-semibold text-ink-900">
        {forbidden ? 'You do not have access to this' : 'This could not be loaded'}
      </h3>
      <p className="mx-auto mt-1 max-w-md text-sm text-ink-600">{message}</p>
      {apiError?.requestId && (
        <p className="mt-2 text-xs text-ink-400">Reference: {apiError.requestId}</p>
      )}
      {onRetry && !forbidden && (
        <div className="mt-4 flex justify-center">
          <Button variant="secondary" size="sm" onClick={onRetry}>Try again</Button>
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
