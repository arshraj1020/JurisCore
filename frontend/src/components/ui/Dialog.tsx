import { useEffect, useId, useRef } from 'react';
import type { ReactNode } from 'react';
import { Button, IconButton } from './primitives';
import { cn } from '@/lib/cn';

/**
 * A modal built on the native `<dialog>` element.
 *
 * Native rather than hand-rolled because the browser then supplies the focus trap, the
 * inert background, Escape-to-close and the top layer — four things that are easy to
 * implement badly and that people relying on a keyboard or a screen reader notice
 * immediately when they are wrong.
 *
 * The ids are per-instance rather than the literal `dialog-title`: two dialogs mounted at
 * once (a confirmation raised from inside an editor) would otherwise both point their
 * `aria-labelledby` at whichever heading rendered first.
 */
export function Dialog({
  open, onClose, title, description, children, footer, size = 'md',
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: string;
  children?: ReactNode;
  footer?: ReactNode;
  size?: 'md' | 'lg';
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const id = useId();
  /** True while the effect below is closing the dialog because `open` went false. */
  const closingFromProp = useRef(false);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) {
      // `close()` fires the native `close` event, which is wired to `onClose` below. Since
      // this branch only runs because the parent *already* set `open` to false, letting
      // that through would call `onClose` a second time for a close the parent requested.
      // Harmless when the handler is a bare `setOpen(false)`; not harmless where it
      // invalidates queries or resets a form, which several callers do — those ran twice.
      closingFromProp.current = true;
      dialog.close();
    }
  }, [open]);

  return (
    <dialog
      ref={ref}
      aria-labelledby={`${id}-title`}
      aria-describedby={description ? `${id}-description` : undefined}
      onCancel={(event) => { event.preventDefault(); onClose(); }}
      onClose={() => {
        // Escape and the backdrop reach us here and must notify the parent. A close the
        // parent itself asked for must not.
        if (closingFromProp.current) {
          closingFromProp.current = false;
          return;
        }
        onClose();
      }}
      className={cn(
        'w-[calc(100vw-1.5rem)] rounded-lg border border-ink-200 p-0 text-ink-900 shadow-pop',
        'backdrop:bg-ink-950/50 backdrop:backdrop-blur-[1px]',
        'open:animate-slide-up',
        size === 'lg' ? 'sm:w-[min(46rem,calc(100vw-3rem))]' : 'sm:w-[min(32rem,calc(100vw-3rem))]',
      )}
    >
      <div className="flex max-h-[85vh] flex-col">
        <div className="flex items-start justify-between gap-4 border-b border-ink-200 px-5 py-3.5">
          <div className="min-w-0">
            <h2 id={`${id}-title`} className="text-base font-semibold text-ink-900">{title}</h2>
            {description && (
              <p id={`${id}-description`} className="mt-1 text-sm text-ink-600">{description}</p>
            )}
          </div>
          <IconButton icon="close" label="Close" size="sm" onClick={onClose} className="-mr-1.5" />
        </div>

        {/* The body scrolls, not the page behind it, so a long form keeps its footer in
            reach on a phone. */}
        {children && <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">{children}</div>}

        <div className="flex flex-wrap justify-end gap-2 border-t border-ink-200 bg-ink-50 px-5 py-3">
          {footer ?? <Button variant="secondary" onClick={onClose}>Close</Button>}
        </div>
      </div>
    </dialog>
  );
}

/** The confirmation every destructive action goes through. */
export function ConfirmDialog({
  open, onClose, onConfirm, title, description, confirmLabel = 'Confirm', destructive = true,
  busy = false,
}: {
  open: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: string;
  description: string;
  confirmLabel?: string;
  destructive?: boolean;
  busy?: boolean;
}) {
  return (
    <Dialog
      open={open} onClose={onClose} title={title} description={description}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button variant={destructive ? 'danger' : 'primary'} onClick={onConfirm} loading={busy}>
            {confirmLabel}
          </Button>
        </>
      }
    />
  );
}
