package io.whozoss.agentos.logging

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC

/**
 * Log correlation context: the SLF4J MDC keys AgentOS sets so that every log line of a case run
 * can be traced back to its case, namespace, agent and user.
 *
 * MDC is thread-local, while a case runs on coroutines that hop between threads and on Spring AI
 * callbacks executed on Reactor threads. The context is therefore carried as an [MDCContext]
 * coroutine element and re-applied explicitly on foreign threads ([withMdc]).
 *
 * The keys are set only where the identity is owned:
 * - [CASE_ID] / [NAMESPACE_ID]: [io.whozoss.agentos.caseFlow.CaseRuntime.run], for the whole turn;
 * - [AGENT_NAME] / [USER_ID]: the agent execution in [io.whozoss.agentos.caseFlow.CaseServiceImpl];
 * - tool callbacks re-apply the context captured when the agent run started.
 *
 * They are rendered by the console pattern (application.yml) and emitted as JSON fields by the
 * Logstash encoder of the docker profile.
 */
object LogContext {
    const val CASE_ID = "caseId"
    const val NAMESPACE_ID = "namespaceId"
    const val AGENT_NAME = "agentName"
    const val USER_ID = "userId"

    /** The keys AgentOS owns: never inherited from whatever a pooled thread last carried. */
    val KEYS: Set<String> = setOf(CASE_ID, NAMESPACE_ID, AGENT_NAME, USER_ID)

    /** The current thread MDC plus [entries]; null values are skipped. */
    fun merged(vararg entries: Pair<String, Any?>): Map<String, String> {
        val current = MDC.getCopyOfContextMap() ?: emptyMap()
        val additions = entries.mapNotNull { (key, value) -> value?.let { key to it.toString() } }
        return current + additions
    }

    /**
     * A coroutine context element carrying the current MDC plus [entries], to attach to a
     * `launch` / `async`. Unlike [withLogContext] it adds no cancellation check on entry, so it is
     * safe for undispatched launches that must always start.
     */
    fun mdcContext(vararg entries: Pair<String, Any?>): MDCContext = MDCContext(merged(*entries))
}

/**
 * Runs [block] with [entries] added to the MDC, propagated across suspensions and dispatchers.
 *
 * [block] has no CoroutineScope receiver: to start child coroutines that inherit the log context,
 * open one with `coroutineScope { ... }` inside it. A bare `launch`/`async` would bind to an
 * enclosing scope, whose context does not carry these entries.
 */
suspend fun <T> withLogContext(
    vararg entries: Pair<String, Any?>,
    block: suspend () -> T,
): T = withContext(LogContext.mdcContext(*entries)) { block() }

/**
 * Runs [block] with [context] merged into the thread's MDC, then restores the previous MDC.
 * For code running outside coroutines on threads AgentOS does not own (e.g. Spring AI tool
 * callbacks on Reactor threads), where the coroutine-propagated MDC is not available.
 *
 * Keys set by others on that thread (e.g. a trace id) are kept. The AgentOS keys ([LogContext.KEYS])
 * come only from [context]: a stale caseId left on a pooled thread never leaks into this block.
 */
inline fun <T> withMdc(
    context: Map<String, String>?,
    block: () -> T,
): T {
    val previous = MDC.getCopyOfContextMap()
    val merged = (previous ?: emptyMap()).filterKeys { it !in LogContext.KEYS } + (context ?: emptyMap())
    if (merged.isEmpty()) MDC.clear() else MDC.setContextMap(merged)
    try {
        return block()
    } finally {
        if (previous.isNullOrEmpty()) MDC.clear() else MDC.setContextMap(previous)
    }
}
