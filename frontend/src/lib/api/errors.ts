import type { ApiErrorBody, FieldViolation } from '@/types/api';

/**
 * Every failure the API layer can produce, in one shape.
 *
 * Components never see a raw `Response`, a `TypeError` from a dropped connection, or a
 * parse failure — they see an `ApiError` with a status, a machine-readable `code` and a
 * message that is safe to render. That last part is the point: the backend's
 * `GlobalExceptionHandler` already replaces internal failures with a generic message and
 * an incident id, and this class is where the frontend refuses to display anything else.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: FieldViolation[];
  readonly requestId?: string;

  constructor(init: {
    status: number;
    code: string;
    message: string;
    fieldErrors?: FieldViolation[];
    requestId?: string;
  }) {
    super(init.message);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.fieldErrors = init.fieldErrors ?? [];
    this.requestId = init.requestId;
  }

  get isUnauthenticated() { return this.status === 401; }
  get isForbidden() { return this.status === 403; }
  get isNotFound() { return this.status === 404; }
  get isValidation() { return this.status === 400 || this.status === 422; }
  /** A stale `version`, or a lifecycle transition the backend refuses. */
  get isConflict() { return this.status === 409; }
  get isConcurrency() { return this.code === 'CONCURRENT_MODIFICATION'; }
  get isNetwork() { return this.status === 0; }
}

/** The network never answered. Distinct from any HTTP status, including 500. */
export function networkError(): ApiError {
  return new ApiError({
    status: 0,
    code: 'NETWORK_ERROR',
    message: 'Could not reach the server. Check your connection and try again.',
  });
}

const FALLBACK: Record<number, string> = {
  400: 'That request was not valid.',
  401: 'Your session has ended. Please sign in again.',
  403: 'You do not have permission to do that.',
  404: 'That record does not exist.',
  405: 'That action is not supported here.',
  409: 'That conflicts with the current state. Reload and try again.',
  415: 'That file type is not supported.',
  429: 'Too many requests. Please slow down.',
  500: 'Something went wrong on the server.',
};

/**
 * Turns a failed response into an `ApiError`.
 *
 * The backend's envelope is trusted when it is present and well-formed. When it is not —
 * a proxy returning HTML, a gateway timeout, a body that is not JSON — the status alone
 * decides the message, because the alternative is putting an unparsed response body in
 * front of a user.
 */
export async function toApiError(response: Response): Promise<ApiError> {
  const requestId = response.headers.get('X-Request-Id') ?? undefined;
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    body = undefined;
  }

  const error = (body as { error?: ApiErrorBody } | undefined)?.error;
  if (error && typeof error.code === 'string' && typeof error.message === 'string') {
    return new ApiError({
      status: response.status,
      code: error.code,
      message: error.message,
      fieldErrors: Array.isArray(error.details) ? error.details : [],
      requestId,
    });
  }

  return new ApiError({
    status: response.status,
    code: 'UNEXPECTED_RESPONSE',
    message: FALLBACK[response.status] ?? 'Something went wrong.',
    requestId,
  });
}

/** A message safe to put in front of a user, whatever was thrown. */
export function messageFor(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  return 'Something went wrong.';
}

/** Field-level messages keyed by field name, for wiring server validation into a form. */
export function fieldErrorsOf(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError)) return {};
  return Object.fromEntries(error.fieldErrors.map((v) => [v.field, v.message]));
}
