import { describe, expect, it, vi } from 'vitest';
import { ApiError } from './errors';
import { applyServerErrors, mapServerErrors, withIncident } from './formErrors';

/**
 * The rule under test is one sentence: no violation the backend sends is ever dropped.
 *
 * Before this module, every form used `if (field in schema.shape)` and threw away anything
 * that did not match — including nested line-item violations and `WEAK_PASSWORD`, the one
 * message that explains why a password was refused.
 */

function validationError(details: Array<{ field: string; message: string }>, extra: {
  code?: string; message?: string; requestId?: string;
} = {}) {
  return new ApiError({
    status: 400,
    code: extra.code ?? 'VALIDATION_FAILED',
    message: extra.message ?? 'That request was not valid.',
    fieldErrors: details,
    requestId: extra.requestId,
  });
}

describe('mapServerErrors', () => {
  it('puts a violation on the field it names', () => {
    const mapping = mapServerErrors(
      validationError([{ field: 'displayName', message: 'must not be blank' }]),
      ['displayName', 'email'],
    );

    expect(mapping.fields).toEqual([{ field: 'displayName', message: 'must not be blank' }]);
    expect(mapping.summary).toEqual([]);
  });

  it('summarises a violation on a field the form does not have', () => {
    // The exact case that used to vanish: the backend validates something the browser does
    // not model, and the form went quiet.
    const mapping = mapServerErrors(
      validationError([{ field: 'taxRegistration', message: 'is not a valid GSTIN' }]),
      ['legalName'],
    );

    expect(mapping.fields).toEqual([]);
    expect(mapping.summary).toEqual(['Tax registration: is not a valid GSTIN']);
  });

  it('keeps a nested line-item violation, with its line number', () => {
    const mapping = mapServerErrors(
      validationError([{ field: 'lineItems[0].quantity', message: 'must be greater than zero' }]),
      ['lineItems', 'notes'],
    );

    expect(mapping.summary).toEqual(['Line 1 — quantity: must be greater than zero']);
  });

  it('shows a generic validation error when there are no field violations at all', () => {
    const mapping = mapServerErrors(
      validationError([], { message: 'A discount of 20000 is more than the invoice’s 11800' }),
      ['discountAmount'],
    );

    expect(mapping.summary).toEqual(['A discount of 20000 is more than the invoice’s 11800']);
  });

  it('does not repeat the generic message above more specific ones', () => {
    // "That request was not valid." above three precise messages is noise, not context.
    const mapping = mapServerErrors(
      validationError([{ field: 'email', message: 'must be a well-formed email address' }]),
      ['email'],
    );

    expect(mapping.summary).toEqual([]);
  });

  it('prefers the specific violation over the generic envelope message', () => {
    const mapping = mapServerErrors(
      validationError([{ field: 'unknownThing', message: 'is wrong' }],
        { message: 'That request was not valid.' }),
      ['email'],
    );

    expect(mapping.summary).toEqual(['Unknown thing: is wrong']);
  });

  it('handles something that is not an ApiError at all', () => {
    expect(mapServerErrors(new Error('boom'), ['email']).summary).toEqual(['boom']);
    expect(mapServerErrors(undefined, ['email']).summary).toEqual(['Something went wrong.']);
  });
});

describe('withIncident', () => {
  it('keeps the incident id the backend issued', () => {
    const error = validationError([], { message: 'Something went wrong.', requestId: 'req-42' });
    expect(withIncident(error)).toBe('Something went wrong. (reference req-42)');
  });

  it('says nothing extra when there is no id', () => {
    expect(withIncident(validationError([], { message: 'Nope.' }))).toBe('Nope.');
  });

  it('carries the incident id into the summary', () => {
    const error = new ApiError({
      status: 500, code: 'INTERNAL_ERROR',
      message: 'Something went wrong on the server.', requestId: 'req-7',
    });

    expect(mapServerErrors(error, ['email']).summary)
      .toEqual(['Something went wrong on the server. (reference req-7)']);
  });
});

describe('applyServerErrors', () => {
  it('calls setError for each field and once for the summary', () => {
    const setError = vi.fn();

    applyServerErrors(
      validationError([
        { field: 'email', message: 'must be a well-formed email address' },
        { field: 'lineItems[1].unitPrice', message: 'cannot be negative' },
      ]),
      setError,
      ['email', 'lineItems'],
    );

    expect(setError).toHaveBeenCalledWith('email',
      { type: 'server', message: 'must be a well-formed email address' });
    expect(setError).toHaveBeenCalledWith('root',
      { type: 'server', message: 'Line 2 — unit price: cannot be negative' });
    expect(setError).toHaveBeenCalledTimes(2);
  });

  it('joins several summary messages so a form can render them as a list', () => {
    const setError = vi.fn();

    applyServerErrors(
      validationError([
        { field: 'a', message: 'first' },
        { field: 'b', message: 'second' },
      ], { message: 'Some fields need attention.' }),
      setError,
      [],
    );

    const [, payload] = setError.mock.calls[0]!;
    expect((payload as { message: string }).message.split('\n')).toEqual([
      'A: first',
      'B: second',
    ]);
  });

  it('never leaves a failure invisible', () => {
    const setError = vi.fn();
    applyServerErrors(new Error('the network died'), setError, ['email']);
    expect(setError).toHaveBeenCalledWith('root',
      { type: 'server', message: 'the network died' });
  });
});
