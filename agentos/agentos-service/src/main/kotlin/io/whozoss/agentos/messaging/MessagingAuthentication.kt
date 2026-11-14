package io.whozoss.agentos.messaging

import io.whozoss.agentos.security.declarative.AgentOsAuthentication
import io.whozoss.agentos.user.User
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Runs [block] with [user] authenticated on this thread, as
 * [io.whozoss.agentos.security.declarative.AgentOsAuthenticationFilter] does for a request, and puts the previous
 * context back after. The endpoints called inside, through their `*Api` beans, authorize that user exactly as
 * they would the same call from the browser: their `@PreAuthorize` and their inline guards
 * ([io.whozoss.agentos.user.UserService.getCurrentUser]) read this identity.
 *
 * [user] is the one a chat's account is linked to, never one named by a message, a button or an agent. [block]
 * must run on this thread from start to end: the messaging code blocks, it never suspends.
 */
internal fun <T> asUser(
    user: User,
    block: () -> T,
): T {
    val previous = SecurityContextHolder.getContext()
    SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext().also { it.authentication = AgentOsAuthentication(user) })
    return try {
        block()
    } finally {
        SecurityContextHolder.setContext(previous)
    }
}
