import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import { Button } from './primitives';

/**
 * A modal built on the native `<dialog>` element.
 *
 * Native rather than hand-rolled because the browser then supplies the focus trap, the
 * inert background, Escape-to-close and the top layer — four things that are easy to
 * implement badly and that people relying on a keyboard or a screen reader notice
 * immediately when they are wrong.
 */
export function Dialog({ open, onClose, title, description, children, footer }: {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: string;
  children?: ReactNode;
  footer?: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      aria-labelledby="dialog-title"
      aria-describedby={description ? 'dialog-description' : undefined}
      onCancel={(event) => { event.preventDefault(); onClose(); }}
      onClose={onClose}
      className="w-[min(32rem,calc(100vw-2rem))] rounded-lg p-0 shadow-xl backdrop:bg-ink-950/40"
    >
      <div className="border-b border-ink-200 px-5 py-4">
        <h2 id="dialog-title" className="text-base font-semibold text-ink-900">{title}</h2>
        {description && (
          <p id="dialog-description" className="mt-1 text-sm text-ink-600">{description}</p>
        )}
      </div>
      {children && <div className="px-5 py-4">{children}</div>}
      <div className="flex justify-end gap-2 border-t border-ink-200 bg-ink-50 px-5 py-3">
        {footer ?? <Button variant="secondary" onClick={onClose}>Close</Button>}
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
