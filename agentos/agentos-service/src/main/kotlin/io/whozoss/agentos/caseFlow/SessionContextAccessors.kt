package io.whozoss.agentos.caseFlow

/**
 * Typed reads of the session context map (see [SessionContextKeys]).
 *
 * The map stays an opaque `Map<String, Any?>`: it comes from JSON written by others (the UI's backend, a
 * plugin, an LLM) and goes back to JSON, so its format is not agentos's to change. These accessors are the
 * only places where agentos reads a value out of it; they are tolerant, and a value of an unexpected type, or a
 * blank one, reads as absent rather than failing the run.
 */

/** The user's preferred response language ([SessionContextKeys.PREFERRED_LANGUAGE]), or null when absent or blank. */
fun Map<String, Any?>.preferredLanguage(): String? =
    (this[SessionContextKeys.PREFERRED_LANGUAGE] as? String)?.takeIf { it.isNotBlank() }

/**
 * The name of the user the context is for: [SessionContextKeys.FIRST_NAME] and [SessionContextKeys.LAST_NAME] of
 * [SessionContextKeys.USER_CONTEXT], joined by a space. Null when there is no `userContext`, or neither name is a
 * non-blank string.
 */
fun Map<String, Any?>.userDisplayName(): String? =
    (this[SessionContextKeys.USER_CONTEXT] as? Map<*, *>)
        ?.let { userContext ->
            listOf(SessionContextKeys.FIRST_NAME, SessionContextKeys.LAST_NAME)
                .mapNotNull { (userContext[it] as? String)?.trim()?.takeIf { name -> name.isNotEmpty() } }
        }?.joinToString(" ")
        ?.ifBlank { null }
