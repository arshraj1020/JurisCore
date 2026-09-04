import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Icon } from './icons';

export interface Crumb { label: string; to?: string }

/**
 * The title block every page opens with.
 *
 * Breadcrumbs are only rendered when there is somewhere above to go — a single crumb
 * saying "Clients" on the clients page is decoration, not navigation.
 */
export function PageHeader({ title, description, actions, breadcrumbs, meta }: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  breadcrumbs?: Crumb[];
  /** Badges and facts that belong beside the title rather than under it. */
  meta?: ReactNode;
}) {
  return (
    <div className="mb-5">
      {breadcrumbs && breadcrumbs.length > 1 && (
        <nav aria-label="Breadcrumb" className="mb-2">
          <ol className="flex flex-wrap items-center gap-1 text-xs text-ink-500">
            {breadcrumbs.map((crumb, index) => (
              <li key={`${crumb.label}-${index}`} className="flex items-center gap-1">
                {index > 0 && (
                  <Icon name="chevronRight" className="h-3 w-3 text-ink-300" />
                )}
                {crumb.to && index < breadcrumbs.length - 1 ? (
                  <Link to={crumb.to} className="rounded hover:text-ink-800 hover:underline">
                    {crumb.label}
                  </Link>
                ) : (
                  <span
                    aria-current={index === breadcrumbs.length - 1 ? 'page' : undefined}
                    className="truncate font-medium text-ink-700"
                  >
                    {crumb.label}
                  </span>
                )}
              </li>
            ))}
          </ol>
        </nav>
      )}

      <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
            <h1 className="text-lg font-semibold text-ink-900 sm:text-xl">{title}</h1>
            {meta}
          </div>
          {description && (
            <div className="mt-1 text-sm text-ink-600">{description}</div>
          )}
        </div>
        {actions && (
          <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>
        )}
      </div>
    </div>
  );
}
