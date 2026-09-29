package io.whozoss.agentos.tool

import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exchange.ExchangeToolsConfigProperties
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.integrationConfig.IntegrationTypeRegistry
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.sdk.tool.ToolPlugin
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.pf4j.PluginManager
import java.util.UUID

class ToolInvokeServiceSpec :
    StringSpec({

        // -------------------------------------------------------------------------
        // Shared fixtures
        // -------------------------------------------------------------------------

        val namespaceId: UUID = UUID.randomUUID()
        val userId: UUID = UUID.randomUUID()
        val userExternalId = "alice@example.com"

        fun makeTool(
            name: String,
            result: ToolExecutionResult = ToolExecutionResult.success("ok"),
            captureContext: ((ToolContext) -> Unit)? = null,
        ): StandardTool<Nothing> =
            object : StandardTool<Nothing> {
                override val name = name
                override val description = name
                override val inputSchema = """{"type":"object"}"""
                override val version = "1.0.0"
                override val paramType: Class<Nothing>? = null

                override suspend fun execute(
                    input: Nothing?,
                    context: ToolContext,
                ): ToolExecutionResult = result

                override suspend fun executeWithJson(
                    json: String?,
                    context: ToolContext,
                ): ToolExecutionResult {
                    captureContext?.invoke(context)
                    return result
                }
            }

        fun makePlugin(
            integrationType: String,
            vararg tools: StandardTool<*>,
        ): ToolPlugin =
            object : ToolPlugin {
                override val integrationType = integrationType
                override val configSchema: JsonNode? = null

                override fun provideTools(
                    config: JsonNode?,
                    configName: String?,
                    context: ToolContext?,
                ): List<StandardTool<*>> = tools.toList()
            }

        fun integrationConfig(
            name: String,
            integrationType: String,
            nsId: UUID = namespaceId,
        ) = IntegrationConfig(
            metadata = EntityMetadata(),
            namespaceId = nsId,
            name = name,
            integrationType = integrationType,
        )

        fun buildResolver(plugins: List<ToolPlugin>): ToolResolverService {
            val pluginManager = mockk<PluginManager>(relaxed = true)
            every { pluginManager.getExtensions(ToolPlugin::class.java) } returns plugins
            every { pluginManager.whichPlugin(any()) } returns null
            val registry = ToolRegistryService(
                pluginManager,
                mockk<IntegrationTypeRegistry>(relaxed = true),
                ExchangeToolsConfigProperties(),
            )
            registry.initialize()
            return ToolResolverService(registry)
        }

        fun buildService(
            configs: List<IntegrationConfig>,
            plugins: List<ToolPlugin>,
            user: User? = null,
        ): ToolInvokeService {
            val integrationConfigService = mockk<IntegrationConfigService> {
                every { findEffective(any(), any()) } returns configs
            }
            val userService = mockk<UserService> {
                every { findById(any()) } returns user
            }
            return ToolInvokeService(
                integrationConfigService = integrationConfigService,
                toolResolverService = buildResolver(plugins),
                userService = userService,
            )
        }

        // -------------------------------------------------------------------------
        // Happy path
        // -------------------------------------------------------------------------

        "invoke returns the tool result when the tool is found" {
            val expectedOutput = "hello from tool"
            val tool = makeTool("MY_INT__doSomething", ToolExecutionResult.success(expectedOutput))
            val service = buildService(
                configs = listOf(integrationConfig("MY_INT", "SOME_TYPE")),
                plugins = listOf(makePlugin("SOME_TYPE", tool)),
            )

            val result = service.invoke(
                namespaceId = namespaceId,
                userId = null,
                toolName = "MY_INT__doSomething",
                payloadJson = null,
            )

            result.success shouldBe true
            result.output shouldBe expectedOutput
        }

        "invoke forwards the payload and context to the tool" {
            var capturedContext: ToolContext? = null
            val tool = makeTool(
                name = "MY_INT__doSomething",
                captureContext = { capturedContext = it },
            )
            val user = User(
                metadata = EntityMetadata(id = userId),
                externalId = userExternalId,
                email = userExternalId,
            )
            val service = buildService(
                configs = listOf(integrationConfig("MY_INT", "SOME_TYPE")),
                plugins = listOf(makePlugin("SOME_TYPE", tool)),
                user = user,
            )

            service.invoke(
                namespaceId = namespaceId,
                userId = userId,
                toolName = "MY_INT__doSomething",
                payloadJson = """{"key":"value"}""",
            )

            capturedContext!!.namespaceId shouldBe namespaceId
            capturedContext!!.userId shouldBe userId
            capturedContext!!.userExternalId shouldBe userExternalId
            capturedContext!!.caseEvents shouldBe emptyList()
            capturedContext!!.agentName shouldBe null
        }

        "invoke returns a failed ToolExecutionResult when the tool signals an error" {
            val tool = makeTool(
                name = "MY_INT__doSomething",
                result = ToolExecutionResult.error("something went wrong", errorType = "BOOM"),
            )
            val service = buildService(
                configs = listOf(integrationConfig("MY_INT", "SOME_TYPE")),
                plugins = listOf(makePlugin("SOME_TYPE", tool)),
            )

            val result = service.invoke(
                namespaceId = namespaceId,
                userId = null,
                toolName = "MY_INT__doSomething",
                payloadJson = null,
            )

            result.success shouldBe false
            result.output shouldBe "something went wrong"
            result.errorType shouldBe "BOOM"
        }

        // -------------------------------------------------------------------------
        // Tool not found
        // -------------------------------------------------------------------------

        "invoke throws ResourceNotFoundException when the tool name does not match" {
            val tool = makeTool("MY_INT__realTool")
            val service = buildService(
                configs = listOf(integrationConfig("MY_INT", "SOME_TYPE")),
                plugins = listOf(makePlugin("SOME_TYPE", tool)),
            )

            val ex = shouldThrow<ResourceNotFoundException> {
                service.invoke(
                    namespaceId = namespaceId,
                    userId = null,
                    toolName = "MY_INT__nonExistent",
                    payloadJson = null,
                )
            }

            ex.message shouldContain "MY_INT__nonExistent"
            ex.message shouldContain namespaceId.toString()
        }

        "invoke includes available tool names in the not-found error message" {
            val tool = makeTool("MY_INT__realTool")
            val service = buildService(
                configs = listOf(integrationConfig("MY_INT", "SOME_TYPE")),
                plugins = listOf(makePlugin("SOME_TYPE", tool)),
            )

            val ex = shouldThrow<ResourceNotFoundException> {
                service.invoke(
                    namespaceId = namespaceId,
                    userId = null,
                    toolName = "MY_INT__nonExistent",
                    payloadJson = null,
                )
            }

            ex.message shouldContain "MY_INT__realTool"
        }

        "invoke throws ResourceNotFoundException when no integration configs are resolved" {
            val service = buildService(
                configs = emptyList(),
                plugins = emptyList(),
            )

            shouldThrow<ResourceNotFoundException> {
                service.invoke(
                    namespaceId = namespaceId,
                    userId = null,
                    toolName = "ANYTHING__anyTool",
                    payloadJson = null,
                )
            }
        }

        // -------------------------------------------------------------------------
        // userExternalId resolution
        // -------------------------------------------------------------------------

        "invoke resolves userExternalId from UserService when userId is provided" {
            var capturedContext: ToolContext? = null
            val tool = makeTool(
                name = "MY_INT__doSomething",
                captureContext = { capturedContext = it },
            )
            val user = User(
                metadata = EntityMetadata(id = userId),
                externalId = userExternalId,
                email = userExternalId,
            )
            val service = buildService(
                configs = listOf(integrationConfig("MY_INT", "SOME_TYPE")),
                plugins = listOf(makePlugin("SOME_TYPE", tool)),
                user = user,
            )

            service.invoke(
                namespaceId = namespaceId,
                userId = userId,
                toolName = "MY_INT__doSomething",
                payloadJson = null,
            )

            capturedContext!!.userExternalId shouldBe userExternalId
        }

        "invoke sets userExternalId to null when userId is not provided" {
            var capturedContext: ToolContext? = null
            val tool = makeTool(
                name = "MY_INT__doSomething",
                captureContext = { capturedContext = it },
            )
            val service = buildService(
                configs = listOf(integrationConfig("MY_INT", "SOME_TYPE")),
                plugins = listOf(makePlugin("SOME_TYPE", tool)),
            )

            service.invoke(
                namespaceId = namespaceId,
                userId = null,
                toolName = "MY_INT__doSomething",
                payloadJson = null,
            )

            capturedContext!!.userExternalId shouldBe null
        }

        "invoke sets userExternalId to null when UserService cannot resolve the user" {
            var capturedContext: ToolContext? = null
            val tool = makeTool(
                name = "MY_INT__doSomething",
                captureContext = { capturedContext = it },
            )
            // UserService returns null for the given userId (user not found)
            val service = buildService(
                configs = listOf(integrationConfig("MY_INT", "SOME_TYPE")),
                plugins = listOf(makePlugin("SOME_TYPE", tool)),
                user = null,
            )

            service.invoke(
                namespaceId = namespaceId,
                userId = userId,
                toolName = "MY_INT__doSomething",
                payloadJson = null,
            )

            capturedContext!!.userExternalId shouldBe null
        }
    })
