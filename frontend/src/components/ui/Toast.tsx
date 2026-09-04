import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { Icon } from './icons';
import type { IconName } from './icons';

/**
 * Transient feedback, used sparingly.
 *
 * Toasts are for the case where something succeeded and the result is not visible on
 * screen — a payment recorded, an invoice issued. Anything the user can already see the
 * result of does not get one, and errors that belong to a form are shown on the form
 * rather than thrown into a corner of the viewport.
 */

type ToastTone = 'success' | 'error' | 'info';
interface Toast { id: number; tone: ToastTone; message: string }

interface ToastApi {
  success: (message: string) => void;
  error: (message: string) => void;
  info: (message: string) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

/**
 * Light surfaces with a coloured rail rather than solid blocks of colour: a full-bleed
 * red panel over a working screen reads as an outage, and most of these say "saved".
 */
const TONES: Record<ToastTone, { box: string; icon: IconName; glyph: string }> = {
  success: { box: 'border-l-emerald-500', icon: 'check', glyph: 'text-emerald-600' },
  error: { box: 'border-l-red-500', icon: 'alert', glyph: 'text-red-600' },
  info: { box: 'border-l-brand-500', icon: 'info', glyph: 'text-brand-600' },
};

let toastSeq = 0;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const push = useCallback((tone: ToastTone, message: string) => {
    const id = (toastSeq += 1);
    setToasts((current) => [...current, { id, tone, message }]);
    window.setTimeout(() => {
      setToasts((current) => current.filter((toast) => toast.id !== id));
    }, tone === 'error' ? 7000 : 4000);
  }, []);

  const api = useMemo<ToastApi>(() => ({
    success: (message) => push('success', message),
    error: (message) => push('error', message),
    info: (message) => push('info', message),
  }), [push]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      {/* Polite, not assertive: these announce completed work, and interrupting a
          screen-reader user mid-sentence to say "Saved" is worse than waiting. */}
      <div
        aria-live="polite" aria-atomic="false"
        className="pointer-events-none fixed inset-x-0 bottom-0 z-50 flex flex-col items-center gap-2 p-4 sm:items-end"
      >
        {toasts.map((toast) => (
          <div
            key={toast.id}
            className={cn(
              'pointer-events-auto flex max-w-sm animate-slide-up items-start gap-2.5 rounded-md',
              'border border-ink-200 border-l-4 bg-white px-3.5 py-2.5 text-sm text-ink-800 shadow-pop',
              TONES[toast.tone].box,
            )}
          >
            <Icon name={TONES[toast.tone].icon}
              className={cn('mt-px h-4 w-4', TONES[toast.tone].glyph)} />
            <span className="font-medium">{toast.message}</span>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const context = useContext(ToastContext);
  if (!context) throw new Error('useToast must be used inside a ToastProvider');
  return context;
}
