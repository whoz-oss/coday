package io.whozoss.agentos.configAgent

import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeState
import org.springframework.security.access.AccessDeniedException

/**
 * A plan that cannot be applied or rejected as it stands: it is in [state], or changed since it was shown when
 * [state] is null. A 409 like any conflict; the state lets a caller other than HTTP tell the reason without
 * reading the message.
 */
class ConfigChangeConflictException(
    val state: ConfigChangeState?,
    message: String,
) : ConflictException(message)

/** A plan applied or rejected by someone else than the user it was proposed to: a 403 like any refusal. */
class ConfigChangeRecipientException(
    message: String,
) : AccessDeniedException(message)
