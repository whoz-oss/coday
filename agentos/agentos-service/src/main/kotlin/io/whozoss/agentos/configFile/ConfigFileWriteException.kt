package io.whozoss.agentos.configFile

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * AgentOS could not write a config file of a namespace's `configPath`. The message says why in words for
 * the user (the folder is read-only, it no longer exists...), never with the raw exception message.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
class ConfigFileWriteException(
    message: String,
) : RuntimeException(message)
