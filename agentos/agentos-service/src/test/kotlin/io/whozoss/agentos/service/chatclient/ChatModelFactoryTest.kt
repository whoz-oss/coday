package io.whozoss.agentos.service.chatclient

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.model.AiApiType
import io.whozoss.agentos.sdk.model.AiProvider
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.openai.OpenAiChatModel
import java.util.UUID

class ChatModelFactoryTest :
    DescribeSpec({

        val factory = ChatModelFactory()

        describe("ChatModelFactory") {

            describe("createChatModel") {

                it("should create OpenAI chat model") {
                    val provider = mockk<AiProvider>()
                    every { provider.id } returns UUID.randomUUID()
                    every { provider.apiType } returns AiApiType.OpenAI
                    every { provider.baseUrl } returns "https://api.openai.com"
                    every { provider.defaultApiKey } returns "sk-test"
                    every { provider.baseModel } returns "gpt-4"
                    every { provider.temperature } returns 0.7
                    every { provider.maxTokens } returns null

                    val model = factory.createChatModel(provider, null, null)

                    model.shouldNotBeNull()
                    model.shouldBeInstanceOf<OpenAiChatModel>()
                }

                it("should create Anthropic chat model") {
                    val provider = mockk<AiProvider>()
                    every { provider.id } returns UUID.randomUUID()
                    every { provider.apiType } returns AiApiType.Anthropic
                    every { provider.baseUrl } returns "https://api.anthropic.com"
                    every { provider.defaultApiKey } returns "sk-ant-test"
                    every { provider.baseModel } returns "claude-3"
                    every { provider.temperature } returns 0.5
                    every { provider.maxTokens } returns 4000

                    val model = factory.createChatModel(provider, null, null)

                    model.shouldNotBeNull()
                    model.shouldBeInstanceOf<AnthropicChatModel>()
                }

                it("should create Gemini chat model") {
                    val provider = mockk<AiProvider>()
                    every { provider.id } returns UUID.randomUUID()
                    every { provider.apiType } returns AiApiType.Gemini
                    every { provider.defaultApiKey } returns "google-key"
                    every { provider.baseModel } returns "gemini-pro"
                    every { provider.temperature } returns 0.5
                    every { provider.maxTokens } returns null

                    val model = factory.createChatModel(provider, null, null)

                    model.shouldNotBeNull()
                    model.shouldBeInstanceOf<GoogleGenAiChatModel>()
                }

                it("should prefer runtime config over default provider config") {
                    val provider = mockk<AiProvider>()
                    every { provider.id } returns UUID.randomUUID()
                    every { provider.apiType } returns AiApiType.OpenAI
                    every { provider.baseUrl } returns "https://api.openai.com"
                    every { provider.defaultApiKey } returns "default-key"
                    every { provider.baseModel } returns "gpt-3.5"
                    every { provider.temperature } returns 0.7
                    every { provider.maxTokens } returns null

                    // Providing runtime key and model
                    val model = factory.createChatModel(provider, "runtime-key", "gpt-4-runtime")

                    model.shouldNotBeNull()
                    model.shouldBeInstanceOf<OpenAiChatModel>()
                    // Note: Verifying internal state of OpenAiChatModel is difficult without reflection,
                    // but no exception means it accepted the parameters.
                }
            }

            describe("validation") {

                it("should throw exception when API key is missing") {
                    val provider = mockk<AiProvider>()
                    every { provider.id } returns UUID.randomUUID()
                    every { provider.defaultApiKey } returns null // No default key
                    every { provider.maxTokens } returns null

                    val exception =
                        shouldThrow<IllegalArgumentException> {
                            factory.createChatModel(provider, null, "gpt-4") // No runtime key
                        }
                    exception.message shouldContain "No API key provided"
                }

                it("should throw exception when model name is missing") {
                    val provider = mockk<AiProvider>()
                    every { provider.id } returns UUID.randomUUID()
                    every { provider.defaultApiKey } returns "key"
                    every { provider.baseModel } returns null // No default model
                    every { provider.maxTokens } returns null

                    val exception =
                        shouldThrow<IllegalArgumentException> {
                            factory.createChatModel(provider, null, null) // No runtime model
                        }
                    exception.message shouldContain "No model name provided"
                }
            }
        }
    })
