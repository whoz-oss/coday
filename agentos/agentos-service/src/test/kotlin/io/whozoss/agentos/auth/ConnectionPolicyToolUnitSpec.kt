package io.whozoss.agentos.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.authSetting.*
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import io.whozoss.agentos.tool.ToolRegistryService
import io.whozoss.agentos.tool.ToolResolverService
import java.util.UUID

class ConnectionPolicyToolUnitSpec : StringSpec({
    listOf("HTTP_API", "MCP_HTTP", "MCP_STDIO", "BASH").forEach { type ->
    "shadow $type is denied before factory and plugin initialization including direct resolution" {
        val setting = OAuthRegisteredAuthSetting(name = "GOOGLE", authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenUrl = "https://oauth2.googleapis.com/token", scopes = "https://www.googleapis.com/auth/drive.readonly")
        val policy = ConnectionPolicy(allowedAuthTypes = setOf(AuthType.OAUTH_REGISTERED),
            authorizationUrls = setOf(setting.authorizationUrl), tokenUrls = setOf(setting.tokenUrl),
            allowedScopes = setOf(setting.scopes!!), allowedIntegrationTypes = setOf("GOOGLE_WORKSPACE"))
        val authSettings = mockk<AuthSettingService>()
        every { authSettings.resolveAuthSetting(any(), any(), "GOOGLE") } returns setting
        val plugins = mockk<ToolRegistryService>()
        var initialized = 0
        val plugin = mockk<ToolPlugin>()
        every { plugin.provideTools(any(), any(), any()) } answers { initialized++; emptyList() }
        every { plugins.findPlugin(type) } returns plugin
        val resolver = ToolResolverService(plugins, ConnectionPolicyRegistry(ConnectionPolicyProperties(policies = mapOf("google" to policy))), authSettings)
        var supplied = 0
        val config = IntegrationConfig(name = "alias", integrationType = type, authSettingName = "GOOGLE")
        val result = resolver.resolveToolsForRun(mapOf("alias" to null), ToolContext(UUID.randomUUID(), UUID.randomUUID(), null, emptyList()),
            listOf(config), { supplied++; { null } })
        result.failedIntegrations shouldBe listOf("alias")
        initialized shouldBe 0
        supplied shouldBe 0
    }
    }
    "active governed tools cannot shed their policy by changing both endpoints" {
        val user = UUID.randomUUID()
        val context = ToolContext(UUID.randomUUID(), user, null, emptyList())
        val original = OAuthRegisteredAuthSetting(name = "READ", authorizationUrl = "https://auth.example.test/auth", tokenUrl = "https://auth.example.test/token", scopes = "read")
        var setting = original
        val settings = mockk<AuthSettingService>()
        every { settings.resolveAuthSetting(any(), any(), "READ") } answers { setting }
        val registry = ConnectionPolicyRegistry(ConnectionPolicyProperties(policies = mapOf("read" to ConnectionPolicy(allowedAuthTypes = setOf(AuthType.OAUTH_REGISTERED), authorizationUrls = setOf(original.authorizationUrl), tokenUrls = setOf(original.tokenUrl), allowedScopes = setOf("read"), allowedIntegrationTypes = setOf("HTTP_API")))))
        val plugins = mockk<ToolRegistryService>()
        val plugin = mockk<ToolPlugin>()
        var executions = 0
        val tool = object : io.whozoss.agentos.sdk.tool.StandardTool<Unit> {
            override val name = "alias__read"
            override val description = "synthetic"
            override val inputSchema = "{}"
            override val version = "1"
            override val paramType = Unit::class.java
            override suspend fun execute(input: Unit?, context: ToolContext): io.whozoss.agentos.sdk.tool.ToolExecutionResult { executions++; return io.whozoss.agentos.sdk.tool.ToolExecutionResult(output = "ok", success = true) }
        }
        every { plugin.provideTools(any(), any(), any()) } returns listOf(tool)
        every { plugins.findPlugin("HTTP_API") } returns plugin
        val resolved = ToolResolverService(plugins, registry, settings).resolveToolsForRun(mapOf("alias" to null), context, listOf(IntegrationConfig(name = "alias", integrationType = "HTTP_API", authSettingName = "READ"))).tools.single()
        setting = original.copy(authorizationUrl = "https://attacker.example.test/auth", tokenUrl = "https://attacker.example.test/token")
        resolved.executeWithJson(null, context).errorType shouldBe "CONNECTION_POLICY_DENIED"
        executions shouldBe 0
    }
})
