import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

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

const TONES: Record<ToastTone, string> = {
  success: 'bg-emerald-600',
  error: 'bg-red-600',
  info: 'bg-ink-800',
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
              'pointer-events-auto max-w-sm rounded-md px-4 py-2.5 text-sm font-medium text-white shadow-lg',
              TONES[toast.tone],
            )}
          >
            {toast.message}
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
