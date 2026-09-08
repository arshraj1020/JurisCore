import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import { ApiError } from './errors';

/**
 * Putting the backend's validation errors where a user can actually see them.
 *
 * Bean Validation answers a rejected write with a list of `{ field, message }` violations,
 * and the obvious way to use them is `setError(field, ...)` for each one. Every form here
 * did that, guarded by `if (field in schema.shape)` — and that guard is where the errors
 * went to die. A violation on a field the form does not have, a nested path like
 * `lineItems[0].quantity`, a rule the frontend does not model — each was silently dropped,
 * and the user watched a button do nothing.
 *
 * The password forms were the worst case. `newPassword` is validated by `@StrongPassword`,
 * whose message is the only explanation of *why* a password was refused; if the field name
 * did not line up, the form showed nothing at all and the user was left guessing which of
 * four character classes was missing.
 *
 * So the rule this module enforces is simple: **every violation the backend sends is
 * displayed somewhere.** Ones that match a form field go on that field; everything else is
 * collected into `root`, which forms render as a summary near the submit button. Nothing is
 * discarded, and no user is left looking at a form that refuses to submit and will not say
 * why.
 */

export interface ServerErrorMapping {
  /** Violations that matched a field in the form, ready for `setError`. */
  fields: Array<{ field: string; message: string }>;
  /**
   * Everything else, already formatted for display: unmatched field violations keep their
   * field name as a prefix so the message still says what it is about.
   */
  summary: string[];
}

/** A stable, readable label for a field name the form does not know. */
function labelFor(field: string): string {
  // `lineItems[0].quantity` reads better as "Line 1 quantity"; anything else is
  // de-camel-cased, which turns `billingEmail` into "Billing email".
  const line = /^lineItems\[(\d+)\]\.(.+)$/.exec(field);
  const name = line ? line[2]! : field;
  const words = name
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[._]/g, ' ')
    .toLowerCase();
  const readable = words.charAt(0).toUpperCase() + words.slice(1);
  return line ? `Line ${Number(line[1]) + 1} — ${readable.toLowerCase()}` : readable;
}

/**
 * Splits an error's violations into what the form can show inline and what it cannot.
 *
 * `knownFields` is the set of names the form actually renders — usually
 * `Object.keys(schema.shape)`. Anything outside it is summarised rather than dropped.
 */
export function mapServerErrors(error: unknown, knownFields: Iterable<string>): ServerErrorMapping {
  const mapping: ServerErrorMapping = { fields: [], summary: [] };
  if (!(error instanceof ApiError)) {
    mapping.summary.push(messageOf(error));
    return mapping;
  }

  const known = new Set(knownFields);
  for (const violation of error.fieldErrors) {
    if (known.has(violation.field)) {
      mapping.fields.push({ field: violation.field, message: violation.message });
    } else {
      mapping.summary.push(`${labelFor(violation.field)}: ${violation.message}`);
    }
  }

  // The top-level message earns its place only when there is nothing more specific to
  // show. "That request was not valid." above three precise violations is noise, and it
  // pushes the messages that actually name the problem further down the block.
  if (mapping.fields.length === 0 && mapping.summary.length === 0) {
    mapping.summary.push(withIncident(error));
  }

  return mapping;
}

/**
 * The message, plus the incident id when the backend issued one.
 *
 * The id is the only thing that connects what the user saw to what the server logged, and
 * it is deliberately the *only* internal detail that crosses this boundary — no stack, no
 * exception class, no SQL.
 */
export function withIncident(error: ApiError): string {
  return error.requestId ? `${error.message} (reference ${error.requestId})` : error.message;
}

function messageOf(error: unknown): string {
  return error instanceof Error && error.message ? error.message : 'Something went wrong.';
}

/**
 * Applies a failed write to a react-hook-form, inline where possible and in the form-level
 * summary otherwise.
 *
 * The `root` error is what forms render near the submit button; several messages are joined
 * with newlines so a form can show them as one block without needing a list component.
 */
export function applyServerErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  knownFields: Iterable<string>,
): void {
  const { fields, summary } = mapServerErrors(error, knownFields);
  for (const { field, message } of fields) {
    setError(field as Path<T>, { type: 'server', message });
  }
  if (summary.length > 0) {
    setError('root' as Path<T>, { type: 'server', message: summary.join('\n') });
  }
}
