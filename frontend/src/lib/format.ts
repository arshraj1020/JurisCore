import type { Decimal, IsoDate, IsoInstant } from '@/types/api';

/**
 * Formatting money, dates and sizes — consistently, and without doing arithmetic.
 *
 * The backend is the authority on every figure an invoice carries. Nothing here adds,
 * subtracts or re-derives a total; these functions take a decimal string the server sent
 * and render it. That is why `formatMoney` parses only at the last moment, purely to hand
 * `Intl.NumberFormat` a number to lay out.
 */

const DEFAULT_LOCALE = 'en-IN';

/**
 * `11800.00` and `INR` become `₹11,800.00`.
 *
 * Indian digit grouping is not the western one — ₹11,80,000.00, not ₹1,180,000.00 — and
 * `en-IN` is what gets that right for a product built for Indian firms.
 */
export function formatMoney(amount: Decimal | number | null | undefined, currency = 'INR'): string {
  if (amount === null || amount === undefined || amount === '') return '—';
  const value = typeof amount === 'number' ? amount : Number(amount);
  if (!Number.isFinite(value)) return String(amount);
  try {
    return new Intl.NumberFormat(DEFAULT_LOCALE, {
      style: 'currency',
      currency,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value);
  } catch {
    // An unknown currency code should not blank the figure out.
    return `${currency} ${value.toFixed(2)}`;
  }
}

/** A quantity as the backend sent it, trimmed of trailing zeros: `2.500` becomes `2.5`. */
export function formatQuantity(quantity: Decimal | null | undefined): string {
  if (!quantity) return '—';
  const trimmed = quantity.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
  return trimmed === '' ? '0' : trimmed;
}

export function formatPercent(rate: Decimal | null | undefined): string {
  if (rate === null || rate === undefined || rate === '') return '—';
  const value = Number(rate);
  if (!Number.isFinite(value)) return String(rate);
  return `${value.toFixed(value % 1 === 0 ? 0 : 2)}%`;
}

export function formatDate(value: IsoDate | IsoInstant | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value.length === 10 ? `${value}T00:00:00Z` : value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat(DEFAULT_LOCALE, {
    day: '2-digit', month: 'short', year: 'numeric',
    ...(value.length === 10 ? { timeZone: 'UTC' } : {}),
  }).format(date);
}

export function formatDateTime(value: IsoInstant | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat(DEFAULT_LOCALE, {
    day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(date);
}

/** "in 3 days", "2 hours ago". Used where the gap matters more than the timestamp. */
export function formatRelative(value: IsoInstant | IsoDate | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value.length === 10 ? `${value}T00:00:00Z` : value);
  if (Number.isNaN(date.getTime())) return '—';
  const deltaSeconds = (date.getTime() - Date.now()) / 1000;
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ['year', 31536000], ['month', 2592000], ['day', 86400],
    ['hour', 3600], ['minute', 60], ['second', 1],
  ];
  const formatter = new Intl.RelativeTimeFormat(DEFAULT_LOCALE, { numeric: 'auto' });
  for (const [unit, seconds] of units) {
    if (Math.abs(deltaSeconds) >= seconds || unit === 'second') {
      return formatter.format(Math.round(deltaSeconds / seconds), unit);
    }
  }
  return '—';
}

export function formatFileSize(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return '—';
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}

/** `IN_PROGRESS` becomes `In progress` — for enum values shown to people. */
export function humanise(value: string | null | undefined): string {
  if (!value) return '—';
  const lower = value.replace(/_/g, ' ').toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

/** True when a due date is in the past. Presentation only; the backend decides OVERDUE. */
export function isPast(value: IsoDate | IsoInstant | null | undefined): boolean {
  if (!value) return false;
  const date = new Date(value.length === 10 ? `${value}T23:59:59Z` : value);
  return !Number.isNaN(date.getTime()) && date.getTime() < Date.now();
}
