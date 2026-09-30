package io.whozoss.agentos.plugins.http

/** Stable `errorType` values of the `HTTP_API` tools, the vocabulary the agent prompts can rely on. */
object HttpApiErrors {
    const val INVALID_INPUT = "INVALID_INPUT"
    const val AUTH_MISSING = "AUTH_MISSING"
    const val URL_POLICY_REJECTED = "URL_POLICY_REJECTED"
    const val HTTP_CLIENT_ERROR = "HTTP_CLIENT_ERROR"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val FORBIDDEN = "FORBIDDEN"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val REDIRECT_NOT_FOLLOWED = "REDIRECT_NOT_FOLLOWED"
    const val HTTP_SERVER_ERROR = "HTTP_SERVER_ERROR"
    const val TRANSPORT_ERROR = "TRANSPORT_ERROR"
}
