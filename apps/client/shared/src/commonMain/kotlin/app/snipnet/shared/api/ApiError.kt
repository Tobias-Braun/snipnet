package app.snipnet.shared.api

/**
 * Every failure of an API call, thrown by [SnipnetApi]. HTTP errors are mapped from the status code (the contract's
 * `code` strings are refinements of it and stay available in [code]); [Network] and [MalformedResponse] cover
 * failures where no valid error body exists. Callers `catch (e: ApiError)` and `when` over the subtypes.
 *
 * @property status HTTP status, or null when no response was received.
 * @property code the contract's `snake_case` error code, or a synthetic one for [Network] and [MalformedResponse].
 */
sealed class ApiError(
    val status: Int?,
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** 400: the request body or parameters were rejected. */
    class Validation(
        code: String,
        message: String,
    ) : ApiError(400, code, message)

    /** 401: missing, expired or wrong credentials. */
    class Unauthorized(
        code: String,
        message: String,
    ) : ApiError(401, code, message)

    /** 403: authenticated but not allowed. */
    class Forbidden(
        code: String,
        message: String,
    ) : ApiError(403, code, message)

    /** 404: the resource does not exist (or is not visible to the user). */
    class NotFound(
        code: String,
        message: String,
    ) : ApiError(404, code, message)

    /** 409: the request conflicts with the current state (duplicate email, video not uploaded, job running...). */
    class Conflict(
        code: String,
        message: String,
    ) : ApiError(409, code, message)

    /** 429: too many requests; [retryAfterSeconds] is taken from the `Retry-After` header when present. */
    class RateLimited(
        code: String,
        message: String,
        val retryAfterSeconds: Long?,
    ) : ApiError(429, code, message)

    /** 5xx: the server failed. */
    class Server(
        status: Int,
        code: String,
        message: String,
    ) : ApiError(status, code, message)

    /** Any other non-success status the contract does not list. */
    class Unexpected(
        status: Int,
        code: String,
        message: String,
    ) : ApiError(status, code, message)

    /** The request never produced a response: DNS, connection refused, timeout. */
    class Network(
        cause: Throwable,
    ) : ApiError(null, "network_error", cause.message ?: "The server could not be reached.", cause)

    /** A success response whose body could not be decoded into the contract's types. */
    class MalformedResponse(
        status: Int,
        cause: Throwable,
    ) : ApiError(status, "malformed_response", "The server sent an unreadable response.", cause)
}
