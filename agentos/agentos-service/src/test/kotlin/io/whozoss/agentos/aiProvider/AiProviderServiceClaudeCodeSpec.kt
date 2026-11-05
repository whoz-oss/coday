package io.whozoss.agentos.aiProvider

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import io.whozoss.agentos.claudeCode.ClaudeCodeProperties
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.user.UserService
import java.util.UUID

class AiProviderServiceClaudeCodeSpec :
    StringSpec({
        fun service(enabled: Boolean = true) =
            AiProviderServiceImpl(
                InMemoryAiProviderRepository(),
                AiProviderMergeStrategy(),
                mockk<PermissionService>(relaxed = true),
                mockk<UserService>(relaxed = true),
                ClaudeCodeProperties(enabled = enabled),
            )

        fun provider(
            namespaceId: UUID? = UUID.randomUUID(),
            baseUrl: String? = null,
            apiKey: String? = null,
        ) = AiProvider(namespaceId = namespaceId, name = "claude-code", apiType = AiApiType.ClaudeCode, baseUrl = baseUrl, apiKey = apiKey)

        "a Claude Code provider is created at any scope: each user runs on their own login" {
            service().create(provider()).shouldNotBeNull()
            service().create(provider(namespaceId = null)).shouldNotBeNull()
        }

        "a Claude Code provider is refused while the runtime is disabled" {
            shouldThrow<BadRequestException> { service(enabled = false).create(provider()) }
                .message shouldContain "agentos.claude-code.enabled"
        }

        "a Claude Code provider takes neither a base URL nor an API key" {
            shouldThrow<BadRequestException> { service().create(provider(baseUrl = "https://api.anthropic.com")) }
            shouldThrow<BadRequestException> { service().create(provider(apiKey = "sk-ant-oat01-token")) }
                .message shouldContain "signs in to the claude CLI"
        }

        "an update cannot add a credential either" {
            val service = service()
            val saved = service.create(provider())

            shouldThrow<BadRequestException> { service.update(saved.copy(apiKey = "sk-ant-oat01-token")) }
        }

        "other providers are not concerned by the flag" {
            service(enabled = false)
                .create(AiProvider(namespaceId = UUID.randomUUID(), name = "anthropic", apiType = AiApiType.Anthropic, apiKey = "sk"))
                .shouldNotBeNull()
        }
    })
