package io.whozoss.agentos.permissions

import java.util.UUID

/**
 * Represents the authenticated principal that is making a request to a service method.
 *
 * ## Why authority is an explicit parameter rather than read from a thread-local
 *
 * The HTTP authentication filter populates a thread-local security context per request and clears
 * it in a `finally` block. Scheduled prompt execution, agent runs, and tool calls all run on
 * `Dispatchers.IO` coroutines that dispatch across threads; a thread-local would not survive a
 * suspension point without extra machinery, and the failure would be silent and intermittent.
 * Passing [Caller] explicitly keeps the dependency visible at every call site, so a background job
 * cannot acquire ambient authority by accident.
 *
 * ## Why `isAdmin` is carried rather than looked up
 *
 * [isAdmin] comes from the already-resolved `User` record. Carrying it here removes a
 * `userService.findById` round-trip from every service method that needs to gate on admin status.
 *
 * ## Agent privilege
 *
 * [Agent] carries full privilege, including `isAdmin`. Delegation is complete: a developer running
 * a single-user instance is Super Admin of their own environment, where platform scope simply means
 * "all my projects". [Agent] is distinguished from [Human] for provenance and so a policy can
 * require an explicit confirmation step for far-reaching writes — not to cap privilege.
 *
 * ## No `System` variant
 *
 * Unchecked internal access is expressed by an `unsecured*` method-naming convention on services
 * themselves. There is exactly one mechanism for it rather than two, which keeps the audit surface
 * small and searchable.
 */
sealed interface Caller {
    /** The AgentOS user identity on whose behalf this call is made. */
    val userId: UUID

    /** True when the resolved user holds Super Admin authority. */
    val isAdmin: Boolean

    /** True when an LLM-driven agent is acting on behalf of [userId]. */
    val isAgent: Boolean

    /**
     * A human user interacting directly (e.g. via the web UI or REST API).
     */
    data class Human(override val userId: UUID, override val isAdmin: Boolean) : Caller {
        override val isAgent: Boolean get() = false
    }

    /**
     * An LLM-driven agent acting on behalf of [userId].
     *
     * [namespaceId] is the namespace in which the agent is running.
     * [caseId] is the case being processed, or `null` for background / scheduled runs.
     */
    data class Agent(
        override val userId: UUID,
        override val isAdmin: Boolean,
        val namespaceId: UUID,
        val caseId: UUID?,
    ) : Caller {
        override val isAgent: Boolean get() = true
    }
}
