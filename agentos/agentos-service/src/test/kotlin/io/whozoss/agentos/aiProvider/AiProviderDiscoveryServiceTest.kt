package io.whozoss.agentos.aiProvider

import io.kotest.core.spec.style.DescribeSpec
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
import org.pf4j.PluginManager
import java.util.UUID

class AiProviderDiscoveryServiceTest :
    DescribeSpec({

        describe("AiProviderDiscoveryService") {

            lateinit var pluginManager: PluginManager
            lateinit var discoveryService: AiProviderDiscoveryService

            beforeEach {
                pluginManager = mockk()
                discoveryService = AiProviderDiscoveryService(pluginManager)
            }

            describe("discoverAiProviders") {

                it("should discover providers from loaded plugins") {
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

                it("should handle plugins that throw exceptions") {
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

                it("should return empty list when no extensions found") {
                    every { pluginManager.getExtensions(AiProviderPlugin::class.java) } returns emptyList()

                    val result = discoveryService.discoverAiProviders()

                    result.shouldBeEmpty()
                }
            }
        }
    })

private fun createAiProvider(
    id: UUID,
    type: AiApiType,
): AiProvider {
    val provider = mockk<AiProvider>()
    every { provider.metadata.id } returns id
    every { provider.apiType } returns type
    return provider
}
