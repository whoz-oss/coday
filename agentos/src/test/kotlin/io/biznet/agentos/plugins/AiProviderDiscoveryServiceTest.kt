package io.biznet.agentos.plugins

import io.biznet.agentos.api.aiprovider.AiProvider
import io.biznet.agentos.api.aiprovider.AiProviderPlugin
import io.biznet.agentos.api.aiprovider.ApiType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.pf4j.PluginManager

class AiProviderDiscoveryServiceTest {

    private lateinit var pluginManager: PluginManager
    private lateinit var discoveryService: AiProviderDiscoveryService

    @BeforeEach
    fun setup() {
        pluginManager = mockk()
        discoveryService = AiProviderDiscoveryService(pluginManager)
    }

    @Test
    fun `should discover providers from loaded plugins`() {
        // Given
        val provider1 = createAiProvider("p1", ApiType.OpenAI)
        val provider2 = createAiProvider("p2", ApiType.Anthropic)

        val mockPlugin = mockk<AiProviderPlugin>()
        every { mockPlugin.getPluginId() } returns "test-plugin"
        every { mockPlugin.getAiProviders() } returns listOf(provider1, provider2)

        every { pluginManager.getExtensions(AiProviderPlugin::class.java) } returns listOf(mockPlugin)

        // When
        val result = discoveryService.discoverAiProviders()

        // Then
        assertEquals(2, result.size)
        assertTrue(result.contains(provider1))
        assertTrue(result.contains(provider2))
        verify(exactly = 1) { mockPlugin.getAiProviders() }
    }

    @Test
    fun `should handle plugins that throw exceptions`() {
        // Given
        val goodPlugin = mockk<AiProviderPlugin>()
        val provider = createAiProvider("p1", ApiType.OpenAI)
        every { goodPlugin.getPluginId() } returns "good-plugin"
        every { goodPlugin.getAiProviders() } returns listOf(provider)

        val badPlugin = mockk<AiProviderPlugin>()
        every { badPlugin.getPluginId() } returns "bad-plugin"
        every { badPlugin.getAiProviders() } throws RuntimeException("Plugin crashed")

        every { pluginManager.getExtensions(AiProviderPlugin::class.java) } returns listOf(badPlugin, goodPlugin)

        // When
        val result = discoveryService.discoverAiProviders()

        // Then
        assertEquals(1, result.size)
        assertEquals(provider, result[0])
    }

    @Test
    fun `should return empty list when no extensions found`() {
        // Given
        every { pluginManager.getExtensions(AiProviderPlugin::class.java) } returns emptyList()

        // When
        val result = discoveryService.discoverAiProviders()

        // Then
        assertTrue(result.isEmpty())
    }

    // Helper to create dummy data objects since we don't have the constructor in the prompt
    private fun createAiProvider(id: String, type: ApiType): AiProvider {
        val provider = mockk<AiProvider>()
        every { provider.id } returns id
        every { provider.apiType } returns type
        return provider
    }
}