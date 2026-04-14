package io.whozoss.agentos.auth

class AccessDeniedException(
    val reason: String,
    val requiredRole: String? = null,
) : RuntimeException(reason)
