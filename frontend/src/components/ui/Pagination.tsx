import { Button } from './primitives';
import type { PageResponse } from '@/types/api';

/**
 * Page controls driven by the backend's own page envelope.
 *
 * Deliberately previous/next rather than numbered pages: the backend reports
 * `totalPages`, but several lists order by a timestamp with an id tiebreak, and deep
 * numbered paging over data that is actively changing lands people on rows they have
 * already seen. Sequential paging matches how these lists are actually read.
 */
export function Pagination<T>({ page, onPageChange, label }: {
  page: Pick<PageResponse<T>, 'page' | 'size' | 'totalItems' | 'totalPages' | 'hasNext'>;
  onPageChange: (page: number) => void;
  label: string;
}) {
  if (page.totalItems === 0) return null;

  const first = page.page * page.size + 1;
  const last = Math.min(first + page.size - 1, page.totalItems);
  const onlyPage = page.totalPages <= 1;

  return (
    <nav
      aria-label={`${label} pagination`}
      className="flex flex-wrap items-center justify-between gap-3 border-t border-ink-200 bg-ink-50/60 px-4 py-2.5"
    >
      <p className="text-xs text-ink-600" aria-live="polite">
        {onlyPage ? (
          <>
            <span className="font-medium text-ink-900">{page.totalItems}</span> {label}
          </>
        ) : (
          <>
            <span className="font-medium text-ink-900">{first}</span>–
            <span className="font-medium text-ink-900">{last}</span> of{' '}
            <span className="font-medium text-ink-900">{page.totalItems}</span> {label}
          </>
        )}
      </p>
      {!onlyPage && (
        <div className="flex items-center gap-2">
          <Button
            variant="secondary" size="sm"
            disabled={page.page === 0}
            onClick={() => onPageChange(page.page - 1)}
          >
            Previous
          </Button>
          <span className="whitespace-nowrap text-xs text-ink-500">
            Page {page.page + 1} of {Math.max(page.totalPages, 1)}
          </span>
          <Button
            variant="secondary" size="sm"
            disabled={!page.hasNext}
            onClick={() => onPageChange(page.page + 1)}
          >
            Next
          </Button>
        </div>
      )}
    </nav>
  );
}
