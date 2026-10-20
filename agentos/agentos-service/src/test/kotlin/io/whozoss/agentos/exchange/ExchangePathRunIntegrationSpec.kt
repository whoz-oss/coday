package io.whozoss.agentos.exchange

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agent.AgentExecutionContext
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [ExchangePathRunIntegration]: which tokens are resolved, who may resolve each one,
 * what a run pays when none is used, and what happens to a token that cannot be resolved.
 *
 * That last one is the contract worth pinning: an unresolvable token removes its integration from
 * the run. Leaving the token in the parameters only fails closed on BASH, the one plugin that
 * requires an absolute path. TMUX discards the failure of `new-session -c` and sends the command to
 * whatever session already holds the name, and FILE_ACCESS reads the literal as a *relative* path
 * and roots its tools under the service's working directory, both of them silently.
 */
class ExchangePathRunIntegrationSpec : StringSpec() {
    private val exchangeRootResolver: ExchangeRootResolver = mockk()
    private val exchangeStorageService: ExchangeStorageService = mockk()
    private val exchangeCapabilityService: ExchangeCapabilityService = mockk()
    private val customizer =
        ExchangePathRunIntegration(exchangeRootResolver, exchangeStorageService, exchangeCapabilityService)

    private val namespaceId = UUID.randomUUID()
    private val caseId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
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

    /** A family's shared directory: owned by the root case, not by the case being run. */
    private fun givenSharedCaseRoot(ownerCaseId: UUID) {
        every { exchangeRootResolver.resolve(caseId, namespaceId, caseCreatedAt) } returns
            ResolvedExchangeRoot(path = casePath, ownerCaseId = ownerCaseId, unavailableReason = null)
    }

    private fun givenNamespaceWrite(allowed: Boolean = true) {
        every {
            exchangeCapabilityService.canWrite(userId.toString(), EntityType.NAMESPACE, namespaceId.toString())
        } returns allowed
        if (allowed) {
            every { exchangeStorageService.namespaceRoot(namespaceId) } returns namespacePath
        }
    }

    init {
        "the case token becomes the case exchange directory" {
            givenCaseRoot()
            val configs = listOf(config("build", """{"workingDirectory": "{{CASE_EXCHANGE}}/repo"}"""))

            val result = customizer.customize(configs, context)

            result.single().parameters.text("workingDirectory") shouldBe "$casePath/repo"
        }

        "the namespace token becomes the shared directory for a user who holds Namespace WRITE" {
            givenNamespaceWrite()
            val configs = listOf(config("index", """{"workingDirectory": "{{NAMESPACE_EXCHANGE}}"}"""))

            val result = customizer.customize(configs, context.copy(userId = userId))

            result.single().parameters.text("workingDirectory") shouldBe namespacePath.toString()
        }

        "the namespace token is refused to a user without Namespace WRITE" {
            // A shell or an MCP server rooted at that directory writes, and nothing can make it
            // read-only. Namespace READ only gets the namespace file tools granted, read-only
            // without WRITE, so resolving this token on READ would be the way around both them and
            // the REST namespace-file endpoints.
            givenNamespaceWrite(allowed = false)
            val configs = listOf(config("index", """{"workingDirectory": "{{NAMESPACE_EXCHANGE}}"}"""))

            customizer.customize(configs, context.copy(userId = userId)).shouldBeEmpty()
            verify(exactly = 0) { exchangeStorageService.namespaceRoot(any()) }
        }

        "the namespace token is denied to a run with no identified user" {
            val configs = listOf(config("index", """{"workingDirectory": "{{NAMESPACE_EXCHANGE}}"}"""))

            customizer.customize(configs, context).shouldBeEmpty()
            verify(exactly = 0) { exchangeCapabilityService.canWrite(any(), any(), any()) }
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

        "an integration whose token cannot be resolved is removed from the run" {
            // A definition preview resolves no case. The integration is dropped rather than handed
            // its token verbatim: see the class note on what each plugin does with the literal.
            val configs =
                listOf(
                    config("build", """{"workingDirectory": "{{CASE_EXCHANGE}}/repo"}"""),
                    config("datetime", null),
                )

            val result = customizer.customize(configs, AgentExecutionContext(namespaceId = namespaceId))

            result shouldHaveSize 1
            result.single().name shouldBe "datetime"
            verify(exactly = 0) { exchangeRootResolver.resolve(any(), any(), any()) }
        }

        "an unresolvable token removes its own integration and no other" {
            givenCaseRoot()
            givenNamespaceWrite()
            val configs =
                listOf(
                    config("shared", """{"workingDirectory": "{{NAMESPACE_EXCHANGE}}"}"""),
                    config("build", """{"workingDirectory": "{{CASE_EXCHANGE}}/repo"}"""),
                )

            // No live case: the case token cannot be resolved, the namespace one can.
            val result =
                customizer.customize(configs, AgentExecutionContext(namespaceId = namespaceId, userId = userId))

            result.single().name shouldBe "shared"
            result.single().parameters.text("workingDirectory") shouldBe namespacePath.toString()
        }

        "an unusable workspace removes the integration instead of failing every agent of the namespace" {
            // Deliberately not a ConflictException: this customizer sees every effective integration
            // config of the namespace, not the ones the agent declares, so throwing would abort the
            // resolution of every agent there, a chat agent referencing no integration included.
            // Dropping the integration protects the half-prepared worktree just as well.
            givenCaseRoot(usable = false)
            val configs =
                listOf(config("build", """{"workingDirectory": "{{CASE_EXCHANGE}}/repo"}"""), config("datetime", null))

            val result = customizer.customize(configs, context)

            result.single().name shouldBe "datetime"
        }

        "a directory the case owns is resolved without a permission query" {
            // The run's own directory needs no gate: the invoking user already holds Case WRITE on
            // that case to have reached this point, which is the rule buildExchangeTools applies to
            // the file tools rooted at the very same directory. The strict mock is the assertion.
            givenCaseRoot()
            val configs = listOf(config("build", """{"workingDirectory": "{{CASE_EXCHANGE}}"}"""))

            customizer.customize(configs, context.copy(userId = userId))

            verify(exactly = 0) { exchangeCapabilityService.canAccessCase(any(), any(), any(), any()) }
        }

        "a family's shared directory is resolved only for a user who may write the owning case" {
            givenSharedCaseRoot(UUID.randomUUID())
            every {
                exchangeCapabilityService.canAccessCase(userId.toString(), caseId, any(), Action.WRITE)
            } returns true
            val configs = listOf(config("build", """{"workingDirectory": "{{CASE_EXCHANGE}}/repo"}"""))

            val result = customizer.customize(configs, context.copy(userId = userId))

            result.single().parameters.text("workingDirectory") shouldBe "$casePath/repo"
        }

        "a family's shared directory is refused to a user who may not write the owning case" {
            // Delegation does not confer its rights: a sub-case of a family whose root case the user
            // cannot reach must not get a shell pointed at the shared workspace, which is what
            // GitToolsRunIntegration refuses before handing over the same worktree.
            givenSharedCaseRoot(UUID.randomUUID())
            every {
                exchangeCapabilityService.canAccessCase(userId.toString(), caseId, any(), Action.WRITE)
            } returns false
            val configs = listOf(config("build", """{"workingDirectory": "{{CASE_EXCHANGE}}/repo"}"""))

            customizer.customize(configs, context.copy(userId = userId)).shouldBeEmpty()
        }

        "a family's shared directory is denied to a run with no identified user" {
            // Fail-closed, as canAccessCase is for a null user everywhere else.
            givenSharedCaseRoot(UUID.randomUUID())
            every { exchangeCapabilityService.canAccessCase(null, caseId, any(), Action.WRITE) } returns false
            val configs = listOf(config("build", """{"workingDirectory": "{{CASE_EXCHANGE}}"}"""))

            customizer.customize(configs, context).shouldBeEmpty()
        }

        "configs that carry no parameters are passed through untouched" {
            givenNamespaceWrite()
            val withToken = config("index", """{"workingDirectory": "{{NAMESPACE_EXCHANGE}}"}""")
            val withoutParameters = config("datetime", null)

            val result = customizer.customize(listOf(withToken, withoutParameters), context.copy(userId = userId))

            result[1] shouldBe withoutParameters
        }
    }
}
