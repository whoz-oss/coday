package io.biznet.agentos.sdk

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class AgentPluginTest : StringSpec({
    
    "AgentInput should be created with correct values" {
        val input = AgentInput(
            message = "Hello",
            context = mapOf("key" to "value"),
            conversationId = "conv-123"
        )
        
        input.message shouldBe "Hello"
        input.context["key"] shouldBe "value"
        input.conversationId shouldBe "conv-123"
    }
    
    "AgentOutput should be created with correct values" {
        val output = AgentOutput(
            message = "Response",
            metadata = mapOf("confidence" to 0.95),
            conversationId = "conv-123"
        )
        
        output.message shouldBe "Response"
        output.metadata["confidence"] shouldBe 0.95
        output.conversationId shouldBe "conv-123"
    }
    
    "AgentMetadata should be created with correct values" {
        val metadata = AgentMetadata(
            name = "test-agent",
            description = "A test agent",
            version = "1.0.0",
            capabilities = listOf("chat", "search")
        )
        
        metadata.name shouldBe "test-agent"
        metadata.description shouldBe "A test agent"
        metadata.version shouldBe "1.0.0"
        metadata.capabilities.size shouldBe 2
    }
    
    "mock AgentPlugin should execute and return correct output" {
        val mockAgent = object : AgentPlugin {
            override suspend fun execute(input: AgentInput): AgentOutput {
                return AgentOutput(
                    message = "Echo: ${input.message}",
                    conversationId = input.conversationId
                )
            }
            
            override fun getMetadata() = AgentMetadata(
                name = "echo-agent",
                description = "Echoes input",
                version = "1.0.0"
            )
        }
        
        val input = AgentInput(message = "test")
        val output = mockAgent.execute(input)
        
        output.message shouldBe "Echo: test"
        
        val metadata = mockAgent.getMetadata()
        metadata.name shouldBe "echo-agent"
    }
})
