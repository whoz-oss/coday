package io.whozoss.agentos.exception

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

@ResponseStatus(HttpStatus.CONFLICT)
class ConflictException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
