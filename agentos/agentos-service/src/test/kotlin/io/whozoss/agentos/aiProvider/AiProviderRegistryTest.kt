package io.whozoss.agentos.aiProvider

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import java.util.UUID

class AiProviderRegistryTest :
    DescribeSpec({

        describe("AiProviderRegistry") {

            lateinit var discoveryService: AiProviderDiscoveryService
            lateinit var registry: AiProviderRegistry

            beforeEach {
                discoveryService = mockk()
                registry = AiProviderRegistry(discoveryService)
            }

            describe("refreshProviders") {

                it("should load providers from discovery service") {
                    val providerId = UUID.randomUUID()
                    val provider = mockk<AiProvider>()
                    every { provider.id } returns providerId
                    every { provider.name } returns "openai"
                    every { discoveryService.discoverAiProviders() } returns listOf(provider)

                    registry.refreshProviders()

                    registry.getProviderMetadata(providerId) shouldBe provider
                    registry.getAllProviders() shouldHaveSize 1
                }

                it("should replace all providers on refresh") {
                    val p1 = mockk<AiProvider>()
                    val p1Id = UUID.randomUUID()
                    every { p1.id } returns p1Id
                    every { p1.name } returns "provider-1"

                    val p2 = mockk<AiProvider>()
                    val p2Id = UUID.randomUUID()
                    every { p2.id } returns p2Id
                    every { p2.name } returns "provider-2"

                    every { discoveryService.discoverAiProviders() } returns listOf(p1)
                    registry.refreshProviders()
                    registry.getAllProviders() shouldHaveSize 1

                    every { discoveryService.discoverAiProviders() } returns listOf(p2)
                    registry.refreshProviders()

                    registry.getAllProviders() shouldHaveSize 1
                    registry.getProviderMetadata(p2Id).shouldNotBeNull()
                    registry.getProviderMetadata(p1Id).shouldBeNull()
                }

                it("should return empty list when no providers discovered") {
                    every { discoveryService.discoverAiProviders() } returns emptyList()
                    registry.refreshProviders()
                    registry.getAllProviders().shouldBeEmpty()
                }
            }

            describe("getProviderByName") {

                it("should find provider by name") {
                    val provider = mockk<AiProvider>()
                    every { provider.id } returns UUID.randomUUID()
                    every { provider.name } returns "anthropic"
                    every { discoveryService.discoverAiProviders() } returns listOf(provider)
                    registry.refreshProviders()

                    registry.getProviderByName("anthropic") shouldBe provider
                }

                it("should return null for unknown name") {
                    every { discoveryService.discoverAiProviders() } returns emptyList()
                    registry.refreshProviders()

                    registry.getProviderByName("unknown").shouldBeNull()
                }
            }
        }
    })
