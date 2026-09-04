/**
 * Exact decimal arithmetic for the invoice draft preview.
 *
 * **This is a preview, not the invoice.** `InvoiceCalculator` on the server computes the
 * figures that are stored, billed and paid against; nothing here is ever sent as a total.
 * What this file exists for is the running estimate under a draft's line items, so
 * somebody typing an invoice can see roughly where it lands before saving it.
 *
 * It is written in `bigint` rather than `number` because the naive version is wrong in a
 * way that is easy to ship and embarrassing to explain: `0.1 + 0.2` is 0.30000000000000004
 * in IEEE-754 doubles, and an eight-line invoice accumulates enough of that drift to show
 * a preview a paisa away from what the server returns a second later. A figure that is
 * *almost* right is worse than no figure, because nobody knows which one to trust.
 *
 * The rounding mirrors the backend's: per line, HALF_UP to two decimal places — the same
 * order of operations, so the preview and the saved invoice agree.
 */

const SCALE = 8;
const FACTOR = 10n ** BigInt(SCALE);

/** Parses a plain decimal string into a bigint scaled by 10^8. `null` if unparseable. */
export function parseDecimal(value: string | null | undefined): bigint | null {
  if (value === null || value === undefined) return null;
  const trimmed = value.trim();
  if (trimmed === '' || !/^-?\d*(\.\d*)?$/.test(trimmed)) return null;
  const negative = trimmed.startsWith('-');
  const body = negative ? trimmed.slice(1) : trimmed;
  const [whole = '', fraction = ''] = body.split('.');
  if (whole === '' && fraction === '') return null;
  const padded = (fraction + '0'.repeat(SCALE)).slice(0, SCALE);
  const magnitude = BigInt(whole === '' ? '0' : whole) * FACTOR + BigInt(padded === '' ? '0' : padded);
  return negative ? -magnitude : magnitude;
}

/** Divides by `divisor`, rounding half away from zero — Java's `RoundingMode.HALF_UP`. */
function divideHalfUp(numerator: bigint, divisor: bigint): bigint {
  const negative = (numerator < 0n) !== (divisor < 0n);
  const a = numerator < 0n ? -numerator : numerator;
  const b = divisor < 0n ? -divisor : divisor;
  const quotient = a / b;
  const remainder = a % b;
  const rounded = remainder * 2n >= b ? quotient + 1n : quotient;
  return negative ? -rounded : rounded;
}

export function multiply(a: bigint, b: bigint): bigint {
  return divideHalfUp(a * b, FACTOR);
}

/** Rounds a scaled value to two decimal places, HALF_UP, keeping the internal scale. */
export function roundToCurrency(value: bigint): bigint {
  const step = 10n ** BigInt(SCALE - 2);
  return divideHalfUp(value, step) * step;
}

export function percentOf(amount: bigint, rate: bigint): bigint {
  return divideHalfUp(amount * rate, 100n * FACTOR);
}

/** Renders a scaled value as a plain two-decimal string — the shape the API uses. */
export function toDecimalString(value: bigint): string {
  const rounded = roundToCurrency(value);
  const negative = rounded < 0n;
  const magnitude = negative ? -rounded : rounded;
  const whole = magnitude / FACTOR;
  const fraction = ((magnitude % FACTOR) / 10n ** BigInt(SCALE - 2)).toString().padStart(2, '0');
  return `${negative ? '-' : ''}${whole}.${fraction}`;
}

export interface DraftLine {
  quantity: string;
  unitPrice: string;
  taxRate: string;
}

export interface DraftTotals {
  subtotal: string;
  taxAmount: string;
  total: string;
  /** True when a line could not be parsed, so the estimate covers only part of the draft. */
  partial: boolean;
}

/**
 * The same per-line arithmetic the backend performs, for preview only.
 *
 * Lines that do not parse are skipped rather than treated as zero, and `partial` says so,
 * because a total that silently ignores a half-typed line reads as authoritative.
 */
export function estimateTotals(lines: DraftLine[], discount: string): DraftTotals {
  let subtotal = 0n;
  let tax = 0n;
  let partial = false;

  for (const line of lines) {
    const quantity = parseDecimal(line.quantity);
    const unitPrice = parseDecimal(line.unitPrice);
    if (quantity === null || unitPrice === null) {
      partial = true;
      continue;
    }
    const amount = roundToCurrency(multiply(quantity, unitPrice));
    subtotal += amount;

    const rate = parseDecimal(line.taxRate === '' ? '0' : line.taxRate);
    if (rate === null) {
      partial = true;
      continue;
    }
    tax += roundToCurrency(percentOf(amount, rate));
  }

  const discountValue = parseDecimal(discount === '' ? '0' : discount);
  if (discountValue === null) partial = true;

  const total = subtotal + tax - (discountValue ?? 0n);
  return {
    subtotal: toDecimalString(subtotal),
    taxAmount: toDecimalString(tax),
    total: toDecimalString(total),
    partial,
  };
}
