package io.whozoss.factory.workflow.sse

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
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
 * Port of `factory/dashboard/workflow-projection-sse.mjs`. It is deliberately
 * mono-process and in-memory: on reconnect the HTTP list/detail APIs stay
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
 * with a `: heartbeat\n\n` comment frame every [heartbeatMs] (30s).
 */
@Component
class WorkflowSseHub(
    private val objectMapper: ObjectMapper,
    private val heartbeatMs: Long = 30_000,
) {

    private class Client(
        val emitter: SseEmitter,
        @Volatile var heartbeat: ScheduledFuture<*>? = null,
        @Volatile var closed: Boolean = false,
    )

    private val clients = ConcurrentHashMap<String, MutableSet<Client>>()

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1) { runnable ->
        Thread(runnable, "workflow-sse-heartbeat").apply { isDaemon = true }
    }

    /** Registers a new SSE connection for [namespaceId] and starts its heartbeat. */
    fun register(namespaceId: String): SseEmitter = register(namespaceId, SseEmitter(0L))

    /** Registers a caller-provided emitter (used by tests to observe exact frames). */
    fun register(namespaceId: String, emitter: SseEmitter): SseEmitter {
        val client = Client(emitter)
        clients.computeIfAbsent(namespaceId) { ConcurrentHashMap.newKeySet() }.add(client)

        val remove = { removeClient(namespaceId, client) }
        emitter.onCompletion(remove)
        emitter.onTimeout(remove)
        emitter.onError { remove() }
        client.heartbeat = scheduler.scheduleAtFixedRate(
            { sendHeartbeat(namespaceId, client) },
            heartbeatMs,
            heartbeatMs,
            TimeUnit.MILLISECONDS,
        )
        return emitter
    }

    private fun removeClient(namespaceId: String, client: Client) {
        if (client.closed) return
        client.closed = true
        client.heartbeat?.cancel(false)
        val namespaceClients = clients[namespaceId] ?: return
        namespaceClients.remove(client)
        if (namespaceClients.isEmpty()) clients.remove(namespaceId, namespaceClients)
    }

    private fun sendHeartbeat(namespaceId: String, client: Client) {
        if (client.closed) return
        try {
            client.emitter.send(RawSseEvent(HEARTBEAT_FRAME))
        } catch (_: Exception) {
            removeClient(namespaceId, client)
        }
    }

    /** Publishes a named event to every connection of [namespaceId]. */
    fun publish(namespaceId: String, payload: Any?, event: String = WorkflowProjectionEvents.UPDATED) {
        val frame = "event: $event\ndata: ${objectMapper.writeValueAsString(payload)}\n\n"
        val namespaceClients = clients[namespaceId] ?: return
        for (client in namespaceClients.toList()) {
            if (client.closed) continue
            try {
                client.emitter.send(RawSseEvent(frame))
            } catch (_: Exception) {
                removeClient(namespaceId, client)
            }
        }
    }

    /** Number of live connections for a namespace (test/introspection hook). */
    fun size(namespaceId: String): Int = clients[namespaceId]?.count { !it.closed } ?: 0

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
    }
}
