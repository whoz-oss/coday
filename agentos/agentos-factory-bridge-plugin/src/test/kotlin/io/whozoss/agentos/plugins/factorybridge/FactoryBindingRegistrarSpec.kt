package io.whozoss.agentos.plugins.factorybridge

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/**
 * Host transport wiring: attributes extracted by the AgentOS host from the `X-External-Context-*`
 * headers (and the shared credential) are turned into a durable binding that the
 * [FactoryExternalExecutionContextProvider] can expose — no longer an empty `{}`.
 */
class FactoryBindingRegistrarSpec : StringSpec({
    val token = "secret-token-value-with-sufficient-length"

    fun registrar(
        services: FactoryBridgeServices,
    ) = FactoryBindingRegistrar { services }

    fun attributes(
        attemptId: String = "attempt",
        runtimeId: String = "runtime",
        agentName: String? = "Worker",
    ) = buildMap {
        put(FactoryBindingRegistrar.ATTRIBUTE_ATTEMPT_ID, attemptId)
        put(FactoryBindingRegistrar.ATTRIBUTE_CAPABILITY_TOKEN, token)
        put(FactoryBindingRegistrar.ATTRIBUTE_RUNTIME_ID, runtimeId)
        agentName?.let { put(FactoryBindingRegistrar.ATTRIBUTE_AGENT_NAME, it) }
    }

    "a case bound from X-External-Context-* attributes is visible through the execution context provider" {
        val caseId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()
        val services = FactoryTestFixtures.services(secret = "shared-secret")
        val provider = FactoryExternalExecutionContextProvider { services }

        provider.provideExecutionContext(caseId, namespaceId, null) shouldBe emptyMap()

        registrar(services).register(caseId, namespaceId, "shared-secret", attributes(), Instant.now().plusSeconds(60)) shouldBe true

        val context = provider.provideExecutionContext(caseId, namespaceId, null)
        context["capabilityToken"] shouldBe token
        context["attemptId"] shouldBe "attempt"
        context["runtimeId"] shouldBe "runtime"
    }

    "a wrong or absent credential is rejected fail-closed and binds nothing" {
        val caseId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()
        val services = FactoryTestFixtures.services(secret = "shared-secret")

        registrar(services).register(caseId, namespaceId, "wrong", attributes(), Instant.now().plusSeconds(60)) shouldBe false
        registrar(services).register(caseId, namespaceId, null, attributes(), Instant.now().plusSeconds(60)) shouldBe false
        services.stepResultBindings.contains(caseId) shouldBe false
    }

    "a blank capability token or attempt id is rejected" {
        val caseId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()
        val services = FactoryTestFixtures.services(secret = "shared-secret")

        registrar(services).register(
            caseId,
            namespaceId,
            "shared-secret",
            mapOf(FactoryBindingRegistrar.ATTRIBUTE_CAPABILITY_TOKEN to token),
            Instant.now().plusSeconds(60),
        ) shouldBe false
        registrar(services).register(
            caseId,
            namespaceId,
            "shared-secret",
            mapOf(
                FactoryBindingRegistrar.ATTRIBUTE_ATTEMPT_ID to "attempt",
                FactoryBindingRegistrar.ATTRIBUTE_CAPABILITY_TOKEN to "short",
            ),
            Instant.now().plusSeconds(60),
        ) shouldBe false
        services.stepResultBindings.contains(caseId) shouldBe false
    }

    "an agent-less binding is redeemable by the case agent through the wildcard" {
        val caseId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()
        val services = FactoryTestFixtures.services(secret = "shared-secret")
        val registrar = registrar(services)

        registrar.register(caseId, namespaceId, "shared-secret", attributes(agentName = null), Instant.now().plusSeconds(60)) shouldBe true

        val acquired = services.stepResultBindings.acquire(caseId, namespaceId, "ProductEngineer")
        acquired.shouldNotBeNull()
        acquired.attemptId shouldBe "attempt"
        acquired.agentName shouldBe FACTORY_AGENT_WILDCARD
    }
})
