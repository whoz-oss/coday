package io.whozoss.agentos.plugins.factorybridge

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

/**
 * Durability of the plugin-wide Factory Bridge state (bindings and pending human
 * checkpoints) across a simulated AgentOS restart, exercised through the public
 * [FactoryBridgeServices] surface.
 */
class FactoryBridgeDurableStateSpec : StringSpec({
    val token = "secret-token-value-with-sufficient-length"

    fun withDir(block: (String) -> Unit) {
        val dir = Files.createTempDirectory("factory-bridge-services")
        try {
            block(dir.toAbsolutePath().toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    "pending human checkpoints survive a restart" {
        withDir { dir ->
            val caseId = UUID.randomUUID()
            val first = FactoryTestFixtures.services(dataDir = dir)
            first.pendingCheckpoints[caseId] = FactoryCheckpointRef("wf-1", "gate-1", 4L)

            val restarted = FactoryTestFixtures.services(dataDir = dir)
            restarted.pendingCheckpoints[caseId] shouldBe FactoryCheckpointRef("wf-1", "gate-1", 4L)
        }
    }

    "a terminal lifecycle invalidation removes the binding and pending checkpoint durably" {
        withDir { dir ->
            val caseId = UUID.randomUUID()
            val namespaceId = UUID.randomUUID()
            val first = FactoryTestFixtures.services(dataDir = dir)
            first.stepResultBindings.bind(
                FactoryStepResultBinding(caseId, namespaceId, "Worker", "attempt", "runtime", token, Instant.now().plusSeconds(60)),
            )
            first.pendingCheckpoints[caseId] = FactoryCheckpointRef("wf-1", "gate-1", 4L)

            FactoryCaseLifecycleObserver { first }
                .onStatusChanged(caseId, io.whozoss.agentos.sdk.caseFlow.CaseStatus.RUNNING, io.whozoss.agentos.sdk.caseFlow.CaseStatus.ERROR)

            first.stepResultBindings.contains(caseId) shouldBe false
            first.pendingCheckpoints[caseId] shouldBe null

            val restarted = FactoryTestFixtures.services(dataDir = dir)
            restarted.stepResultBindings.contains(caseId) shouldBe false
            restarted.pendingCheckpoints[caseId] shouldBe null
        }
    }

    "a binding accepted from the host transport survives a restart and blocks re-acquire" {
        withDir { dir ->
            val caseId = UUID.randomUUID()
            val namespaceId = UUID.randomUUID()
            val first = FactoryTestFixtures.services(dataDir = dir, secret = "shared-secret")
            FactoryBindingRegistrar { first }.register(
                caseId,
                namespaceId,
                "shared-secret",
                mapOf(
                    FactoryBindingRegistrar.ATTRIBUTE_ATTEMPT_ID to "attempt",
                    FactoryBindingRegistrar.ATTRIBUTE_CAPABILITY_TOKEN to token,
                    FactoryBindingRegistrar.ATTRIBUTE_RUNTIME_ID to "runtime",
                ),
                Instant.now().plusSeconds(60),
            ) shouldBe true
            first.stepResultBindings.acquire(caseId, namespaceId, "Worker") shouldBe first.stepResultBindings.find(caseId)

            val restarted = FactoryTestFixtures.services(dataDir = dir, secret = "shared-secret")
            restarted.stepResultBindings.contains(caseId) shouldBe true
            restarted.stepResultBindings.acquire(caseId, namespaceId, "Worker") shouldBe null
        }
    }
})
