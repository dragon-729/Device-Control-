package com.droidlink.network

/** Typed failures so ViewModels can show precise, actionable messages. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Offline(cause: Throwable? = null) : ApiException("No connection to the server. Check your internet and try again.", cause)
    class Unauthorized(message: String = "Your session expired. Please sign in again.") : ApiException(message)
    class Forbidden(message: String = "You do not have permission to do that.") : ApiException(message)
    class NotFound(message: String = "Not found.") : ApiException(message)
    class Conflict(message: String = "That conflicts with the current state.") : ApiException(message)
    class Expired(message: String = "That request has expired.") : ApiException(message)
    class RateLimited(message: String = "Too many attempts. Please wait a minute.") : ApiException(message)
    class Validation(message: String) : ApiException(message)
    class Server(val code: Int, message: String = "The server had a problem (HTTP $code). Try again later.") : ApiException(message)
}
