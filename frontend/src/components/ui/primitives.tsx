import { forwardRef, useId, useState } from 'react';
import type {
  ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes,
  TextareaHTMLAttributes,
} from 'react';
import { cn } from '@/lib/cn';
import { Icon } from './icons';
import type { IconName } from './icons';

// ------------------------------------------------------------------ button

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'subtle';
type Size = 'xs' | 'sm' | 'md';

/**
 * Four weights of emphasis, and they are used consistently: one `primary` per screen
 * region at most, `secondary` for the alternatives beside it, `ghost` for row-level
 * actions that should not compete with the content, `danger` only where something is
 * destroyed or withdrawn.
 */
/**
 * Disabled means grey, never a paler version of the brand colour.
 *
 * A washed-out coloured fill still reads as "press me" and, at the contrast it leaves,
 * the label is barely legible — WCAG exempts disabled controls from contrast, which is a
 * licence to ship something unreadable rather than a reason to. A grey fill with readable
 * text is unmistakably inert *and* still says what the button would do.
 */
const DISABLED = 'disabled:bg-ink-100 disabled:text-ink-500 disabled:shadow-none '
  + 'disabled:ring-1 disabled:ring-inset disabled:ring-ink-200';

const VARIANTS: Record<Variant, string> = {
  primary:
    `bg-brand-600 text-white shadow-raised hover:bg-brand-700 active:bg-brand-800 ${DISABLED}`,
  secondary:
    'bg-white text-ink-800 shadow-raised ring-1 ring-inset ring-ink-300 hover:bg-ink-50 '
    + `active:bg-ink-100 ${DISABLED}`,
  subtle:
    `bg-ink-100 text-ink-800 hover:bg-ink-200 active:bg-ink-300 ${DISABLED}`,
  ghost:
    'text-ink-600 hover:bg-ink-100 hover:text-ink-900 active:bg-ink-200 '
    + 'disabled:bg-transparent disabled:text-ink-500',
  danger:
    `bg-red-600 text-white shadow-raised hover:bg-red-700 active:bg-red-800 ${DISABLED}`,
};

const SIZES: Record<Size, string> = {
  xs: 'h-7 px-2 text-xs gap-1',
  sm: 'h-8 px-2.5 text-sm gap-1.5',
  md: 'h-9 px-3.5 text-sm gap-1.5',
};

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
  /** Drawn before the label. Purely decorative — the label is the accessible name. */
  icon?: IconName;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = 'primary', size = 'md', loading = false, icon, disabled, children, className,
    type = 'button', ...rest
  },
  ref,
) {
  const glyph = size === 'xs' ? 'h-3.5 w-3.5' : 'h-4 w-4';
  return (
    <button
      ref={ref}
      // Defaulting to `button` rather than `submit`: a button inside a form that was meant
      // to open a dialog and instead submits it is a bug that is easy to ship and hard to
      // spot. Submit buttons say so.
      type={type}
      // Disabled while saving, so a double-click cannot submit twice.
      disabled={disabled || loading}
      // Screen readers are told the control is working, not just that it stopped responding.
      aria-busy={loading || undefined}
      className={cn(
        'inline-flex select-none items-center justify-center whitespace-nowrap rounded-md',
        'font-medium transition-colors duration-100 disabled:cursor-not-allowed',
        VARIANTS[variant], SIZES[size], className,
      )}
      {...rest}
    >
      {loading ? <Spinner className={glyph} />
        : icon ? <Icon name={icon} className={glyph} /> : null}
      {children}
    </button>
  );
});

/** A square button whose only content is an icon, so it must carry its own label. */
export const IconButton = forwardRef<
HTMLButtonElement,
Omit<ButtonProps, 'icon' | 'children'> & { icon: IconName; label: string }
>(function IconButton({ icon, label, variant = 'ghost', size = 'md', className, ...rest }, ref) {
  return (
    <Button
      ref={ref} variant={variant} size={size} aria-label={label} title={label}
      className={cn('px-0', size === 'md' ? 'w-9' : size === 'sm' ? 'w-8' : 'w-7', className)}
      {...rest}
    >
      <Icon name={icon} className={size === 'xs' ? 'h-3.5 w-3.5' : 'h-4 w-4'} />
    </Button>
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
  /** Hides the visible label but keeps it for assistive technology. */
  srOnlyLabel?: boolean;
  children: (props: { id: string; describedBy?: string; invalid: boolean }) => ReactNode;
}

/**
 * A labelled control with its error and hint wired up.
 *
 * The wiring is the reason this exists: the label's `htmlFor`, the control's `id`, its
 * `aria-describedby` and its `aria-invalid` all have to agree, and doing that by hand at
 * every call site is how a form ends up with an error message no screen reader announces.
 */
export function Field({
  label, error, hint, required, srOnlyLabel, children,
}: FieldProps) {
  // `useId` rather than a module counter: the id must be stable across re-renders, or the
  // label and its control drift apart the moment anything above this component updates.
  const id = useId();
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const describedBy = [error ? errorId : null, hint ? hintId : null].filter(Boolean).join(' ');

  return (
    <div className="space-y-1.5">
      <label
        htmlFor={id}
        className={cn(
          'block text-sm font-medium text-ink-800',
          srOnlyLabel && 'sr-only',
        )}
      >
        {label}
        {required && <span aria-hidden="true" className="ml-0.5 text-red-600">*</span>}
        {required && <span className="sr-only"> (required)</span>}
      </label>
      {children({ id, describedBy: describedBy || undefined, invalid: !!error })}
      {hint && !error && <p id={hintId} className="text-xs text-ink-500">{hint}</p>}
      {error && (
        <p id={errorId} role="alert" className="flex items-start gap-1 text-xs font-medium text-red-700">
          <Icon name="alert" className="mt-px h-3.5 w-3.5 shrink-0" />
          <span>{error}</span>
        </p>
      )}
    </div>
  );
}

const CONTROL = 'block w-full rounded-md border-0 bg-white px-2.5 py-1.5 text-sm text-ink-900 '
  + 'shadow-card ring-1 ring-inset ring-ink-300 placeholder:text-ink-400 '
  + 'transition-shadow focus:ring-2 focus:ring-inset focus:ring-brand-500 '
  + 'disabled:bg-ink-100 disabled:text-ink-500 disabled:shadow-none';
const INVALID = 'ring-red-400 focus:ring-red-500';

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean }>(
  function Input({ invalid, className, ...rest }, ref) {
    return <input ref={ref} aria-invalid={invalid || undefined}
      className={cn(CONTROL, 'h-9', invalid && INVALID, className)} {...rest} />;
  },
);

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement> & { invalid?: boolean }>(
  function Textarea({ invalid, className, ...rest }, ref) {
    return <textarea ref={ref} aria-invalid={invalid || undefined}
      className={cn(CONTROL, 'py-2', invalid && INVALID, className)} {...rest} />;
  },
);

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement> & { invalid?: boolean }>(
  function Select({ invalid, className, children, ...rest }, ref) {
    return (
      <select ref={ref} aria-invalid={invalid || undefined}
        className={cn(CONTROL, 'h-9 cursor-pointer pr-8', invalid && INVALID, className)} {...rest}>
        {children}
      </select>
    );
  },
);

/** An input with a leading search glyph. The label still comes from `Field`. */
export const SearchInput = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function SearchInput({ className, ...rest }, ref) {
    return (
      <div className="relative">
        <Icon name="search"
          className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-400" />
        <input ref={ref} type="search"
          className={cn(CONTROL, 'h-9 pl-8', className)} {...rest} />
      </div>
    );
  },
);

/**
 * A password field with a reveal toggle.
 *
 * Masking exists to stop somebody reading the screen over a shoulder, not to stop the
 * person typing from checking what they typed — and a password nobody can verify is a
 * password people paste from somewhere less safe. The toggle is a real button so it is
 * reachable from the keyboard, and it never leaves the field in a revealed state across
 * navigations because the component unmounts with the form.
 */
export const PasswordInput = forwardRef<
HTMLInputElement,
Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> & { invalid?: boolean }
>(function PasswordInput({ invalid, className, ...rest }, ref) {
  const [revealed, setRevealed] = useState(false);
  return (
    <div className="relative">
      <input
        ref={ref}
        type={revealed ? 'text' : 'password'}
        aria-invalid={invalid || undefined}
        className={cn(CONTROL, 'h-9 pr-10', invalid && INVALID, className)}
        {...rest}
      />
      <button
        type="button"
        onClick={() => setRevealed((value) => !value)}
        aria-label={revealed ? 'Hide the password' : 'Reveal the password'}
        aria-pressed={revealed}
        title={revealed ? 'Hide' : 'Reveal'}
        className="absolute right-1 top-1/2 grid h-7 w-8 -translate-y-1/2 place-items-center rounded text-ink-400 transition-colors hover:text-ink-700"
      >
        <Icon name={revealed ? 'eyeOff' : 'eye'} className="h-4 w-4" />
      </button>
    </div>
  );
});

/**
 * A checkbox styled as a switch, still a checkbox.
 *
 * The role stays `checkbox` deliberately: a `role="switch"` implemented on a div is a
 * common way to lose keyboard support, and nothing here needs the distinction.
 */
export function Toggle({ label, description, checked, disabled, onChange }: {
  label: string;
  description?: string;
  checked: boolean;
  disabled?: boolean;
  onChange: (checked: boolean) => void;
}) {
  const id = useId();
  return (
    <div className="flex items-start justify-between gap-4">
      <div className="min-w-0">
        <label htmlFor={id} className="block text-sm font-medium text-ink-900">{label}</label>
        {description && <p className="mt-0.5 text-xs text-ink-500">{description}</p>}
      </div>
      <span className="relative inline-flex shrink-0 pt-0.5">
        <input
          id={id}
          type="checkbox"
          className="peer h-5 w-9 cursor-pointer appearance-none rounded-full bg-ink-300 transition-colors checked:bg-brand-600 disabled:cursor-not-allowed disabled:opacity-50"
          checked={checked}
          disabled={disabled}
          onChange={(event) => onChange(event.target.checked)}
        />
        <span
          aria-hidden="true"
          className="pointer-events-none absolute left-0.5 top-1 h-4 w-4 rounded-full bg-white shadow transition-transform peer-checked:translate-x-4"
        />
      </span>
    </div>
  );
}

// -------------------------------------------------------------------- card

export function Card({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <section className={cn(
      'overflow-hidden rounded-lg border border-ink-200 bg-white shadow-card',
      className,
    )}>
      {children}
    </section>
  );
}

export function CardHeader({ title, description, actions, icon }: {
  title: ReactNode; description?: ReactNode; actions?: ReactNode; icon?: IconName;
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 border-b border-ink-200 bg-ink-50/60 px-4 py-2.5">
      <div className="flex min-w-0 items-start gap-2">
        {icon && <Icon name={icon} className="mt-px h-4 w-4 text-ink-400" />}
        <div className="min-w-0">
          <h2 className="text-sm font-semibold text-ink-900">{title}</h2>
          {description && <p className="mt-0.5 text-xs text-ink-500">{description}</p>}
        </div>
      </div>
      {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

/** The padded body of a card, for content that is not a table or a list. */
export function CardBody({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn('p-4', className)}>{children}</div>;
}

/**
 * The filter row that sits above every list.
 *
 * One component so the spacing, the border and the wrapping behaviour are identical on
 * seven pages rather than seven slightly different rows.
 */
export function Toolbar({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={cn(
      'flex flex-wrap items-end gap-2 border-b border-ink-200 bg-white px-3 py-2.5',
      // Below `sm` each control takes the full row: two selects of different intrinsic
      // widths stacked on a phone look like a mistake rather than a filter bar.
      'max-sm:[&>*]:w-full',
      className,
    )}>
      {children}
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

const DOTS: Record<Tone, string> = {
  neutral: 'bg-ink-400',
  info: 'bg-brand-500',
  success: 'bg-emerald-500',
  warning: 'bg-amber-500',
  danger: 'bg-red-500',
};

/**
 * A status chip. With `dot`, it carries a coloured marker as well as the tinted
 * background — colour alone should never be the only thing distinguishing two states,
 * and the label always says which state it is.
 */
export function Badge({ tone = 'neutral', dot = false, children }: {
  tone?: Tone; dot?: boolean; children: ReactNode;
}) {
  return (
    <span className={cn(
      'inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5',
      'text-xs font-medium ring-1 ring-inset',
      TONES[tone],
    )}>
      {dot && <span aria-hidden="true" className={cn('h-1.5 w-1.5 rounded-full', DOTS[tone])} />}
      {children}
    </span>
  );
}

/** A count beside a label — the small grey number in a tab or a section heading. */
export function Count({ value }: { value: number | undefined }) {
  if (value === undefined) return null;
  return (
    <span className="rounded-full bg-ink-100 px-1.5 py-0.5 text-2xs font-semibold text-ink-600">
      {value}
    </span>
  );
}

// ------------------------------------------------------------------- alert

const ALERT_TONES: Record<Tone, { box: string; icon: IconName; iconClass: string }> = {
  neutral: { box: 'bg-ink-50 text-ink-800 ring-ink-200', icon: 'info', iconClass: 'text-ink-500' },
  info: { box: 'bg-brand-50 text-brand-900 ring-brand-200', icon: 'info', iconClass: 'text-brand-600' },
  success: { box: 'bg-emerald-50 text-emerald-900 ring-emerald-200', icon: 'check', iconClass: 'text-emerald-600' },
  warning: { box: 'bg-amber-50 text-amber-900 ring-amber-200', icon: 'warning', iconClass: 'text-amber-600' },
  danger: { box: 'bg-red-50 text-red-900 ring-red-200', icon: 'alert', iconClass: 'text-red-600' },
};

/**
 * An inline message. `role="alert"` is opt-in, because announcing a permanently-visible
 * explanatory note every time the surrounding component re-renders is worse than silence;
 * form submission failures pass `live`, standing notes do not.
 */
export function Alert({ tone = 'info', title, children, live = false, className }: {
  tone?: Tone; title?: ReactNode; children?: ReactNode; live?: boolean; className?: string;
}) {
  const style = ALERT_TONES[tone];
  return (
    <div
      role={live ? 'alert' : undefined}
      className={cn(
        'flex items-start gap-2.5 rounded-md px-3 py-2.5 text-sm ring-1 ring-inset',
        style.box, className,
      )}
    >
      <Icon name={style.icon} className={cn('mt-px h-4 w-4', style.iconClass)} />
      <div className="min-w-0 flex-1">
        {title && <p className="font-medium">{title}</p>}
        {children && <div className={cn(title ? 'mt-0.5' : null, 'text-sm')}>{children}</div>}
      </div>
    </div>
  );
}

// -------------------------------------------------------- description list

/** A label/value pair, used wherever a record's details are laid out. */
export function Detail({ label, children, className }: {
  label: ReactNode; children: ReactNode; className?: string;
}) {
  return (
    <div className={className}>
      <dt className="text-2xs font-semibold uppercase tracking-wide text-ink-500">{label}</dt>
      <dd className="mt-1 text-sm text-ink-900">{children}</dd>
    </div>
  );
}

export function DetailList({ children, columns = 2, className }: {
  children: ReactNode; columns?: 2 | 3; className?: string;
}) {
  return (
    <dl className={cn(
      'grid gap-x-6 gap-y-4 p-4',
      columns === 3 ? 'sm:grid-cols-2 lg:grid-cols-3' : 'sm:grid-cols-2',
      className,
    )}>
      {children}
    </dl>
  );
}

// ------------------------------------------------------------------ avatar

/** Initials on a tinted disc. Decorative: the name is always rendered beside it. */
export function Avatar({ name, size = 'md', className }: {
  name: string; size?: 'sm' | 'md'; className?: string;
}) {
  const initials = name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('') || '?';

  return (
    <span
      aria-hidden="true"
      className={cn(
        'grid shrink-0 place-items-center rounded-full bg-brand-100 font-semibold text-brand-800',
        size === 'sm' ? 'h-6 w-6 text-2xs' : 'h-8 w-8 text-xs',
        className,
      )}
    >
      {initials}
    </span>
  );
}
