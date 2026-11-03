package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.aiModel.AiModelRepository
import io.whozoss.agentos.config.Neo4jPersistenceConfiguration
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.api.agentConfig.ExecutionMode
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.neo4j.core.Neo4jClient
import java.util.UUID

/**
 * Contract tests of the one-time `supportsAdvancedExecution` migration of persisted AiModel nodes
 * (`Neo4jPersistenceConfiguration.migrateAiModelSupportsAdvancedExecution`).
 *
 * Legacy nodes are simulated the way the maxTokens migration test does: the property is removed with
 * Cypher after the node is saved through the repository.
 */
abstract class AbstractAiModelAdvancedExecutionMigrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var modelRepo: AiModelRepository

    @Autowired
    lateinit var agentRepo: AgentConfigRepository

    @Autowired
    lateinit var driver: Driver

    @Autowired
    lateinit var neo4jClient: Neo4jClient

    // The migration never reads the provider: a model only stores its id.
    private val providerId = UUID.randomUUID()

    /** Saves a model, then removes `supportsAdvancedExecution` unless [flag] is given (a current-code node). */
    private fun model(
        namespaceId: UUID?,
        alias: String?,
        apiName: String = "claude-${UUID.randomUUID()}",
        priority: Int = 0,
        flag: Boolean? = null,
    ): AiModel {
        val saved =
            modelRepo.save(
                AiModel(
                    metadata = EntityMetadata(),
                    aiProviderId = providerId,
                    namespaceId = namespaceId,
                    apiModelName = apiName,
                    alias = alias,
                    priority = priority,
                    supportsAdvancedExecution = flag ?: false,
                ),
            )
        if (flag == null) {
            neo4jClient
                .query("MATCH (m:AiModel {id: \$id}) REMOVE m.supportsAdvancedExecution")
                .bind(saved.id.toString())
                .to("id")
                .run()
        }
        return saved
    }

    @Suppress("DEPRECATION")
    private fun agent(
        namespaceId: UUID?,
        modelName: String?,
        mode: ExecutionMode? = null,
        legacyAdvanced: Boolean = false,
        enabled: Boolean = true,
        removed: Boolean = false,
    ): AgentConfig {
        val saved =
            agentRepo.save(
                AgentConfig(
                    metadata = EntityMetadata(),
                    namespaceId = namespaceId,
                    name = "agent-${UUID.randomUUID()}",
                    modelName = modelName,
                    executionMode = mode,
                    advancedExecution = legacyAdvanced,
                    enabled = enabled,
                ),
            )
        if (removed) agentRepo.delete(saved.id)
        return saved
    }

    private fun migrate() = Neo4jPersistenceConfiguration().migrateAiModelSupportsAdvancedExecution(neo4jClient).run()

    private fun flag(model: AiModel): Boolean = modelRepo.findByIds(listOf(model.id)).single().supportsAdvancedExecution

    private fun hasProperty(model: AiModel): Boolean =
        neo4jClient
            .query("MATCH (m:AiModel {id: \$id}) RETURN m.supportsAdvancedExecution IS NOT NULL AS present")
            .bind(model.id.toString())
            .to("id")
            .fetch()
            .one()
            .orElseThrow()["present"] == true

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "a namespace ADVANCED agent marks the model it names by alias, not its sibling" {
            val ns = UUID.randomUUID()
            val big = model(ns, alias = "big")
            val small = model(ns, alias = "small")
            agent(ns, modelName = "BIG", mode = ExecutionMode.ADVANCED)

            migrate()

            flag(big) shouldBe true
            flag(small) shouldBe false
            hasProperty(small) shouldBe true
        }

        "a legacy advancedExecution agent, even disabled, marks its model" {
            val ns = UUID.randomUUID()
            val big = model(ns, alias = "big")
            agent(ns, modelName = "big", legacyAdvanced = true, enabled = false)

            migrate()

            flag(big) shouldBe true
        }

        "SIMPLE and LOOP agents only leave every model false" {
            val ns = UUID.randomUUID()
            val big = model(ns, alias = "big")
            val small = model(ns, alias = "small")
            agent(ns, modelName = "big", mode = ExecutionMode.SIMPLE)
            agent(ns, modelName = "small", mode = ExecutionMode.LOOP)
            agent(ns, modelName = "big")

            migrate()

            flag(big) shouldBe false
            flag(small) shouldBe false
            hasProperty(big) shouldBe true
            hasProperty(small) shouldBe true
        }

        "an agent without modelName marks the default model" {
            val ns = UUID.randomUUID()
            val default = model(ns, alias = "default")
            val other = model(ns, alias = "big")
            agent(ns, modelName = null, mode = ExecutionMode.ADVANCED)

            migrate()

            flag(default) shouldBe true
            flag(other) shouldBe false
        }

        "an agent naming no alias falls back to the apiModelName, case-insensitively" {
            val ns = UUID.randomUUID()
            val target = model(ns, alias = null, apiName = "Claude-Opus-4-6")
            val other = model(ns, alias = "other", apiName = "claude-haiku-4-5")
            agent(ns, modelName = "claude-opus-4-6", mode = ExecutionMode.ADVANCED)

            migrate()

            flag(target) shouldBe true
            flag(other) shouldBe false
        }

        "an alias match wins over an apiModelName match" {
            val ns = UUID.randomUUID()
            val byAlias = model(ns, alias = "sonnet", apiName = "claude-haiku-4-5")
            val byApiName = model(ns, alias = null, apiName = "sonnet", priority = 100)
            agent(ns, modelName = "sonnet", mode = ExecutionMode.ADVANCED)

            migrate()

            flag(byAlias) shouldBe true
            flag(byApiName) shouldBe false
        }

        "namespace scope beats platform, whatever the priority" {
            val ns = UUID.randomUUID()
            val namespaceModel = model(ns, alias = "big", priority = 0)
            val platformModel = model(null, alias = "big", priority = 100)
            agent(ns, modelName = "big", mode = ExecutionMode.ADVANCED)

            migrate()

            flag(namespaceModel) shouldBe true
            flag(platformModel) shouldBe false
        }

        "priority decides between models of the same scope, and other namespaces are not visible" {
            val ns = UUID.randomUUID()
            val low = model(ns, alias = "big", priority = 1)
            val high = model(ns, alias = "big", priority = 5)
            val elsewhere = model(UUID.randomUUID(), alias = "big", priority = 50)
            agent(ns, modelName = "big", mode = ExecutionMode.ADVANCED)

            migrate()

            flag(high) shouldBe true
            flag(low) shouldBe false
            flag(elsewhere) shouldBe false
        }

        "a platform ADVANCED agent marks every matching model at any scope" {
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()
            val inA = model(nsA, alias = "big")
            val inB = model(nsB, alias = "BIG")
            val onPlatform = model(null, alias = "big")
            val apiNameOnly = model(nsA, alias = null, apiName = "big")
            val unrelated = model(nsA, alias = "small")
            agent(null, modelName = "big", mode = ExecutionMode.ADVANCED)

            migrate()

            flag(inA) shouldBe true
            flag(inB) shouldBe true
            flag(onPlatform) shouldBe true
            // apiModelName is only a fallback when no alias matches
            flag(apiNameOnly) shouldBe false
            flag(unrelated) shouldBe false
        }

        "a platform ADVANCED agent falls back to apiModelName when no alias matches" {
            val ns = UUID.randomUUID()
            val target = model(ns, alias = null, apiName = "gpt-4o")
            agent(null, modelName = "GPT-4o", mode = ExecutionMode.ADVANCED)

            migrate()

            flag(target) shouldBe true
        }

        "a removed agent does not count, and a removed model is never the link" {
            val ns = UUID.randomUUID()
            val big = model(ns, alias = "big")
            val gone = model(ns, alias = "gone")
            modelRepo.delete(gone.id)
            agent(ns, modelName = "big", mode = ExecutionMode.ADVANCED, removed = true)
            agent(ns, modelName = "gone", mode = ExecutionMode.ADVANCED)

            migrate()

            flag(big) shouldBe false
            modelRepo.findByIds(listOf(gone.id), withRemoved = true).single().supportsAdvancedExecution shouldBe false
        }

        "an admin's change survives a second run" {
            val ns = UUID.randomUUID()
            val big = model(ns, alias = "big")
            agent(ns, modelName = "big", mode = ExecutionMode.ADVANCED)
            migrate()
            flag(big) shouldBe true

            modelRepo.save(modelRepo.findByIds(listOf(big.id)).single().copy(supportsAdvancedExecution = false))
            migrate()

            flag(big) shouldBe false
        }

        "a model that already has the property is never changed" {
            val ns = UUID.randomUUID()
            val enabledWithoutAgent = model(ns, alias = "kept-true", flag = true)
            val disabledWithAgent = model(ns, alias = "kept-false", flag = false)
            val legacy = model(ns, alias = "legacy")
            agent(ns, modelName = "kept-false", mode = ExecutionMode.ADVANCED)
            agent(ns, modelName = "legacy", mode = ExecutionMode.ADVANCED)

            migrate()

            flag(enabledWithoutAgent) shouldBe true
            flag(disabledWithAgent) shouldBe false
            flag(legacy) shouldBe true
        }

        "an agent whose modelName matches nothing links the default model, as at runtime" {
            val ns = UUID.randomUUID()
            val default = model(ns, alias = "default")
            agent(ns, modelName = "does-not-exist", mode = ExecutionMode.ADVANCED)

            migrate()

            flag(default) shouldBe true
        }
    }
}
