package io.whozoss.agentos.plugins.factorybridge

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

class FactoryStepResultBindingControllerSpec : StringSpec({
    val secret = "shared-secret"
    val namespaceId = UUID.randomUUID()
    val caseId = UUID.randomUUID()

    fun controller(
        registry: FactoryStepResultBindingRegistry = FactoryStepResultBindingRegistry(),
    ) = FactoryStepResultBindingController(
        registry = registry,
        caseNamespace = { id -> if (id == caseId) namespaceId else null },
        secret = secret,
    )

    fun request(
        namespace: UUID = namespaceId,
        token: String = "secret-token-value-with-sufficient-length",
    ) = FactoryStepResultBindingRequest(
        namespaceId = namespace,
        agentName = "Worker",
        attemptId = "attempt",
        runtimeId = "runtime",
        capabilityToken = token,
        expiresAt = Instant.now().plusSeconds(60),
    )

    "rejects an absent or wrong secret" {
        controller().bind(caseId, null, request()) shouldBe FactoryBindingOutcome.Unauthorized
        controller().bind(caseId, "wrong", request()) shouldBe FactoryBindingOutcome.Unauthorized
        FactoryStepResultBindingController(FactoryStepResultBindingRegistry(), { namespaceId }, "").bind(caseId, secret, request()) shouldBe FactoryBindingOutcome.Unauthorized
    }

    "rejects an unknown case" {
        controller().bind(UUID.randomUUID(), secret, request()) shouldBe FactoryBindingOutcome.NotFound
    }

    "rejects a namespace mismatch or blank identity" {
        controller().bind(caseId, secret, request(namespace = UUID.randomUUID())) shouldBe FactoryBindingOutcome.Conflict
        controller().bind(caseId, secret, request().copy(agentName = "")) shouldBe FactoryBindingOutcome.Conflict
    }

    "rejects a token outside the accepted length bounds" {
        controller().bind(caseId, secret, request(token = "short")) shouldBe FactoryBindingOutcome.Conflict
    }

    "binds a valid request exactly once" {
        val registry = FactoryStepResultBindingRegistry()
        val controller = controller(registry)
        controller.bind(caseId, secret, request()) shouldBe FactoryBindingOutcome.Bound
        registry.contains(caseId) shouldBe true
        controller.bind(caseId, secret, request()) shouldBe FactoryBindingOutcome.Conflict
    }
})
