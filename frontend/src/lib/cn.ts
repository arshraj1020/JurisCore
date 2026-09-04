/** Joins class names, dropping anything falsy. Small on purpose — no dependency needed. */
export function cn(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ');
}
