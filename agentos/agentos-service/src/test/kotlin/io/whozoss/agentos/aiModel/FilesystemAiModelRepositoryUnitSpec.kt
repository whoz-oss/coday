package io.whozoss.agentos.aiModel

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.aiProvider.AiProviderRepository
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class FilesystemAiModelRepositoryUnitSpec :
    StringSpec({
        val namespaceId = UUID.randomUUID()

        fun configDir(vararg files: Pair<String, String>): Path =
            Files.createTempDirectory("ai-model-repo-test").also { root ->
                val dir = Files.createDirectories(root.resolve("ai-models"))
                files.forEach { (name, content) -> Files.writeString(dir.resolve(name), content) }
            }

        fun provider(
            name: String,
            namespace: UUID? = namespaceId,
        ) = AiProvider(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = namespace,
            name = name,
            apiType = AiApiType.OpenAI,
        )

        fun repo(
            configPath: Path?,
            persisted: List<AiModel> = emptyList(),
            namespaceProviders: List<AiProvider> = emptyList(),
            platformProviders: List<AiProvider> = emptyList(),
            platformModels: List<AiModel> = emptyList(),
        ): FilesystemAiModelRepository {
            val delegate =
                mockk<AiModelRepository> {
                    every { findByNamespaceId(namespaceId) } returns persisted
                    every { findAllForNamespace(namespaceId) } returns persisted + platformModels
                }
            val namespaces =
                mockk<NamespaceRepository> {
                    every { findByIds(listOf(namespaceId)) } returns
                        listOf(Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns", configPath = configPath?.toString()))
                }
            val providers =
                mockk<AiProviderRepository> {
                    every { findByNamespaceId(namespaceId) } returns namespaceProviders
                    every { findPlatformLevel() } returns platformProviders
                }
            return FilesystemAiModelRepository(delegate, namespaces, providers)
        }

        val bigYaml =
            """
            alias: BIG
            apiModelName: anthropic/claude-sonnet-5-5
            provider: requesty
            priority: 10
            contextWindow: 1000000
            pricing:
              inputMTokens: 2
              outputMTokens: 10
              cacheRead: 0.2
              cacheWrite: 2.5
            """.trimIndent()

        "loads a model bound to the namespace provider named in the file" {
            val requesty = provider("requesty")
            val models = repo(configDir("BIG.yaml" to bigYaml), namespaceProviders = listOf(requesty)).findAllForNamespace(namespaceId)

            models shouldHaveSize 1
            with(models.single()) {
                alias shouldBe "BIG"
                apiModelName shouldBe "anthropic/claude-sonnet-5-5"
                aiProviderId shouldBe requesty.id
                this.namespaceId shouldBe namespaceId
                priority shouldBe 10
                contextWindow shouldBe 1_000_000L
                pricing shouldBe ModelPricing(inputMTokens = 2.0, outputMTokens = 10.0, cacheRead = 0.2, cacheWrite = 2.5)
            }
        }

        "keeps a stable id across reads" {
            val repository = repo(configDir("BIG.yaml" to bigYaml), namespaceProviders = listOf(provider("requesty")))
            repository.findByNamespaceId(namespaceId).single().id shouldBe repository.findAllForNamespace(namespaceId).single().id
        }

        "resolves a platform provider when the namespace has none of that name" {
            val platform = provider("requesty", namespace = null)
            repo(configDir("BIG.yaml" to bigYaml), platformProviders = listOf(platform))
                .findAllForNamespace(namespaceId)
                .single()
                .aiProviderId shouldBe platform.id
        }

        "skips a model whose provider does not exist in this installation" {
            repo(configDir("BIG.yaml" to bigYaml), namespaceProviders = listOf(provider("openai"))).findAllForNamespace(namespaceId).shouldBeEmpty()
        }

        "a persisted model with the same provider and alias wins" {
            val requesty = provider("requesty")
            val persisted =
                AiModel(aiProviderId = requesty.id, namespaceId = namespaceId, apiModelName = "anthropic/claude-opus-5-5", alias = "big")
            val models = repo(configDir("BIG.yaml" to bigYaml), persisted = listOf(persisted), namespaceProviders = listOf(requesty))
                .findAllForNamespace(namespaceId)

            models shouldBe listOf(persisted)
        }

        "keeps platform models from the delegate and adds filesystem ones" {
            val requesty = provider("requesty")
            val platformModel = AiModel(aiProviderId = UUID.randomUUID(), apiModelName = "gpt-platform", alias = "BIG")
            repo(configDir("BIG.yaml" to bigYaml), namespaceProviders = listOf(requesty), platformModels = listOf(platformModel))
                .findAllForNamespace(namespaceId)
                .map { it.apiModelName } shouldBe listOf("gpt-platform", "anthropic/claude-sonnet-5-5")
        }

        "ignores files missing apiModelName or provider" {
            repo(configDir("bad.yaml" to "alias: BROKEN\nprovider: requesty\n"), namespaceProviders = listOf(provider("requesty")))
                .findAllForNamespace(namespaceId)
                .shouldBeEmpty()
        }

        "returns only persisted models when the namespace has no configPath" {
            repo(null, namespaceProviders = listOf(provider("requesty"))).findAllForNamespace(namespaceId).shouldBeEmpty()
        }
    
        "the ai-models committed at the repository root all load" {
            // Gradle runs tests from agentos/agentos-service; the namespace configPath is the repository root.
            val repositoryRoot = Path.of("../..").toAbsolutePath().normalize()
            val committed = Files.list(repositoryRoot.resolve("ai-models")).use { files -> files.filter { it.toString().endsWith(".yaml") }.count() }
            val models = repo(repositoryRoot, namespaceProviders = listOf(provider("requesty"))).findAllForNamespace(namespaceId)

            models shouldHaveSize committed.toInt()
            models.forEach { it.pricing?.isEmpty shouldBe false }
        }
    })
