package io.whozoss.factory.workflow.sse

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests of the SSE hub framing and heartbeat.
 *
 * The captured frames are the exact bytes the hub writes to the wire, so this
 * locks in the byte-for-byte framing contract of
 * `factory/dashboard/workflow-projection-sse.mjs`:
 *
 * ```text
 * event: <name>\n
 * data: <json>\n
 * \n
 * ```
 *
 * and the `: heartbeat\n\n` comment frame.
 */
class WorkflowSseHubTest {

    /** Records the raw frame of every event the hub sends. */
    private class CapturingSseEmitter : SseEmitter(0L) {
        val frames = CopyOnWriteArrayList<String>()

        override fun send(builder: SseEventBuilder) {
            val data = builder.build().firstOrNull()?.data
            if (data is String) frames.add(data)
        }
    }

    @Test
    fun `publish frames a named event exactly like the Node hub`() {
        val hub = WorkflowSseHub(ObjectMapper(), heartbeatMs = 60_000)
        val emitter = CapturingSseEmitter()
        hub.register("ns-1", emitter)

        val payload = linkedMapOf<String, Any?>("workflowId" to "wf-1", "namespaceId" to "ns-1", "revision" to 2)
        hub.publish("ns-1", payload)

        assertThat(emitter.frames).containsExactly(
            "event: workflow-projection-updated\ndata: {\"workflowId\":\"wf-1\",\"namespaceId\":\"ns-1\",\"revision\":2}\n\n",
        )
    }

    @Test
    fun `lifecycle events use their named frames`() {
        val hub = WorkflowSseHub(ObjectMapper(), heartbeatMs = 60_000)
        val emitter = CapturingSseEmitter()
        hub.register("ns-2", emitter)

        hub.publish("ns-2", linkedMapOf("workflowId" to "wf-2", "namespaceId" to "ns-2"), WorkflowProjectionEvents.REMOVED)
        hub.publish("ns-2", linkedMapOf("workflowId" to "wf-2", "namespaceId" to "ns-2", "revision" to 3), WorkflowProjectionEvents.RESTORED)
        hub.publish("ns-2", linkedMapOf("workflowId" to "wf-2", "namespaceId" to "ns-2"), WorkflowProjectionEvents.PURGED)

        assertThat(emitter.frames).hasSize(3)
        assertThat(emitter.frames[0]).startsWith("event: workflow-projection-removed\ndata: ")
        assertThat(emitter.frames[1]).startsWith("event: workflow-projection-restored\ndata: ")
        assertThat(emitter.frames[2]).startsWith("event: workflow-projection-purged\ndata: ")
    }

    @Test
    fun `heartbeat comment frame is emitted on the configured interval`() {
        val hub = WorkflowSseHub(ObjectMapper(), heartbeatMs = 50)
        val emitter = CapturingSseEmitter()
        hub.register("ns-3", emitter)

        val deadline = System.currentTimeMillis() + 5_000
        while (emitter.frames.none { it == WorkflowSseHub.HEARTBEAT_FRAME } && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertThat(emitter.frames).contains(WorkflowSseHub.HEARTBEAT_FRAME)
        assertThat(WorkflowSseHub.HEARTBEAT_FRAME).isEqualTo(": heartbeat\n\n")
    }

    @Test
    fun `publish only reaches subscribers of the same namespace`() {
        val hub = WorkflowSseHub(ObjectMapper(), heartbeatMs = 60_000)
        val otherEmitter = CapturingSseEmitter()
        hub.register("ns-other", otherEmitter)
        hub.publish("ns-4", linkedMapOf("workflowId" to "wf-4"))
        assertThat(otherEmitter.frames).isEmpty()
        assertThat(hub.size("ns-other")).isEqualTo(1)
    }
}
