"""Framework-independent domain errors; the API layer maps them to HTTP status codes."""


class ServiceError(Exception):
    status_code = 400
    code = "bad_request"

    def __init__(self, message: str = ""):
        super().__init__(message or self.code)
        self.message = message or self.code


class Unauthorized(ServiceError):
    status_code = 401
    code = "unauthorized"


class Forbidden(ServiceError):
    status_code = 403
    code = "forbidden"


class NotFound(ServiceError):
    status_code = 404
    code = "not_found"


class Conflict(ServiceError):
    status_code = 409
    code = "conflict"


class Expired(ServiceError):
    status_code = 410
    code = "expired"


class TooManyRequests(ServiceError):
    status_code = 429
    code = "rate_limited"
