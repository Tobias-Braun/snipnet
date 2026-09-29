/**
 * Error codes of the API contract (docs/api.md). Routes throw an `AppError` with one of them and the error
 * handler plugin turns it into `{ "error": { "code", "message" } }` with the matching HTTP status.
 */
export const ERROR_STATUS = {
  validation_error: 400,
  unauthorized: 401,
  forbidden: 403,
  not_found: 404,
  conflict: 409,
  rate_limited: 429,
  internal: 500,
} as const;

export type ErrorCode = keyof typeof ERROR_STATUS;

export class AppError extends Error {
  readonly code: ErrorCode;

  constructor(code: ErrorCode, message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = 'AppError';
    this.code = code;
  }

  get statusCode(): number {
    return ERROR_STATUS[this.code];
  }
}
