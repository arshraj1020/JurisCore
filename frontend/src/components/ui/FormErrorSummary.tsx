import { useEffect, useRef } from 'react';
import { Alert } from '@/components/ui/primitives';

/**
 * The form-level error block: everything the server said that no single field could carry.
 *
 * It exists because `applyServerErrors` refuses to discard a violation it cannot pin to a
 * field, and those messages need somewhere to go. One message renders as a sentence;
 * several render as a list, because a paragraph of three concatenated validation failures
 * is read as one confusing sentence.
 *
 * It also takes focus when it appears. A user who pressed "Save draft" and saw nothing
 * happen is the exact failure this phase is fixing: on a long form the summary can easily
 * render above the fold the user is looking at, and a submit that silently does nothing is
 * indistinguishable from a broken button. `tabIndex={-1}` makes the block focusable without
 * putting it in the tab order, and `role="alert"` (from `Alert`, with `live`) means a
 * screen-reader user hears it whether or not focus moved.
 */
export function FormErrorSummary({ message }: { message?: string }) {
  const container = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!message) return;
    container.current?.focus();
    container.current?.scrollIntoView({ block: 'nearest' });
  }, [message]);

  if (!message) return null;

  // `applyServerErrors` joins several messages with newlines; one message stays a sentence.
  const lines = message.split('\n').filter((line) => line.trim() !== '');

  return (
    <div ref={container} tabIndex={-1} className="outline-none">
      <Alert tone="danger" live title={lines.length > 1 ? 'That could not be saved' : undefined}>
        {lines.length > 1
          ? (
            <ul className="list-disc space-y-0.5 pl-4">
              {lines.map((line) => <li key={line}>{line}</li>)}
            </ul>
          )
          : lines[0]}
      </Alert>
    </div>
  );
}
