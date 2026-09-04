import { forwardRef, useId } from 'react';
import type {
  ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes,
  TextareaHTMLAttributes,
} from 'react';
import { cn } from '@/lib/cn';

// ------------------------------------------------------------------ button

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';
type Size = 'sm' | 'md';

const VARIANTS: Record<Variant, string> = {
  primary: 'bg-brand-600 text-white hover:bg-brand-700 disabled:bg-brand-300',
  secondary: 'bg-white text-ink-800 ring-1 ring-inset ring-ink-300 hover:bg-ink-50 disabled:text-ink-400',
  ghost: 'text-ink-700 hover:bg-ink-100 disabled:text-ink-400',
  danger: 'bg-red-600 text-white hover:bg-red-700 disabled:bg-red-300',
};

const SIZES: Record<Size, string> = {
  sm: 'h-8 px-3 text-sm gap-1.5',
  md: 'h-10 px-4 text-sm gap-2',
};

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'primary', size = 'md', loading = false, disabled, children, className, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      // Disabled while saving, so a double-click cannot submit twice.
      disabled={disabled || loading}
      // Screen readers are told the control is working, not just that it stopped responding.
      aria-busy={loading || undefined}
      className={cn(
        'inline-flex items-center justify-center rounded-md font-medium transition-colors',
        'disabled:cursor-not-allowed',
        VARIANTS[variant], SIZES[size], className,
      )}
      {...rest}
    >
      {loading && <Spinner className="h-4 w-4" />}
      {children}
    </button>
  );
});

export function Spinner({ className }: { className?: string }) {
  return (
    <svg className={cn('animate-spin', className)} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor"
        d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
    </svg>
  );
}

// ------------------------------------------------------------------- field

export interface FieldProps {
  label: string;
  error?: string;
  hint?: string;
  required?: boolean;
  children: (props: { id: string; describedBy?: string; invalid: boolean }) => ReactNode;
}

/**
 * A labelled control with its error and hint wired up.
 *
 * The wiring is the reason this exists: the label's `htmlFor`, the control's `id`, its
 * `aria-describedby` and its `aria-invalid` all have to agree, and doing that by hand at
 * every call site is how a form ends up with an error message no screen reader announces.
 */
export function Field({ label, error, hint, required, children }: FieldProps) {
  // `useId` rather than a module counter: the id must be stable across re-renders, or the
  // label and its control drift apart the moment anything above this component updates.
  const id = useId();
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const describedBy = [error ? errorId : null, hint ? hintId : null].filter(Boolean).join(' ');

  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-sm font-medium text-ink-800">
        {label}
        {required && <span aria-hidden="true" className="ml-0.5 text-red-600">*</span>}
        {required && <span className="sr-only"> (required)</span>}
      </label>
      {children({ id, describedBy: describedBy || undefined, invalid: !!error })}
      {hint && !error && <p id={hintId} className="text-xs text-ink-500">{hint}</p>}
      {error && (
        <p id={errorId} role="alert" className="text-xs font-medium text-red-700">{error}</p>
      )}
    </div>
  );
}

const CONTROL = 'block w-full rounded-md border-0 bg-white px-3 py-2 text-sm text-ink-900 '
  + 'ring-1 ring-inset ring-ink-300 placeholder:text-ink-400 '
  + 'focus:ring-2 focus:ring-inset focus:ring-brand-500 disabled:bg-ink-100 disabled:text-ink-500';
const INVALID = 'ring-red-500 focus:ring-red-500';

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean }>(
  function Input({ invalid, className, ...rest }, ref) {
    return <input ref={ref} aria-invalid={invalid || undefined}
      className={cn(CONTROL, invalid && INVALID, className)} {...rest} />;
  },
);

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement> & { invalid?: boolean }>(
  function Textarea({ invalid, className, ...rest }, ref) {
    return <textarea ref={ref} aria-invalid={invalid || undefined}
      className={cn(CONTROL, invalid && INVALID, className)} {...rest} />;
  },
);

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement> & { invalid?: boolean }>(
  function Select({ invalid, className, children, ...rest }, ref) {
    return (
      <select ref={ref} aria-invalid={invalid || undefined}
        className={cn(CONTROL, 'pr-8', invalid && INVALID, className)} {...rest}>
        {children}
      </select>
    );
  },
);

// -------------------------------------------------------------------- card

export function Card({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={cn('rounded-lg bg-white shadow-sm ring-1 ring-ink-200', className)}>
      {children}
    </div>
  );
}

export function CardHeader({ title, description, actions }: {
  title: ReactNode; description?: ReactNode; actions?: ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3 border-b border-ink-200 px-4 py-3 sm:px-5">
      <div className="min-w-0">
        <h2 className="text-sm font-semibold text-ink-900">{title}</h2>
        {description && <p className="mt-0.5 text-xs text-ink-500">{description}</p>}
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </div>
  );
}

// ------------------------------------------------------------------- badge

export type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger';

const TONES: Record<Tone, string> = {
  neutral: 'bg-ink-100 text-ink-700 ring-ink-200',
  info: 'bg-brand-50 text-brand-800 ring-brand-200',
  success: 'bg-emerald-50 text-emerald-800 ring-emerald-200',
  warning: 'bg-amber-50 text-amber-900 ring-amber-200',
  danger: 'bg-red-50 text-red-800 ring-red-200',
};

export function Badge({ tone = 'neutral', children }: { tone?: Tone; children: ReactNode }) {
  return (
    <span className={cn(
      'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
      TONES[tone],
    )}>
      {children}
    </span>
  );
}
