import { describe, expect, it } from 'vitest';
import {
  estimateTotals, multiply, parseDecimal, percentOf, roundToCurrency, toDecimalString,
} from './money';

describe('parseDecimal', () => {
  it('reads plain decimals at full precision', () => {
    expect(toDecimalString(parseDecimal('1234.56') ?? 0n)).toBe('1234.56');
    expect(toDecimalString(parseDecimal('.5') ?? 0n)).toBe('0.50');
    expect(toDecimalString(parseDecimal('7.') ?? 0n)).toBe('7.00');
    expect(toDecimalString(parseDecimal('-12.5') ?? 0n)).toBe('-12.50');
  });

  it('rejects anything that is not a plain decimal', () => {
    for (const bad of ['', ' ', '.', '-', 'abc', '1e5', '1,000', '1.2.3', '12px']) {
      expect(parseDecimal(bad), bad).toBeNull();
    }
  });
});

describe('exact arithmetic', () => {
  /**
   * The reason this module exists at all. In IEEE-754 doubles `0.1 + 0.2` is
   * 0.30000000000000004 and `1.005 * 100` is 100.49999999999999; neither is acceptable in
   * a figure a lawyer reads next to a client's name.
   */
  it('does not accumulate binary floating-point drift', () => {
    const tenth = parseDecimal('0.1') ?? 0n;
    const fifth = parseDecimal('0.2') ?? 0n;
    expect(toDecimalString(tenth + fifth)).toBe('0.30');
    expect(0.1 + 0.2).not.toBe(0.3); // the behaviour being avoided

    const rate = parseDecimal('1.005') ?? 0n;
    const hundred = parseDecimal('100') ?? 0n;
    expect(toDecimalString(multiply(rate, hundred))).toBe('100.50');
  });

  it('rounds half away from zero, matching Java HALF_UP', () => {
    expect(toDecimalString(roundToCurrency(parseDecimal('2.005') ?? 0n))).toBe('2.01');
    expect(toDecimalString(roundToCurrency(parseDecimal('2.015') ?? 0n))).toBe('2.02');
    expect(toDecimalString(roundToCurrency(parseDecimal('2.004') ?? 0n))).toBe('2.00');
    expect(toDecimalString(roundToCurrency(parseDecimal('-2.005') ?? 0n))).toBe('-2.01');
  });

  it('computes a percentage of an amount', () => {
    const amount = parseDecimal('11800') ?? 0n;
    const rate = parseDecimal('18') ?? 0n;
    expect(toDecimalString(percentOf(amount, rate))).toBe('2124.00');
  });
});

describe('estimateTotals', () => {
  it('taxes each line separately, as the backend does', () => {
    // Two lines at different rates: taxing the combined subtotal would give a different
    // answer, which is exactly the mistake this mirrors the backend to avoid.
    const totals = estimateTotals([
      { quantity: '2', unitPrice: '5000', taxRate: '18' },
      { quantity: '1', unitPrice: '2000', taxRate: '5' },
    ], '');

    expect(totals.subtotal).toBe('12000.00');
    expect(totals.taxAmount).toBe('1900.00'); // 1800 + 100
    expect(totals.total).toBe('13900.00');
    expect(totals.partial).toBe(false);
  });

  it('subtracts a flat discount from the taxed total', () => {
    const totals = estimateTotals(
      [{ quantity: '1', unitPrice: '1000', taxRate: '10' }],
      '250',
    );
    expect(totals.total).toBe('850.00');
  });

  it('treats a blank tax rate as zero', () => {
    const totals = estimateTotals([{ quantity: '3', unitPrice: '99.99', taxRate: '' }], '');
    expect(totals.subtotal).toBe('299.97');
    expect(totals.taxAmount).toBe('0.00');
    expect(totals.partial).toBe(false);
  });

  it('flags a half-typed line rather than silently counting it as zero', () => {
    const totals = estimateTotals([
      { quantity: '1', unitPrice: '500', taxRate: '' },
      { quantity: '2', unitPrice: '', taxRate: '' },
    ], '');

    expect(totals.subtotal).toBe('500.00');
    expect(totals.partial).toBe(true);
  });
});
