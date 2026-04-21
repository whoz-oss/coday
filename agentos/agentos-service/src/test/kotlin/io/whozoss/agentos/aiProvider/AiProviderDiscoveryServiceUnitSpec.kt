package io.whozoss.agentos.aiProvider

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.AiProviderPlugin
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.pf4j.PluginManager
import java.util.UUID

class AiProviderDiscoveryServiceUnitSpec : StringSpec({

    "discoverAiProviders should discover providers from loaded plugins" {
        val pluginManager = mockk<PluginManager>()
        val discoveryService = AiProviderDiscoveryService(pluginManager)
        val provider1 = createAiProvider(UUID.randomUUID(), AiApiType.OpenAI)
        val provider2 = createAiProvider(UUID.randomUUID(), AiApiType.Anthropic)
        val mockPlugin = mockk<AiProviderPlugin>()
        every { mockPlugin.getPluginId() } returns "test-plugin"
        every { mockPlugin.getAiProviders() } returns listOf(provider1, provider2)
        every { pluginManager.getExtensions(AiProviderPlugin::class.java) } returns listOf(mockPlugin)

        val result = discoveryService.discoverAiProviders()

        result shouldHaveSize 2
        result shouldContain provider1
        result shouldContain provider2
        verify(exactly = 1) { mockPlugin.getAiProviders() }
    }

    "discoverAiProviders should handle plugins that throw exceptions" {
        val pluginManager = mockk<PluginManager>()
        val discoveryService = AiProviderDiscoveryService(pluginManager)
        val goodPlugin = mockk<AiProviderPlugin>()
        val provider = createAiProvider(UUID.randomUUID(), AiApiType.OpenAI)
        every { goodPlugin.getPluginId() } returns "good-plugin"
        every { goodPlugin.getAiProviders() } returns listOf(provider)
        val badPlugin = mockk<AiProviderPlugin>()
        every { badPlugin.getPluginId() } returns "bad-plugin"
        every { badPlugin.getAiProviders() } throws RuntimeException("Plugin crashed")
        every { pluginManager.getExtensions(AiProviderPlugin::class.java) } returns listOf(badPlugin, goodPlugin)

        val result = discoveryService.discoverAiProviders()

        result shouldHaveSize 1
        result[0] shouldBe provider
    }

    "discoverAiProviders should return empty list when no extensions found" {
        val pluginManager = mockk<PluginManager>()
        val discoveryService = AiProviderDiscoveryService(pluginManager)
        every { pluginManager.getExtensions(AiProviderPlugin::class.java) } returns emptyList()

        val result = discoveryService.discoverAiProviders()

        result.shouldBeEmpty()
    }
})

private fun createAiProvider(id: UUID, type: AiApiType): AiProvider =
    AiProvider(
        metadata = EntityMetadata(id = id),
        name = "provider-$id",
        apiType = type,
        baseUrl = "https://api.example.com",
    )
