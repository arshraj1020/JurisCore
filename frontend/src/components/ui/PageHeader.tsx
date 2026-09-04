import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';

export interface Crumb { label: string; to?: string }

/**
 * The title block every page opens with.
 *
 * Breadcrumbs are only rendered when there is somewhere above to go — a single crumb
 * saying "Clients" on the clients page is decoration, not navigation.
 */
export function PageHeader({ title, description, actions, breadcrumbs }: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  breadcrumbs?: Crumb[];
}) {
  return (
    <div className="mb-4 sm:mb-6">
      {breadcrumbs && breadcrumbs.length > 1 && (
        <nav aria-label="Breadcrumb" className="mb-2">
          <ol className="flex flex-wrap items-center gap-1.5 text-xs text-ink-500">
            {breadcrumbs.map((crumb, index) => (
              <li key={`${crumb.label}-${index}`} className="flex items-center gap-1.5">
                {index > 0 && <span aria-hidden="true">/</span>}
                {crumb.to && index < breadcrumbs.length - 1 ? (
                  <Link to={crumb.to} className="hover:text-ink-800 hover:underline">{crumb.label}</Link>
                ) : (
                  <span aria-current={index === breadcrumbs.length - 1 ? 'page' : undefined}
                    className="text-ink-700">
                    {crumb.label}
                  </span>
                )}
              </li>
            ))}
          </ol>
        </nav>
      )}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h1 className="truncate text-xl font-semibold tracking-tight text-ink-900">{title}</h1>
          {description && <p className="mt-1 text-sm text-ink-600">{description}</p>}
        </div>
        {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </div>
  );
}
