import { describe, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Dialog } from './Dialog';

/**
 * `onClose` means "the user asked to close this", exactly once.
 *
 * <p>The component drives a native dialog element, and native `close()` fires a `close`
 * event. So a parent setting `open={false}` used to produce two notifications: the effect
 * called `close()`, the browser fired `close`, and the handler wired to that event called
 * `onClose` again — for a close the parent had already decided on.
 *
 * <p>Invisible when the handler is a bare `setOpen(false)`, which is why it survived. Not
 * invisible in the several callers that invalidate queries or reset a form on close: those
 * ran twice, issuing a duplicate refetch on every dismissal.
 *
 * <h2>Why these tests dispatch the event themselves</h2>
 *
 * <p>jsdom implements no dialog behaviour; the suite's setup stubs `showModal`/`close` to
 * toggle the `open` property and nothing else, so no `close` event is ever fired and a test
 * driven purely by clicks would observe neither the bug nor the fix. Firing the event
 * directly is what a real browser does at that moment, and it is the only way to reach the
 * branch under test. The trade-off is stated rather than hidden: this pins the guard's
 * logic, not the browser's behaviour.
 */
describe('Dialog — onClose fires once per close', () => {
  function Harness({ onClose }: { onClose: () => void }) {
    const [open, setOpen] = useState(true);
    return (
      <>
        <button type="button" onClick={() => setOpen(false)}>Close from parent</button>
        <Dialog
          open={open}
          onClose={() => { setOpen(false); onClose(); }}
          title="Schedule a hearing"
          footer={<span />}
        >
          <p>Body</p>
        </Dialog>
      </>
    );
  }

  it('does not notify a second time for a close the parent asked for', async () => {
    const onClose = vi.fn();
    render(<Harness onClose={onClose} />);
    const dialog = screen.getByRole('dialog');

    // The parent closes it. The effect calls dialog.close(), and in a real browser that
    // synchronously fires `close` — which is what this dispatch stands in for.
    await userEvent.click(screen.getByRole('button', { name: 'Close from parent' }));
    fireEvent(dialog, new Event('close'));

    // Before the fix this was 1: the parent's own close came back to it as a user action.
    expect(onClose).not.toHaveBeenCalled();
  });

  it('still notifies when the browser closes it on its own', () => {
    const onClose = vi.fn();
    render(<Harness onClose={onClose} />);

    // Escape and a backdrop dismissal both arrive as a `close` the parent did not request.
    // The suppression has to be narrow enough to let these through, or the dialog would
    // vanish visually while the parent's state still said open.
    fireEvent(screen.getByRole('dialog'), new Event('close'));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('suppresses only the one close it was asked for, not the next one', async () => {
    const onClose = vi.fn();
    render(<Harness onClose={onClose} />);
    const dialog = screen.getByRole('dialog');

    await userEvent.click(screen.getByRole('button', { name: 'Close from parent' }));
    fireEvent(dialog, new Event('close'));
    expect(onClose).not.toHaveBeenCalled();

    // A latched flag that never reset would swallow every subsequent Escape too.
    fireEvent(dialog, new Event('close'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('renders its content and labels itself', () => {
    render(<Harness onClose={vi.fn()} />);

    // A guard against the assertions above passing because nothing rendered at all.
    expect(screen.getByText('Body')).toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: 'Schedule a hearing' })).toBeInTheDocument();
  });
});
