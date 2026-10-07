package io.whozoss.factory.workflow.sse

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import mu.KotlinLogging
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextClosedEvent
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.EOFException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Named events the projection hub emits, matching the Node SSE hub. */
object WorkflowProjectionEvents {
    const val UPDATED = "workflow-projection-updated"
    const val REMOVED = "workflow-projection-removed"
    const val RESTORED = "workflow-projection-restored"
    const val PURGED = "workflow-projection-purged"
}

/**
 * Namespace-scoped, best-effort in-memory SSE hub for projection invalidation
 * hints.
 *
 * Deliberately mono-process and in-memory: on reconnect the HTTP list/detail APIs stay
 * authoritative and close any notification gap.
 *
 * Framing is byte-for-byte identical to the Node hub:
 *
 * ```text
 * event: <event-name>\n
 * data: <json-payload>\n
 * \n
 * ```
 *
 * with a `: heartbeat\n\n` comment frame every [heartbeatMs] (30s) and an
 * immediate `: open\n\n` comment frame as soon as a client registers.
 *
 * ## Transaction boundary
 *
 * Every [publish] is deferred to [TransactionSynchronization.afterCommit] when a
 * transaction is active, so an invalidation hint is only ever emitted for state
 * that was durably committed — a rolled-back transaction emits nothing. Outside
 * a transaction (the non-transactional DAG sequencer, unit tests) the event is
 * emitted immediately.
 *
 * ## Scope contract
 *
 * Subscriptions are always partitioned by the verified [TenantScope]; a
 * `namespaceId` only narrows the subscription *within* the caller's tenant. A
 * blank namespace is mapped to a tenant-scoped fanout key — never to an
 * unpartitioned global bucket — so two tenants can never observe each other's
 * invalidations.
 */
@Component
class WorkflowSseHub(
    private val objectMapper: ObjectMapper,
    private val heartbeatMs: Long = 30_000,
) : ApplicationListener<ContextClosedEvent> {

    private val logger = KotlinLogging.logger {}

    private class Client(
        val emitter: SseEmitter,
        @Volatile var heartbeat: ScheduledFuture<*>? = null,
        @Volatile var closed: Boolean = false,
    )

    private val clients = ConcurrentHashMap<String, MutableSet<Client>>()

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1) { runnable ->
        Thread(runnable, "workflow-sse-heartbeat").apply { isDaemon = true }
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    /** Registers a new SSE connection for the raw [namespaceId] key and starts its heartbeat. */
    fun register(namespaceId: String): SseEmitter = register(namespaceId, SseEmitter(0L))

    /**
     * Registers a caller-provided emitter under the raw [namespaceId] key.
     *
     * This overload keys the connection on the opaque string verbatim (legacy /
     * unit-test use). Production callers must use the scope-aware overload so
     * connections are partitioned by tenant.
     */
    fun register(namespaceId: String, emitter: SseEmitter): SseEmitter = registerClient(namespaceId, emitter)

    /**
     * Registers for [namespaceId] **within** [scope]. A blank/absent namespace is
     * a whole-tenant subscription, still partitioned by [scope] so it can never
     * observe another tenant's invalidations.
     */
    fun register(scope: TenantScope, namespaceId: String?): SseEmitter = register(scope, namespaceId, SseEmitter(0L))

    /** Registers a caller-provided scoped emitter (used by tests to observe exact frames). */
    fun register(scope: TenantScope, namespaceId: String?, emitter: SseEmitter): SseEmitter =
        registerClient(subscriptionKey(scope, namespaceId), emitter)

    private fun registerClient(key: String, emitter: SseEmitter): SseEmitter {
        val client = Client(emitter)
        clients.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(client)

        emitter.onCompletion { removeClient(key, client) }
        // Complete the emitter on async timeout so the servlet container is not
        // left holding an open async request (which stalls graceful shutdown and
        // surfaces an `AsyncRequestTimeoutException`). The container has already
        // torn the async request down; a defensive `complete()` must never surface
        // a secondary error, so it is guarded and best-effort.
        emitter.onTimeout {
            if (!client.closed) {
                runCatching { emitter.complete() }
                    .onFailure { ex -> logger.debug(ex) { "SSE emitter complete() on timeout was refused" } }
            }
            removeClient(key, client)
        }
        emitter.onError { removeClient(key, client) }
        client.heartbeat = scheduler.scheduleAtFixedRate(
            { sendHeartbeat(key, client) },
            heartbeatMs,
            heartbeatMs,
            TimeUnit.MILLISECONDS,
        )
        // Immediate initial framing: Spring buffers a send performed before the
        // response body emitter is handed back, then flushes it as soon as the
        // stream is initialized — so the client observes an open stream without
        // waiting for the first heartbeat.
        sendFrame(key, client, OPEN_FRAME, "open")
        return emitter
    }

    private fun removeClient(key: String, client: Client) {
        if (client.closed) return
        client.closed = true
        client.heartbeat?.cancel(false)
        val namespaceClients = clients[key] ?: return
        namespaceClients.remove(client)
        if (namespaceClients.isEmpty()) clients.remove(key, namespaceClients)
    }

    // ------------------------------------------------------------------
    // Publishing
    // ------------------------------------------------------------------

    /** Publishes a named event to every connection of the raw [namespaceId] key. */
    fun publish(namespaceId: String, payload: Any?, event: String = WorkflowProjectionEvents.UPDATED) {
        publishAfterCommit { dispatch(namespaceId, payload, event) }
    }

    /**
     * Publishes a named event to every connection subscribed to [namespaceId]
     * within [scope]. A concrete namespace reaches its namespace subscribers and
     * the whole-tenant subscribers of the same scope; a blank namespace reaches
     * only the whole-tenant subscribers.
     */
    fun publish(scope: TenantScope, namespaceId: String?, payload: Any?, event: String = WorkflowProjectionEvents.UPDATED) {
        publishAfterCommit { publishScoped(scope, namespaceId, payload, event) }
    }

    /**
     * Runs [block] strictly after the active transaction commits, or immediately
     * when no transaction is active. On rollback the synchronization's
     * `afterCommit` is never invoked, so nothing is emitted.
     */
    private fun publishAfterCommit(block: () -> Unit) {
        val transactionActive = TransactionSynchronizationManager.isActualTransactionActive()
        val synchronizationActive = TransactionSynchronizationManager.isSynchronizationActive()
        if (transactionActive && synchronizationActive) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        // A best-effort SSE dispatch must never turn a committed
                        // transaction into a failure: swallow and log.
                        runCatching(block).onFailure { ex ->
                            logger.warn(ex) { "SSE publish after commit failed" }
                        }
                    }
                },
            )
            return
        }
        block()
    }

    private fun publishScoped(scope: TenantScope, namespaceId: String?, payload: Any?, event: String) {
        val concrete = namespaceId?.takeIf { it.isNotBlank() }
        if (concrete == null) {
            dispatch(subscriptionKey(scope, null), payload, event)
            return
        }
        dispatch(subscriptionKey(scope, concrete), payload, event)
        // Whole-tenant subscribers of the same scope observe every concrete
        // namespace invalidation; other tenants never do.
        dispatch(subscriptionKey(scope, null), payload, event)
    }

    private fun dispatch(key: String, payload: Any?, event: String) {
        val namespaceClients = clients[key] ?: return
        if (namespaceClients.isEmpty()) return
        val frame = try {
            "event: $event\ndata: ${objectMapper.writeValueAsString(payload)}\n\n"
        } catch (ex: Exception) {
            logger.warn(ex) { "SSE payload for event '$event' could not be serialized; event dropped" }
            return
        }
        for (client in namespaceClients.toList()) {
            sendFrame(key, client, frame, "event '$event'")
        }
    }

    // ------------------------------------------------------------------
    // Emission + disconnect handling
    // ------------------------------------------------------------------

    private fun sendHeartbeat(key: String, client: Client) {
        sendFrame(key, client, HEARTBEAT_FRAME, "heartbeat")
    }

    /**
     * Best-effort frame emission to one client.
     *
     * A client/proxy disconnect (Tomcat `ClientAbortException`, Jetty `EofException`,
     * a broken pipe / reset connection, an async timeout) is expected and is
     * swallowed at debug level: the container has already closed the response, so
     * calling `complete()` / `completeWithError()` would only produce a secondary
     * error. Any other failure is logged but, like a disconnect, never rethrown —
     * a slow or dead consumer must never fail the caller's business execution.
     */
    private fun sendFrame(key: String, client: Client, frame: String, label: String) {
        if (client.closed) return
        try {
            client.emitter.send(RawSseEvent(frame))
        } catch (ex: Exception) {
            if (isClientDisconnect(ex)) {
                logger.debug { "SSE client disconnected while sending $label frame: ${ex.message}" }
            } else {
                logger.warn(ex) { "SSE send failed for $label frame; client dropped" }
            }
            removeClient(key, client)
        }
    }

    /** Number of live connections for a raw namespace key (test/introspection hook). */
    fun size(namespaceId: String): Int = clients[namespaceId]?.count { !it.closed } ?: 0

    /** Number of live connections for a scoped subscription (test/introspection hook). */
    fun size(scope: TenantScope, namespaceId: String?): Int = size(subscriptionKey(scope, namespaceId))

    /**
     * Completes every live emitter when the context closes.
     *
     * [ContextClosedEvent] is published before Spring Boot's graceful web-server
     * shutdown starts waiting for active requests, so completing the streams here
     * lets the connector drain immediately instead of blocking for the full
     * `spring.lifecycle.timeout-per-shutdown-phase` (30s) and then logging
     * "Graceful shutdown aborted with one or more requests still active".
     */
    override fun onApplicationEvent(event: ContextClosedEvent) {
        for ((key, namespaceClients) in clients) {
            for (client in namespaceClients.toList()) {
                if (!client.closed) {
                    runCatching { client.emitter.complete() }
                        .onFailure { ex -> logger.debug(ex) { "SSE emitter already closed during shutdown" } }
                }
                removeClient(key, client)
            }
        }
        scheduler.shutdownNow()
    }

    // ------------------------------------------------------------------
    // Scope + exception classification
    // ------------------------------------------------------------------

    /**
     * The explicit, tenant-partitioned registry key of a subscription:
     *
     * - concrete namespace → `namespace:<org>:<workstream>:<namespaceId>`
     * - blank namespace    → `tenant:<org>:<workstream>` (whole-tenant fanout)
     *
     * A blank namespace therefore maps to a *scoped* key, never to a shared
     * global bucket that would leak across tenants.
     */
    fun subscriptionKey(scope: TenantScope, namespaceId: String?): String {
        val concrete = namespaceId?.takeIf { it.isNotBlank() }
        return if (concrete == null) {
            "tenant:${scope.organizationId}:${scope.workstreamId}"
        } else {
            "namespace:${scope.organizationId}:${scope.workstreamId}:$concrete"
        }
    }

    /**
     * Whether [error] (or any cause) denotes an expected client/proxy
     * disconnection rather than a real server fault.
     */
    fun isClientDisconnect(error: Throwable?): Boolean {
        var current = error
        var depth = 0
        while (current != null && depth++ < MAX_CAUSE_DEPTH) {
            if (current is EOFException) return true
            val className = current.javaClass.name
            if (className.endsWith("ClientAbortException") ||
                className.endsWith("EofException") ||
                className.endsWith("AsyncRequestTimeoutException") ||
                className.endsWith("AsyncRequestNotUsableException")
            ) {
                return true
            }
            val message = current.message?.lowercase()
            if (message != null && DISCONNECT_MESSAGES.any { message.contains(it) }) return true
            val cause = current.cause
            if (cause === current) break
            current = cause
        }
        return false
    }

    /**
     * An [SseEmitter.SseEventBuilder] that writes a pre-framed raw SSE frame
     * verbatim (no Spring `event:`/`data:` re-formatting), so the wire bytes are
     * exactly the Node hub's.
     */
    private class RawSseEvent(private val frame: String) : SseEmitter.SseEventBuilder {
        override fun id(id: String): SseEmitter.SseEventBuilder = this
        override fun name(eventName: String): SseEmitter.SseEventBuilder = this
        override fun reconnectTime(reconnectTime: Long): SseEmitter.SseEventBuilder = this
        override fun comment(comment: String): SseEmitter.SseEventBuilder = this
        override fun data(`object`: Any): SseEmitter.SseEventBuilder = this
        override fun data(`object`: Any, mediaType: MediaType?): SseEmitter.SseEventBuilder = this

        override fun build(): MutableSet<ResponseBodyEmitter.DataWithMediaType> =
            mutableSetOf(ResponseBodyEmitter.DataWithMediaType(frame, MediaType.TEXT_PLAIN))
    }

    companion object {
        /** Heartbeat comment frame, byte-for-byte identical to the Node hub. */
        const val HEARTBEAT_FRAME = ": heartbeat\n\n"

        /**
         * Initial framing comment frame: sent as soon as a client registers so
         * the stream opening is observed immediately (before the first heartbeat).
         */
        const val OPEN_FRAME = ": open\n\n"

        private const val MAX_CAUSE_DEPTH = 10

        /** Lower-case message fragments that denote an expected client disconnect. */
        private val DISCONNECT_MESSAGES = listOf(
            "broken pipe",
            "connection reset",
            "connection abort",
            "stream closed",
            "socket closed",
            "eof",
            "async request timed out",
            "asyncrequesttimeout",
        )
    }
}
