package io.whozoss.factory.workflow.sse

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests of the SSE hub framing, transaction boundary, scope partitioning,
 * disconnect handling and heartbeat.
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
 * plus the `: open\n\n` initial frame and the `: heartbeat\n\n` comment frame.
 */
class WorkflowSseHubTest {

    private val scope = TenantScope("org-a", "ws-1")
    private val otherScope = TenantScope("org-b", "ws-1")

    /** Records the raw frame of every event the hub sends. */
    private class CapturingSseEmitter : SseEmitter(0L) {
        val frames = CopyOnWriteArrayList<String>()

        override fun send(builder: SseEventBuilder) {
            val data = builder.build().firstOrNull()?.data
            if (data is String) frames.add(data)
        }
    }

    /** Records the initial frame, then fails every named event like a broken pipe. */
    private class DisconnectingSseEmitter : SseEmitter(0L) {
        val frames = CopyOnWriteArrayList<String>()

        override fun send(builder: SseEventBuilder) {
            val data = builder.build().firstOrNull()?.data as? String ?: return
            if (data.startsWith("event:")) throw IOException("Broken pipe")
            frames.add(data)
        }
    }

    /**
     * Minimal [AbstractPlatformTransactionManager] giving the hub real Spring
     * synchronization semantics (actual transaction active + synchronization
     * registered + `afterCommit` invoked on commit, never on rollback).
     */
    private class StubTransactionManager : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()
        override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
        override fun doCommit(status: DefaultTransactionStatus) = Unit
        override fun doRollback(status: DefaultTransactionStatus) = Unit
        override fun doSetRollbackOnly(status: DefaultTransactionStatus) = Unit
    }

    private fun hub() = WorkflowSseHub(ObjectMapper(), heartbeatMs = 60_000)

    // ------------------------------------------------------------------
    // Framing
    // ------------------------------------------------------------------

    @Test
    fun `register sends the initial open frame immediately`() {
        val hub = hub()
        val emitter = CapturingSseEmitter()
        hub.register(scope, "ns-open", emitter)

        assertThat(WorkflowSseHub.OPEN_FRAME).isEqualTo(": open\n\n")
        assertThat(emitter.frames).contains(WorkflowSseHub.OPEN_FRAME)
    }

    @Test
    fun `publish frames a named event exactly like the Node hub`() {
        val hub = hub()
        val emitter = CapturingSseEmitter()
        hub.register("ns-1", emitter)
        emitter.frames.clear()

        val payload = linkedMapOf<String, Any?>("workflowId" to "wf-1", "namespaceId" to "ns-1", "revision" to 2)
        hub.publish("ns-1", payload)

        assertThat(emitter.frames).containsExactly(
            "event: workflow-projection-updated\ndata: {\"workflowId\":\"wf-1\",\"namespaceId\":\"ns-1\",\"revision\":2}\n\n",
        )
    }

    @Test
    fun `lifecycle events use their named frames`() {
        val hub = hub()
        val emitter = CapturingSseEmitter()
        hub.register("ns-2", emitter)
        emitter.frames.clear()

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
        hub.register(scope, "ns-3", emitter)

        val deadline = System.currentTimeMillis() + 5_000
        while (emitter.frames.none { it == WorkflowSseHub.HEARTBEAT_FRAME } && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertThat(emitter.frames).contains(WorkflowSseHub.HEARTBEAT_FRAME)
        assertThat(WorkflowSseHub.HEARTBEAT_FRAME).isEqualTo(": heartbeat\n\n")
    }

    // ------------------------------------------------------------------
    // Transaction boundary
    // ------------------------------------------------------------------

    @Test
    fun `publish is deferred until the transaction commits`() {
        val hub = hub()
        val emitter = CapturingSseEmitter()
        hub.register(scope, "ns-tx", emitter)
        emitter.frames.clear()

        val template = TransactionTemplate(StubTransactionManager())
        var sentBeforeCommit = false
        template.execute {
            hub.publish(scope, "ns-tx", mapOf("workflowId" to "wf-tx", "revision" to 7))
            sentBeforeCommit = emitter.frames.isNotEmpty()
            null
        }

        assertThat(sentBeforeCommit).isFalse()
        assertThat(emitter.frames).hasSize(1)
        assertThat(emitter.frames[0]).contains("\"revision\":7")
    }

    @Test
    fun `no event is emitted when the transaction rolls back`() {
        val hub = hub()
        val emitter = CapturingSseEmitter()
        hub.register(scope, "ns-rollback", emitter)
        emitter.frames.clear()

        val template = TransactionTemplate(StubTransactionManager())
        assertThrows<IllegalStateException> {
            template.execute {
                hub.publish(scope, "ns-rollback", mapOf("workflowId" to "wf-rb", "revision" to 9))
                throw IllegalStateException("boom")
            }
        }

        assertThat(emitter.frames).isEmpty()
    }

    @Test
    fun `publish outside a transaction is emitted immediately`() {
        val hub = hub()
        val emitter = CapturingSseEmitter()
        hub.register(scope, "ns-immediate", emitter)
        emitter.frames.clear()

        hub.publish(scope, "ns-immediate", mapOf("workflowId" to "wf-now", "revision" to 1))

        assertThat(emitter.frames).hasSize(1)
        assertThat(emitter.frames[0]).contains("wf-now")
    }

    // ------------------------------------------------------------------
    // Scope partitioning
    // ------------------------------------------------------------------

    @Test
    fun `scoped publish only reaches subscribers of the same tenant scope`() {
        val hub = hub()
        val inTenant = CapturingSseEmitter()
        val crossTenant = CapturingSseEmitter()
        hub.register(scope, "ns-shared", inTenant)
        hub.register(otherScope, "ns-shared", crossTenant)
        inTenant.frames.clear()
        crossTenant.frames.clear()

        hub.publish(scope, "ns-shared", mapOf("workflowId" to "wf-scope"))

        assertThat(inTenant.frames).hasSize(1)
        assertThat(crossTenant.frames).isEmpty()
    }

    @Test
    fun `blank namespace is a tenant-scoped fanout key, never a global bucket`() {
        val hub = hub()
        assertThat(hub.subscriptionKey(scope, null)).isNotEqualTo("")
        assertThat(hub.subscriptionKey(scope, null)).isNotEqualTo(hub.subscriptionKey(scope, "ns"))
        assertThat(hub.subscriptionKey(scope, null)).isNotEqualTo(hub.subscriptionKey(otherScope, null))
    }

    @Test
    fun `whole-tenant subscribers receive concrete namespace invalidations of their tenant only`() {
        val hub = hub()
        val tenantWide = CapturingSseEmitter()
        val otherTenantWide = CapturingSseEmitter()
        hub.register(scope, null, tenantWide)
        hub.register(otherScope, null, otherTenantWide)
        tenantWide.frames.clear()
        otherTenantWide.frames.clear()

        hub.publish(scope, "ns-concrete", mapOf("revision" to 3))

        assertThat(tenantWide.frames).hasSize(1)
        assertThat(tenantWide.frames[0]).contains("\"revision\":3")
        assertThat(otherTenantWide.frames).isEmpty()
    }

    @Test
    fun `blank namespace publish only reaches whole-tenant subscribers`() {
        val hub = hub()
        val tenantWide = CapturingSseEmitter()
        val namespaceScoped = CapturingSseEmitter()
        hub.register(scope, null, tenantWide)
        hub.register(scope, "ns-x", namespaceScoped)
        tenantWide.frames.clear()
        namespaceScoped.frames.clear()

        hub.publish(scope, null, mapOf("revision" to 5))

        assertThat(tenantWide.frames).hasSize(1)
        assertThat(namespaceScoped.frames).isEmpty()
    }

    @Test
    fun `publish only reaches subscribers of the same namespace`() {
        val hub = hub()
        val otherEmitter = CapturingSseEmitter()
        hub.register("ns-other", otherEmitter)
        hub.publish("ns-4", linkedMapOf("workflowId" to "wf-4"))
        assertThat(otherEmitter.frames).doesNotContain("event: workflow-projection-updated\ndata: {\"workflowId\":\"wf-4\"}\n\n")
        assertThat(hub.size("ns-other")).isEqualTo(1)
    }

    // ------------------------------------------------------------------
    // Disconnect handling
    // ------------------------------------------------------------------

    @Test
    fun `a disconnected client is dropped without failing publish or other clients`() {
        val hub = hub()
        val dead = DisconnectingSseEmitter()
        val healthy = CapturingSseEmitter()
        hub.register(scope, "ns-dead", dead)
        hub.register(scope, "ns-dead", healthy)
        healthy.frames.clear()

        // Must not throw, even though `dead.send` raises a broken pipe.
        hub.publish(scope, "ns-dead", mapOf("workflowId" to "wf-dead", "revision" to 1))

        assertThat(healthy.frames).hasSize(1)
        assertThat(hub.size(scope, "ns-dead")).isEqualTo(1)
    }

    @Test
    fun `a client whose write fails after a delay is dropped without failing publish`() {
        val hub = hub()
        val delayedFailure = object : SseEmitter(0L) {
            val frames = CopyOnWriteArrayList<String>()
            override fun send(builder: SseEventBuilder) {
                val data = builder.build().firstOrNull()?.data as? String ?: return
                if (data.startsWith("event:")) {
                    Thread.sleep(50)
                    throw IOException("Broken pipe")
                }
                frames.add(data)
            }
        }
        val healthy = CapturingSseEmitter()
        hub.register(scope, "ns-slow", delayedFailure)
        hub.register(scope, "ns-slow", healthy)
        healthy.frames.clear()

        hub.publish(scope, "ns-slow", mapOf("workflowId" to "wf-slow", "revision" to 1))

        assertThat(healthy.frames).hasSize(1)
        assertThat(hub.size(scope, "ns-slow")).isEqualTo(1)
    }

    @Test
    fun `client disconnect exceptions are classified as expected`() {
        val hub = hub()
        assertThat(hub.isClientDisconnect(IOException("Broken pipe"))).isTrue()
        assertThat(hub.isClientDisconnect(IOException("Connection reset by peer"))).isTrue()
        assertThat(hub.isClientDisconnect(IllegalStateException("boom"))).isFalse()
        assertThat(hub.isClientDisconnect(null)).isFalse()
    }

    @Test
    fun `container disconnect class names are classified as expected`() {
        val hub = hub()
        // The hub classifies by the throwable's binary class name (with cause walk),
        // so a nested type named after the containers is enough to exercise it.
        assertThat(hub.isClientDisconnect(ClientAbortException("broken"))).isTrue()
        assertThat(hub.isClientDisconnect(EofException("eof"))).isTrue()
        assertThat(hub.isClientDisconnect(AsyncRequestTimeoutException("timeout"))).isTrue()
        assertThat(hub.isClientDisconnect(RuntimeException("real server fault"))).isFalse()
    }

    /** Binary-name marker matching Tomcat's `ClientAbortException`. */
    private class ClientAbortException(message: String) : IOException(message)

    /** Binary-name marker matching Jetty's `EofException`. */
    private class EofException(message: String) : IOException(message)

    /** Binary-name marker matching Spring's `AsyncRequestTimeoutException`. */
    private class AsyncRequestTimeoutException(message: String) : RuntimeException(message)

    @Test
    fun `a failing emitter is dropped and never double-completed`() {
        val hub = hub()
        val unsupported = object : SseEmitter(0L) {
            override fun send(builder: SseEventBuilder) {
                throw IllegalStateException("ResponseBodyEmitter has already completed")
            }
        }
        hub.register(scope, "ns-unsupported", unsupported)
        hub.publish(scope, "ns-unsupported", mapOf("revision" to 1))
        assertThat(hub.size(scope, "ns-unsupported")).isEqualTo(0)
    }
}
