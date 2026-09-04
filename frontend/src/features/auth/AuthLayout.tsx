import type { ReactNode } from 'react';
import { Wordmark } from '@/components/ui/icons';
import { cn } from '@/lib/cn';

/**
 * The frame for the two pages a signed-out visitor can reach.
 *
 * Two panels on a wide screen: the form on the left where the eye starts, and a quiet
 * statement of what the product is on the right. The right panel is a flat ink surface
 * with one hairline rule — no gradient, no photograph, no stock illustration of a gavel.
 * Below `lg` it disappears entirely rather than stacking, because on a phone the only
 * thing anyone wants from this screen is the password field.
 */
export function AuthLayout({ title, subtitle, children, wide = false }: {
  title: string; subtitle?: string; children: ReactNode; wide?: boolean;
}) {
  return (
    <div className="min-h-screen bg-white lg:grid lg:grid-cols-[minmax(0,1fr),26rem] xl:grid-cols-[minmax(0,1fr),32rem]">
      <div className="flex min-h-screen flex-col justify-center px-5 py-10 sm:px-8 lg:min-h-0 lg:px-12">
        <div className={cn('mx-auto w-full', wide ? 'max-w-lg' : 'max-w-sm')}>
          <div className="mb-8 flex items-center gap-2.5">
            <Wordmark className="h-8 w-8 text-brand-600" />
            <span className="text-lg font-semibold tracking-tight text-ink-900">JurisCore</span>
          </div>

          <h1 className="text-xl font-semibold tracking-tight text-ink-900">{title}</h1>
          {subtitle && <p className="mt-1.5 text-sm text-ink-600">{subtitle}</p>}

          <div className="mt-6">{children}</div>
        </div>
      </div>

      <aside className="hidden bg-ink-900 lg:flex lg:flex-col lg:justify-between lg:px-10 lg:py-12">
        <p className="text-2xs font-semibold uppercase tracking-[0.14em] text-ink-400">
          Legal practice management
        </p>

        <div>
          <p className="text-lg font-medium leading-snug text-white">
            Matters, hearings, deadlines, documents and billing — held in one place, with a
            record of who changed what.
          </p>
          <ul className="mt-8 space-y-3 border-t border-white/10 pt-6 text-sm text-ink-300">
            <li>Court diary and deadline tracking across every matter</li>
            <li>Documents stored against the matter they belong to</li>
            <li>Invoices, recorded payments and outstanding balances</li>
            <li>An append-only audit trail of firm activity</li>
          </ul>
        </div>

        <p className="text-2xs text-ink-400">
          Your firm&rsquo;s data is scoped to your organisation.
        </p>
      </aside>
    </div>
  );
}
