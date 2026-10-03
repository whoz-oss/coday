package io.whozoss.factory.adapter.agentos

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process AgentOS SSE test double on the JDK's built-in HTTP server.
 *
 * Each connection to `/api/cases/{caseId}/events` dequeues one [Script]: a
 * list of raw SSE lines (with optional inter-line delays) written to the
 * stream, after which the connection closes — unless [Script.holdAfterMs]
 * keeps it open (stall/heartbeat scenarios). When no script is enqueued the
 * connection replays nothing and closes immediately (a "dead" case).
 */
class FakeAgentOsSseServer : AutoCloseable {

    data class ScriptedFrame(val line: String, val delayAfterMs: Long = 0)

    data class Script(
        val frames: List<ScriptedFrame> = emptyList(),
        val holdAfterMs: Long = 0,
    )

    private val scripts = ConcurrentLinkedQueue<Script>()
    val connectionCount = AtomicInteger(0)

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/api/cases/") { exchange ->
            connectionCount.incrementAndGet()
            if (!exchange.requestURI.path.endsWith("/events")) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }
            val script = scripts.poll() ?: Script()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0) // chunked streaming
            try {
                exchange.responseBody.use { out ->
                    for (frame in script.frames) {
                        out.write((frame.line + "\n").toByteArray(Charsets.UTF_8))
                        out.flush()
                        if (frame.delayAfterMs > 0) Thread.sleep(frame.delayAfterMs)
                    }
                    if (script.holdAfterMs > 0) Thread.sleep(script.holdAfterMs)
                }
            } catch (_: Exception) {
                // the client went away mid-script — expected in drop/timeout scenarios
            } finally {
                exchange.close()
            }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    fun enqueue(script: Script) {
        scripts.add(script)
    }

    fun enqueueEvents(vararg events: String, holdAfterMs: Long = 0) {
        enqueue(Script(events.flatMap { sseEvent(it) }, holdAfterMs))
    }

    override fun close() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    companion object {
        /** Render one JSON event as the SSE frame lines of the `case-event` channel. */
        fun sseEvent(eventJson: String, id: String? = null, delayAfterMs: Long = 0): List<ScriptedFrame> {
            val eventId = id ?: Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(eventJson)?.groupValues?.get(1)
                ?: error("test event carries no id: $eventJson")
            return listOf(
                ScriptedFrame("event: case-event"),
                ScriptedFrame("id: $eventId"),
                ScriptedFrame("data: $eventJson", delayAfterMs),
                ScriptedFrame(""),
            )
        }

        fun heartbeat(delayAfterMs: Long = 0): ScriptedFrame = ScriptedFrame(":keep-alive", delayAfterMs)

        fun statusEvent(id: String, caseId: String, status: String, timestamp: String = "2026-01-01T00:00:00Z"): String =
            """{"id":"$id","type":"CaseStatusEvent","status":"$status","caseId":"$caseId","timestamp":"$timestamp"}"""

        fun agentMessageEvent(id: String, caseId: String, text: String, timestamp: String = "2026-01-01T00:00:01Z"): String =
            """{"id":"$id","type":"MessageEvent","caseId":"$caseId","timestamp":"$timestamp",""" +
                """"actor":{"role":"AGENT"},"content":[{"content":"$text"}]}"""

        fun questionEvent(id: String, caseId: String, question: String, timestamp: String = "2026-01-01T00:00:01Z"): String =
            """{"id":"$id","type":"QuestionEvent","question":"$question","caseId":"$caseId","timestamp":"$timestamp"}"""

        fun answerEvent(id: String, caseId: String, questionId: String, timestamp: String = "2026-01-01T00:00:02Z"): String =
            """{"id":"$id","type":"AnswerEvent","questionId":"$questionId","caseId":"$caseId","timestamp":"$timestamp"}"""

        fun transientEvent(id: String, caseId: String, type: String, timestamp: String = "2026-01-01T00:00:00Z"): String =
            """{"id":"$id","type":"$type","caseId":"$caseId","timestamp":"$timestamp"}"""
    }
}
