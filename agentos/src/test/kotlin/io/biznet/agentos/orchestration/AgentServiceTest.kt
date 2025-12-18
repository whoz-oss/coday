package io.biznet.agentos.orchestration

import io.biznet.agentos.chatclient.ChatClientProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Test for AgentService with hard-coded agent definitions.
 */
class AgentServiceTest {
    @Test
    fun `should find default agent`() {
        // Given
        val mockChatClientProvider = mockk<ChatClientProvider>()
        every { mockChatClientProvider.getChatClient(any()) } returns mockk<ChatClient>()
        val agentService = AgentService(mockChatClientProvider, "anthropic")

        // When
        val defaultAgent = agentService.getDefaultAgent()

        // Then
        assertNotNull(defaultAgent, "Default agent should exist")
        assertEquals("General Purpose Agent", defaultAgent.name)
    }

    @Test
    fun `should find agent by exact name`() {
        // Given
        val mockChatClientProvider = mockk<ChatClientProvider>()
        every { mockChatClientProvider.getChatClient(any()) } returns mockk<ChatClient>()
        val agentService = AgentService(mockChatClientProvider, "anthropic")

        // When
        val agent = agentService.findAgentByName("General Purpose Agent")

        // Then
        assertNotNull(agent)
        assertEquals("General Purpose Agent", agent.name)
    }

    @Test
    fun `should find agent by partial name`() {
        // Given
        val mockChatClientProvider = mockk<ChatClientProvider>()
        every { mockChatClientProvider.getChatClient(any()) } returns mockk<ChatClient>()
        val agentService = AgentService(mockChatClientProvider, "anthropic")

        // When
        val agent = agentService.findAgentByName("General")

        // Then
        assertNotNull(agent)
        assertEquals("General Purpose Agent", agent.name)
    }

    @Test
    fun `should throw exception for unknown agent`() {
        // Given
        val mockChatClientProvider = mockk<ChatClientProvider>()
        every { mockChatClientProvider.getChatClient(any()) } returns mockk<ChatClient>()
        val agentService = AgentService(mockChatClientProvider, "anthropic")

        // When/Then
        try {
            agentService.findAgentByName("NonExistentAgent")
            throw AssertionError("Should have thrown exception")
        } catch (e: IllegalArgumentException) {
            assertEquals("Agent not found: NonExistentAgent", e.message)
        }
    }
}
