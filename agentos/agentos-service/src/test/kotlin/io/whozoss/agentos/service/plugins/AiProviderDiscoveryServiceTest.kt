package io.whozoss.agentos.service.plugins

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.aiprovider.AiProvider
import io.whozoss.agentos.sdk.aiprovider.AiProviderPlugin
import io.whozoss.agentos.sdk.aiprovider.ApiType
import org.pf4j.PluginManager

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
                    val provider1 = createAiProvider("p1", ApiType.OpenAI)
                    val provider2 = createAiProvider("p2", ApiType.Anthropic)

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
                    val provider = createAiProvider("p1", ApiType.OpenAI)
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
    id: String,
    type: ApiType,
): AiProvider {
    val provider = mockk<AiProvider>()
    every { provider.id } returns id
    every { provider.apiType } returns type
    return provider
}
