import { describe, expect, it } from 'vitest';
import {
  ALLOWED_CONTENT_TYPES, HEARING_DURATION, LIMITS, MAX_FILE_SIZE, compareDecimals,
  fileSizeProblem, filenameProblem, invoiceDiscount, invoicePrefix, invoiceQuantity,
  invoiceTaxRate, invoiceUnitPrice, isAllowedContentType, isIsoCurrency,
  normaliseContentType, optionalCurrencyCode, passwordProblem, strongPassword,
} from './validation';

/**
 * Boundaries, on both sides.
 *
 * Each case here is a value the backend has a definite opinion about, and the test asserts
 * that the browser agrees. Where the audit found a mismatch, the *old* frontend behaviour
 * is named in the test, so a regression reads as a regression rather than as a puzzling
 * assertion about the number 200.
 */

const ok = (schema: { safeParse: (value: unknown) => { success: boolean } }, value: string) =>
  schema.safeParse(value).success;

describe('length limits mirror the backend DTOs', () => {
  it('uses the backend figure everywhere the audit found the frontend wrong', () => {
    expect(LIMITS.NAME).toBe(200);        // was 255 for court name and legal name
    expect(LIMITS.PHONE).toBe(40);        // was 32 on the profile and invite forms
    expect(LIMITS.PURPOSE).toBe(1000);    // hearing purpose, was 2000
    expect(LIMITS.LONG_TEXT).toBe(4000);  // task/deadline descriptions, were 2000
    expect(LIMITS.PAYMENT_REFERENCE).toBe(120);
    expect(LIMITS.INVOICE_PREFIX).toBe(12);
  });

  it('bounds a hearing at a single day, as @Min(1) @Max(1440) does', () => {
    expect(HEARING_DURATION).toEqual({ MIN: 1, MAX: 1440 });
  });
});

describe('invoice prefix', () => {
  it.each(['INV', 'A', 'INV-2026', 'ABCDEFGHIJKL'])('accepts %s', (value) => {
    expect(ok(invoicePrefix, value)).toBe(true);
  });

  it('accepts a twelve-character prefix that the old 1-10 rule refused', () => {
    expect('ABCDEFGHIJKL').toHaveLength(12);
    expect(ok(invoicePrefix, 'ABCDEFGHIJKL')).toBe(true);
  });

  it.each([
    ['ABCDEFGHIJKLM', 'thirteen characters'],
    ['inv', 'lower case'],
    ['1NV', 'a leading digit'],
    ['IN V', 'a space'],
    ['', 'nothing at all'],
  ])('refuses %s (%s)', (value) => {
    expect(ok(invoicePrefix, value)).toBe(false);
  });
});

describe('currency codes', () => {
  it('accepts real ISO 4217 codes', () => {
    for (const code of ['INR', 'USD', 'EUR', 'GBP', 'JPY']) {
      expect(isIsoCurrency(code)).toBe(true);
    }
  });

  it('refuses a three-letter string that is not a currency', () => {
    // `[A-Z]{3}` was the old rule, and ZZZ passes it. `Currency.getInstance` does not.
    expect(isIsoCurrency('ZZZ')).toBe(false);
  });

  it('refuses "122", which the audit watched reach the server', () => {
    expect(isIsoCurrency('122')).toBe(false);
    expect(ok(optionalCurrencyCode, '122')).toBe(false);
  });

  it('treats blank as "use the firm default", which the backend fills in', () => {
    expect(ok(optionalCurrencyCode, '')).toBe(true);
  });
});

describe('passwords mirror StrongPasswordValidator', () => {
  it('accepts one that satisfies all four character classes at length', () => {
    expect(passwordProblem('Correct-Horse7!')).toBeNull();
    expect(ok(strongPassword, 'Correct-Horse7!')).toBe(true);
  });

  it('refuses eleven characters and accepts twelve', () => {
    expect(passwordProblem('Abcdefgh1!x')).not.toBeNull();
    expect(passwordProblem('Abcdefgh1!xy')).toBeNull();
  });

  it('refuses a long password missing a character class', () => {
    // The old frontend rule was `min(12)` alone, so this passed in the browser and was
    // refused by the server — with a message the form had nowhere to put.
    expect(passwordProblem('abcdefghijklmnop')).toContain('upper-case');
  });

  it('refuses the blocklisted passwords the validator names', () => {
    expect(passwordProblem('Password@1234')).toContain('too common');
  });

  it('refuses more than 128 characters', () => {
    expect(passwordProblem(`Aa1!${'x'.repeat(130)}`)).toContain('at most 128');
  });
});

describe('invoice decimals mirror the @DecimalMin/@Digits bounds', () => {
  it('takes a quantity at the 0.001 floor and refuses zero', () => {
    expect(ok(invoiceQuantity, '0.001')).toBe(true);
    expect(ok(invoiceQuantity, '0.000')).toBe(false);
    expect(ok(invoiceQuantity, '0')).toBe(false);
  });

  it('holds a quantity to three decimals and nine integer digits', () => {
    expect(ok(invoiceQuantity, '999999999.999')).toBe(true);
    expect(ok(invoiceQuantity, '0.0001')).toBe(false);
    expect(ok(invoiceQuantity, '1000000000')).toBe(false);
  });

  it('allows a zero unit price, which the backend permits', () => {
    // @DecimalMin("0.00"), not 0.01 — a zero-priced line is a legitimate written-off line,
    // and the old `positiveDecimal` rule refused it.
    expect(ok(invoiceUnitPrice, '0.00')).toBe(true);
    expect(ok(invoiceUnitPrice, '0.001')).toBe(false);
  });

  it('bounds a tax rate at 100 inclusive', () => {
    expect(ok(invoiceTaxRate, '100.000')).toBe(true);
    expect(ok(invoiceTaxRate, '100.001')).toBe(false);
    expect(ok(invoiceTaxRate, '')).toBe(true);
  });

  it('allows a zero discount and refuses a negative one', () => {
    expect(ok(invoiceDiscount, '0.00')).toBe(true);
    expect(ok(invoiceDiscount, '-1.00')).toBe(false);
  });

  it('refuses text where a number belongs', () => {
    expect(ok(invoiceUnitPrice, '1,000')).toBe(false);
    expect(ok(invoiceUnitPrice, 'free')).toBe(false);
  });
});

describe('compareDecimals', () => {
  it('compares without going through a float', () => {
    expect(compareDecimals('0.10', '0.1')).toBe(0);
    expect(compareDecimals('11800.00', '11800.01')).toBe(-1);
    // Both sides are past Number.MAX_SAFE_INTEGER, where a float comparison gives 0.
    expect(compareDecimals('9007199254740993.01', '9007199254740993.00')).toBe(1);
  });
});

describe('document rules mirror DocumentUploadPolicy', () => {
  it('mirrors the server allowlist exactly', () => {
    expect(ALLOWED_CONTENT_TYPES).toHaveLength(11);
    expect(isAllowedContentType('application/pdf')).toBe(true);
    expect(isAllowedContentType('image/png')).toBe(true);
  });

  it('refuses a type the server does not accept', () => {
    expect(isAllowedContentType('application/x-msdownload')).toBe(false);
    expect(isAllowedContentType('application/zip')).toBe(false);
    expect(isAllowedContentType('')).toBe(false);
    expect(isAllowedContentType(undefined)).toBe(false);
  });

  it('drops the charset parameter before matching, as normalise does', () => {
    expect(normaliseContentType('TEXT/PLAIN; charset=utf-8')).toBe('text/plain');
    expect(isAllowedContentType('TEXT/PLAIN; charset=utf-8')).toBe(true);
  });

  it('applies each filename rule the policy applies', () => {
    expect(filenameProblem('petition.pdf')).toBeNull();
    expect(filenameProblem('  ')).toContain('required');
    expect(filenameProblem('a/b.pdf')).toContain('path separator');
    expect(filenameProblem('a\\b.pdf')).toContain('path separator');
    // The backend checks separators before '..', so a name needs the dots without a slash
    // to reach that rule — mirrored here rather than asserting a different order.
    expect(filenameProblem('..secrets.pdf')).toContain("'..'");
    expect(filenameProblem('.')).toContain('not a filename');
    expect(filenameProblem('head\u000Dinjection.pdf')).toContain('control characters');
    expect(filenameProblem(`${'x'.repeat(256)}.pdf`)).toContain('at most 255');
    expect(filenameProblem(`${'x'.repeat(251)}.pdf`)).toBeNull();
  });

  it('bounds the file size at the server figure', () => {
    expect(MAX_FILE_SIZE).toBe(52_428_800);
    expect(fileSizeProblem(0)).toContain('empty');
    expect(fileSizeProblem(MAX_FILE_SIZE)).toBeNull();
    expect(fileSizeProblem(MAX_FILE_SIZE + 1)).toContain('50 MB');
  });
});
