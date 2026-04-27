package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.aiModel.AiModelRepository
import io.whozoss.agentos.aiProvider.AiProviderRepository
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Shared persistence contract tests for [AiModelRepository] / [Neo4JAiModelRepository].
 *
 * Exercises the real Cypher queries in [io.whozoss.agentos.aiModel.AiModelNodeNeo4jRepository],
 * including [findActiveByAiProviderIdAndAlias] — the query that was broken by the
 * GqlParams.StringParam.alias workaround and fixed in PR #790.
 *
 * Subclasses activate a specific persistence mode (embedded harness or Testcontainers)
 * and inherit all test cases, ensuring both modes satisfy the same contract.
 */
abstract class AbstractAiModelPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var repo: AiModelRepository

    @Autowired
    lateinit var providerRepo: AiProviderRepository

    @Autowired
    lateinit var driver: Driver

    private fun aiProvider(namespaceId: UUID = UUID.randomUUID()): AiProvider =
        AiProvider(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            name = "anthropic",
            apiType = AiApiType.Anthropic,
        )

    private fun aiModel(
        aiProviderId: UUID,
        namespaceId: UUID = UUID.randomUUID(),
        apiName: String = "claude-haiku-4-5",
        alias: String? = null,
        priority: Int = 0,
    ): AiModel =
        AiModel(
            metadata = EntityMetadata(),
            aiProviderId = aiProviderId,
            namespaceId = namespaceId,
            apiModelName = apiName,
            alias = alias,
            priority = priority,
        )

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        // -------------------------------------------------------------------------
        // Basic save / read
        // -------------------------------------------------------------------------

        "save and findByIds returns the same model" {
            val provider = providerRepo.save(aiProvider())
            val saved = repo.save(aiModel(aiProviderId = provider.id, apiName = "claude-haiku-4-5", alias = "small"))

            val found = repo.findByIds(listOf(saved.id))

            found shouldHaveSize 1
            found.first().id shouldBe saved.id
            found.first().apiModelName shouldBe "claude-haiku-4-5"
            found.first().alias shouldBe "small"
        }

        // -------------------------------------------------------------------------
        // findByParent
        // -------------------------------------------------------------------------

        "findByParent returns only models for the given aiProviderId" {
            val providerA = providerRepo.save(aiProvider())
            val providerB = providerRepo.save(aiProvider())
            repo.save(aiModel(aiProviderId = providerA.id, apiName = "claude-haiku-4-5"))
            repo.save(aiModel(aiProviderId = providerA.id, apiName = "claude-opus-4-6"))
            repo.save(aiModel(aiProviderId = providerB.id, apiName = "gpt-4o"))

            repo.findByParent(providerA.id) shouldHaveSize 2
            repo.findByParent(providerB.id) shouldHaveSize 1
            repo.findByParent(UUID.randomUUID()).shouldBeEmpty()
        }

        "findByParent returns models sorted by apiName" {
            val provider = providerRepo.save(aiProvider())
            repo.save(aiModel(aiProviderId = provider.id, apiName = "claude-opus-4-6"))
            repo.save(aiModel(aiProviderId = provider.id, apiName = "claude-haiku-4-5"))
            repo.save(aiModel(aiProviderId = provider.id, apiName = "claude-sonnet-4-6"))

            repo.findByParent(provider.id).map { it.apiModelName } shouldBe
                listOf("claude-haiku-4-5", "claude-opus-4-6", "claude-sonnet-4-6")
        }

        // -------------------------------------------------------------------------
        // findByAiProviderAndApiName
        // -------------------------------------------------------------------------

        "findByAiProviderAndApiName returns the matching model" {
            val provider = providerRepo.save(aiProvider())
            repo.save(aiModel(aiProviderId = provider.id, apiName = "claude-haiku-4-5"))

            val found = repo.findByAiProviderAndApiName(provider.id, "claude-haiku-4-5")

            found.shouldNotBeNull()
            found.apiModelName shouldBe "claude-haiku-4-5"
        }

        "findByAiProviderAndApiName returns null when apiName does not match" {
            val provider = providerRepo.save(aiProvider())
            repo.save(aiModel(aiProviderId = provider.id, apiName = "claude-haiku-4-5"))

            repo.findByAiProviderAndApiName(provider.id, "unknown-model").shouldBeNull()
        }

        "findByAiProviderAndApiName does not cross provider boundary" {
            val providerA = providerRepo.save(aiProvider())
            val providerB = providerRepo.save(aiProvider())
            repo.save(aiModel(aiProviderId = providerA.id, apiName = "claude-haiku-4-5"))

            repo.findByAiProviderAndApiName(providerB.id, "claude-haiku-4-5").shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // findByAiProviderAndAlias  — this is the query fixed in PR #790
        // -------------------------------------------------------------------------

        "findByAiProviderAndAlias returns the matching model" {
            val provider = providerRepo.save(aiProvider())
            repo.save(aiModel(aiProviderId = provider.id, apiName = "claude-haiku-4-5", alias = "small"))

            val found = repo.findByAiProviderAndAlias(provider.id, "small")

            found.shouldNotBeNull()
            found.alias shouldBe "small"
            found.apiModelName shouldBe "claude-haiku-4-5"
        }

        "findByAiProviderAndAlias returns null when alias does not match" {
            val provider = providerRepo.save(aiProvider())
            repo.save(aiModel(aiProviderId = provider.id, apiName = "claude-haiku-4-5", alias = "small"))

            repo.findByAiProviderAndAlias(provider.id, "big").shouldBeNull()
        }

        "findByAiProviderAndAlias returns null when model has no alias" {
            val provider = providerRepo.save(aiProvider())
            repo.save(aiModel(aiProviderId = provider.id, apiName = "claude-haiku-4-5", alias = null))

            repo.findByAiProviderAndAlias(provider.id, "small").shouldBeNull()
        }

        "findByAiProviderAndAlias does not cross provider boundary" {
            val providerA = providerRepo.save(aiProvider())
            val providerB = providerRepo.save(aiProvider())
            repo.save(aiModel(aiProviderId = providerA.id, apiName = "claude-haiku-4-5", alias = "small"))

            repo.findByAiProviderAndAlias(providerB.id, "small").shouldBeNull()
        }

        "findByAiProviderAndAlias does not return soft-deleted models" {
            val provider = providerRepo.save(aiProvider())
            val saved = repo.save(aiModel(aiProviderId = provider.id, apiName = "claude-haiku-4-5", alias = "small"))
            repo.delete(saved.id)

            repo.findByAiProviderAndAlias(provider.id, "small").shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // Soft delete
        // -------------------------------------------------------------------------

        "soft delete removes model from findByIds" {
            val provider = providerRepo.save(aiProvider())
            val saved = repo.save(aiModel(aiProviderId = provider.id))

            repo.delete(saved.id).shouldBeTrue()
            repo.findByIds(listOf(saved.id)).shouldBeEmpty()
        }

        "double delete returns false" {
            val provider = providerRepo.save(aiProvider())
            val saved = repo.save(aiModel(aiProviderId = provider.id))

            repo.delete(saved.id).shouldBeTrue()
            repo.delete(saved.id).shouldBeFalse()
        }

        // -------------------------------------------------------------------------
        // deleteByParent
        // -------------------------------------------------------------------------

        "deleteByParent removes all models for the provider without touching others" {
            val providerA = providerRepo.save(aiProvider())
            val providerB = providerRepo.save(aiProvider())
            repo.save(aiModel(aiProviderId = providerA.id, apiName = "claude-haiku-4-5"))
            repo.save(aiModel(aiProviderId = providerA.id, apiName = "claude-opus-4-6"))
            val survivor = repo.save(aiModel(aiProviderId = providerB.id, apiName = "gpt-4o"))

            repo.deleteByParent(providerA.id) shouldBe 2
            repo.findByParent(providerA.id).shouldBeEmpty()
            repo.findByParent(providerB.id) shouldHaveSize 1
            repo.findByParent(providerB.id).first().id shouldBe survivor.id
        }

        // -------------------------------------------------------------------------
        // update
        // -------------------------------------------------------------------------

        "update: save with same id replaces the node" {
            val provider = providerRepo.save(aiProvider())
            val saved = repo.save(aiModel(aiProviderId = provider.id, apiName = "claude-haiku-4-5", alias = "small"))

            repo.save(saved.copy(alias = "tiny"))

            val found = repo.findByIds(listOf(saved.id))
            found shouldHaveSize 1
            found.first().alias shouldBe "tiny"
        }
    }
}
