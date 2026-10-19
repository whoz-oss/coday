package io.whozoss.agentos.exchange

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agent.AgentExecutionContext
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [ExchangePathRunIntegration]: which tokens are resolved, what a run pays when none
 * is used, and what happens to a token that cannot be resolved.
 */
class ExchangePathRunIntegrationSpec : StringSpec() {
    private val exchangeRootResolver: ExchangeRootResolver = mockk()
    private val exchangeStorageService: ExchangeStorageService = mockk()
    private val customizer = ExchangePathRunIntegration(exchangeRootResolver, exchangeStorageService)

    private val namespaceId = UUID.randomUUID()
    private val caseId = UUID.randomUUID()
    private val caseCreatedAt: Instant = Instant.parse("2026-10-19T10:14:00Z")
    private val context =
        AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, caseCreatedAt = caseCreatedAt)

    private val casePath = Path.of("/srv/exchange/ns/cases/2026/10/19/case")
    private val namespacePath = Path.of("/srv/exchange/ns/shared")

    private fun config(
        name: String,
        parameters: String?,
    ) = IntegrationConfig(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        namespaceId = namespaceId,
        name = name,
        integrationType = "BASH",
        parameters = parameters?.let { jacksonObjectMapper().readTree(it) },
    )

    private fun JsonNode?.text(field: String): String? = this?.get(field)?.asText()

    private fun givenCaseRoot(usable: Boolean = true) {
        every { exchangeRootResolver.resolve(caseId, namespaceId, caseCreatedAt) } returns
            ResolvedExchangeRoot(
                path = casePath,
                ownerCaseId = caseId,
                unavailableReason = if (usable) null else "The workspace for this case is still being prepared.",
            )
    }

    init {
        "the case token becomes the case exchange directory" {
            givenCaseRoot()
            val configs = listOf(config("build", """{"workingDirectory": "{{CASE_EXCHANGE}}/repo"}"""))

            val result = customizer.customize(configs, context)

            result.single().parameters.text("workingDirectory") shouldBe "$casePath/repo"
        }

        "the namespace token becomes the shared directory" {
            every { exchangeStorageService.namespaceRoot(namespaceId) } returns namespacePath
            val configs = listOf(config("index", """{"workingDirectory": "{{NAMESPACE_EXCHANGE}}"}"""))

            val result = customizer.customize(configs, context)

            result.single().parameters.text("workingDirectory") shouldBe namespacePath.toString()
        }

        "tokens are substituted anywhere in the tree, not only at the top level" {
            // Every consumer shape at once: a nested object, an array element, and a token used
            // mid-string rather than as a whole value.
            givenCaseRoot()
            val configs =
                listOf(
                    config(
                        "nested",
                        """
                        {
                          "workingDirectory": "{{CASE_EXCHANGE}}",
                          "tools": [
                            { "name": "test", "command": "cd {{CASE_EXCHANGE}}/repo && pnpm test" }
                          ]
                        }
                        """.trimIndent(),
                    ),
                )

            val result = customizer.customize(configs, context).single().parameters

            result.text("workingDirectory") shouldBe casePath.toString()
            result?.get("tools")?.get(0)?.get("command")?.asText() shouldBe "cd $casePath/repo && pnpm test"
        }

        "a run whose integrations carry no token resolves nothing and returns the configs untouched" {
            // The guard that matters: resolving the case token costs the resolver a read of the case
            // and of its family binding, and most runs use no token at all. Strict mocks would throw
            // if either collaborator were called.
            val configs = listOf(config("plain", """{"workingDirectory": "/opt/tooling"}"""), config("none", null))

            val result = customizer.customize(configs, context)

            result shouldBe configs
            verify(exactly = 0) { exchangeRootResolver.resolve(any(), any(), any()) }
            verify(exactly = 0) { exchangeStorageService.namespaceRoot(any()) }
        }

        "only the tokens actually used are resolved" {
            givenCaseRoot()
            val configs = listOf(config("build", """{"workingDirectory": "{{CASE_EXCHANGE}}"}"""))

            customizer.customize(configs, context)

            verify(exactly = 0) { exchangeStorageService.namespaceRoot(any()) }
        }

        "the case token is left verbatim when the resolution has no live case" {
            // A definition preview resolves no case. Leaving the token in place makes the plugin
            // reject a non-absolute path and provide no tool, which is the point: falling back to
            // another directory would run the work where nobody looks for it.
            val configs = listOf(config("build", """{"workingDirectory": "{{CASE_EXCHANGE}}/repo"}"""))

            val result = customizer.customize(configs, AgentExecutionContext(namespaceId = namespaceId))

            result.single().parameters.text("workingDirectory") shouldBe "{{CASE_EXCHANGE}}/repo"
            verify(exactly = 0) { exchangeRootResolver.resolve(any(), any(), any()) }
        }

        "an unusable workspace fails the run instead of pointing commands at it" {
            givenCaseRoot(usable = false)
            val configs = listOf(config("build", """{"workingDirectory": "{{CASE_EXCHANGE}}/repo"}"""))

            shouldThrow<ConflictException> { customizer.customize(configs, context) }
        }

        "configs that carry no parameters are passed through untouched" {
            every { exchangeStorageService.namespaceRoot(namespaceId) } returns namespacePath
            val withToken = config("index", """{"workingDirectory": "{{NAMESPACE_EXCHANGE}}"}""")
            val withoutParameters = config("datetime", null)

            val result = customizer.customize(listOf(withToken, withoutParameters), context)

            result[1] shouldBe withoutParameters
        }
    }
}
