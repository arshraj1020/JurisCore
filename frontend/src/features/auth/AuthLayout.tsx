import type { ReactNode } from 'react';

export function AuthLayout({ title, subtitle, children }: {
  title: string; subtitle?: string; children: ReactNode;
}) {
  return (
    <div className="flex min-h-screen flex-col justify-center bg-ink-50 px-4 py-12">
      <div className="mx-auto w-full max-w-sm">
        <div className="mb-6 flex items-center gap-2">
          <span className="grid h-9 w-9 place-items-center rounded bg-brand-600 text-sm font-bold text-white">
            JC
          </span>
          <span className="text-lg font-semibold tracking-tight text-ink-900">JurisCore</span>
        </div>
        <div className="rounded-lg bg-white p-6 shadow-sm ring-1 ring-ink-200">
          <h1 className="text-lg font-semibold text-ink-900">{title}</h1>
          {subtitle && <p className="mb-5 mt-1 text-sm text-ink-600">{subtitle}</p>}
          {children}
        </div>
      </div>
    </div>
  );
}
