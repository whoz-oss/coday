package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.plugins.factorybridge.persistence.FactoryBridgeStateStore
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Durability and restart semantics of the Factory step-result binding registry.
 *
 * Each test simulates an AgentOS restart by discarding the first registry/store pair and
 * opening a fresh one over the same directory, verifying that bindings, leases and
 * fail-closed invalidation all survive.
 */
class FactoryStepResultBindingRegistryDurabilitySpec : StringSpec({
    val now = Instant.parse("2030-01-01T00:00:00Z")
    val clock = Clock.fixed(now, ZoneOffset.UTC)
    val token = "secret-token-value-with-sufficient-length"

    fun withStore(block: (java.nio.file.Path) -> Unit) {
        val dir = Files.createTempDirectory("factory-bridge-registry")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    fun registry(dir: java.nio.file.Path) =
        FactoryStepResultBindingRegistry(clock, FactoryBridgeStateStore.open(dir.toAbsolutePath().toString(), jacksonObjectMapper()))

    "an unfinished binding and its lease survive a simulated restart" {
        withStore { dir ->
            val caseId = UUID.randomUUID()
            val namespaceId = UUID.randomUUID()
            val first = registry(dir)
            first.bind(FactoryStepResultBinding(caseId, namespaceId, "Worker", "attempt", "runtime", token, now.plusSeconds(60)))
            first.acquire(caseId, namespaceId, "Worker").shouldNotBeNull()

            // Restart.
            val restarted = registry(dir)
            restarted.contains(caseId) shouldBe true
            // Single-flight: the lease acquired before the restart is still held.
            restarted.acquire(caseId, namespaceId, "Worker") shouldBe null
            restarted.find(caseId)!!.leased.get() shouldBe true
        }
    }

    "single-flight CAS is preserved across a restart and release unblocks the next acquire" {
        withStore { dir ->
            val caseId = UUID.randomUUID()
            val namespaceId = UUID.randomUUID()
            val first = registry(dir)
            first.bind(FactoryStepResultBinding(caseId, namespaceId, "Worker", "attempt", "runtime", token, now.plusSeconds(60)))
            first.acquire(caseId, namespaceId, "Worker").shouldNotBeNull()

            val restarted = registry(dir)
            restarted.acquire(caseId, namespaceId, "Worker") shouldBe null

            val reloaded = restarted.find(caseId).shouldNotBeNull()
            restarted.release(reloaded)
            restarted.acquire(caseId, namespaceId, "Worker").shouldNotBeNull()

            restarted.acknowledge(reloaded)
            // Acknowledged bindings are removed from memory and from the durable store.
            registry(dir).contains(caseId) shouldBe false
        }
    }

    "fail-closed: a terminal invalidation is durable and is not resurrected by a restart" {
        withStore { dir ->
            val caseId = UUID.randomUUID()
            val namespaceId = UUID.randomUUID()
            val first = registry(dir)
            first.bind(FactoryStepResultBinding(caseId, namespaceId, "Worker", "attempt", "runtime", token, now.plusSeconds(60)))
            first.remove(caseId)

            registry(dir).contains(caseId) shouldBe false
        }
    }

    // An attempt whose capability lapsed while AgentOS was down must still be renewable:
    // the binding is the input of the renewal, so expiry alone never evicts it.
    "an expired binding survives a restart and stays renewable" {
        withStore { dir ->
            val caseId = UUID.randomUUID()
            val namespaceId = UUID.randomUUID()
            val first = registry(dir)
            first.bind(FactoryStepResultBinding(caseId, namespaceId, "Worker", "attempt", "runtime", token, now.plusSeconds(60)))

            // A later clock makes the persisted binding expired.
            val later = FactoryStepResultBindingRegistry(
                Clock.fixed(now.plusSeconds(120), ZoneOffset.UTC),
                FactoryBridgeStateStore.open(dir.toAbsolutePath().toString(), jacksonObjectMapper()),
            )
            later.contains(caseId) shouldBe true
            // Ordinary redemption still refuses it — only an authorized renewal may take it.
            later.acquire(caseId, namespaceId, "Worker") shouldBe null
            later.acquire(caseId, namespaceId, "Worker", allowExpired = true).shouldNotBeNull()
        }
    }
})
