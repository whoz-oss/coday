package io.whozoss.agentos.aiModel

import mu.KLogging
import org.springframework.data.neo4j.core.Neo4jClient

/**
 * One-time migration of persisted `AiModel` nodes that predate `supportsAdvancedExecution`.
 *
 * Rule: a model linked to at least one persisted, non-removed agent running ADVANCED (explicit
 * `executionMode = 'ADVANCED'`, or no `executionMode` and the legacy `advancedExecution = true`)
 * gets the flag `true`; every other migrated model gets `false`. `supportsAdvancedExecution` is a
 * capability that ADVANCED agents require, so this keeps the existing ADVANCED agents running.
 *
 * The marker is the missing property: only nodes where `supportsAdvancedExecution IS NULL` are
 * written, and each of them gets an explicit boolean. Nodes saved by the current code always carry
 * a boolean, so a later restart never touches them and never overrides an admin's choice.
 *
 * Agent-to-model linking mirrors the runtime (`AgentServiceImpl` + `AiModelServiceImpl.findAiModel`):
 * the name is `modelName ?: "default"`, matched on alias first then on `apiModelName`
 * (case-insensitive) among the non-removed models of the agent's namespace plus platform models, the
 * winner chosen by scope (namespace > platform) then priority. As at runtime, a `modelName` that
 * matches nothing falls back to the `default` model. A platform agent runs in any namespace, so its
 * model cannot be determined: every matching model counts, at any scope.
 *
 * Filesystem models and agents are not persisted and are neither read nor written.
 */
class AiModelAdvancedExecutionMigration(
    private val neo4jClient: Neo4jClient,
) {
    internal data class ModelRow(
        val id: String,
        val namespaceId: String?,
        val alias: String?,
        val apiName: String,
        val priority: Int,
        val removed: Boolean,
    ) {
        val label: String get() = alias ?: apiName
    }

    internal data class AgentRow(
        val id: String,
        val name: String,
        val namespaceId: String?,
        val modelName: String?,
    )

    fun run() {
        val legacyCount = countLegacyModels()
        if (legacyCount == 0L) {
            logger.debug { "[Migration] No AiModel node lacks supportsAdvancedExecution — nothing to migrate" }
            return
        }

        val models = loadModels()
        val legacyIds = loadLegacyModelIds()
        val advancedAgents = loadAgents()
        val links = advancedAgents.associateWith { linkedModels(it, models) }
        val advancedLinkedIds = links.values.flatten().map { it.id }.toSet()
        val toTrue = models.filter { it.id in legacyIds && it.id in advancedLinkedIds }

        // One statement for every legacy node: true for the linked ones, false for all the others.
        // The IS NULL guard leaves untouched any node saved meanwhile (it carries a boolean).
        val result =
            neo4jClient
                .query(
                    $$"""
                    MATCH (m:AiModel)
                    WHERE m.supportsAdvancedExecution IS NULL
                    SET m.supportsAdvancedExecution = m.id IN $trueIds
                    RETURN count(m) AS total,
                           sum(CASE WHEN m.supportsAdvancedExecution THEN 1 ELSE 0 END) AS enabled
                    """.trimIndent(),
                ).bind(toTrue.map { it.id }).to("trueIds")
                .fetch()
                .one()
                .orElse(emptyMap())
        val total = (result["total"] as? Number)?.toLong() ?: 0L
        val enabled = (result["enabled"] as? Number)?.toLong() ?: 0L
        logger.info {
            "[Migration] supportsAdvancedExecution: set to true on $enabled AiModel node(s) " +
                "${toTrue.map { it.label }.sorted()}, to false on ${total - enabled}"
        }

        // Such an agent cannot run: it fails at instantiation for lack of a model. Worth a line in the logs.
        links.filterValues { it.isEmpty() }.keys.forEach { agent ->
            logger.warn {
                "[Migration] ADVANCED agent '${agent.name}' (id=${agent.id}) matches no persisted AiModel " +
                    "(modelName=${agent.modelName}): no model was marked for it"
            }
        }
    }

    private fun countLegacyModels(): Long =
        neo4jClient
            .query("MATCH (m:AiModel) WHERE m.supportsAdvancedExecution IS NULL RETURN count(m) AS legacy")
            .fetch()
            .one()
            .map { (it["legacy"] as Number).toLong() }
            .orElse(0L)

    private fun loadLegacyModelIds(): Set<String> =
        neo4jClient
            .query("MATCH (m:AiModel) WHERE m.supportsAdvancedExecution IS NULL RETURN m.id AS id")
            .fetch()
            .all()
            .map { it["id"] as String }
            .toSet()

    /** Every persisted model. Removed ones are kept in the list so they can be told apart, and never count as a link. */
    private fun loadModels(): List<ModelRow> =
        neo4jClient
            .query(
                """
                MATCH (m:AiModel)
                RETURN m.id AS id, m.namespaceId AS namespaceId, m.alias AS alias, m.apiName AS apiName,
                       coalesce(m.priority, 0) AS priority, coalesce(m.removed, false) AS removed
                ORDER BY m.apiName ASC
                """.trimIndent(),
            ).fetch()
            .all()
            .map {
                ModelRow(
                    id = it["id"] as String,
                    namespaceId = it["namespaceId"] as String?,
                    alias = it["alias"] as String?,
                    apiName = it["apiName"] as String,
                    priority = (it["priority"] as Number).toInt(),
                    removed = it["removed"] == true,
                )
            }

    /**
     * Non-removed persisted agents running ADVANCED: an explicit `executionMode`, or no `executionMode`
     * and the legacy flag. Disabled agents are included.
     */
    private fun loadAgents(): List<AgentRow> {
        val modeFilter =
            "a.executionMode = 'ADVANCED' OR (a.executionMode IS NULL AND coalesce(a.advancedExecution, false) = true)"
        return neo4jClient
            .query(
                """
                MATCH (a:AgentConfig)
                WHERE coalesce(a.removed, false) = false AND ($modeFilter)
                RETURN a.id AS id, a.name AS name, a.namespaceId AS namespaceId, a.modelName AS modelName
                """.trimIndent(),
            ).fetch()
            .all()
            .map {
                AgentRow(
                    id = it["id"] as String,
                    name = it["name"] as String,
                    namespaceId = it["namespaceId"] as String?,
                    modelName = it["modelName"] as String?,
                )
            }
    }

    /** The non-removed models [agent] can run on: exactly one for a namespace agent, every match for a platform agent. */
    private fun linkedModels(
        agent: AgentRow,
        models: List<ModelRow>,
    ): List<ModelRow> {
        val active = models.filter { !it.removed }
        // The named model first; the default one only when the name matches nothing (see AgentServiceImpl).
        return listOfNotNull(agent.modelName, DEFAULT_MODEL_NAME)
            .firstNotNullOfOrNull { name ->
                val found =
                    if (agent.namespaceId == null) {
                        matchAnyScope(active, name)
                    } else {
                        listOfNotNull(resolve(active, agent.namespaceId, name))
                    }
                found.takeIf { it.isNotEmpty() }
            } ?: emptyList()
    }

    /** Mirror of `AiModelServiceImpl.findAiModel` for one namespace. */
    private fun resolve(
        active: List<ModelRow>,
        namespaceId: String,
        name: String,
    ): ModelRow? {
        val visible = active.filter { it.namespaceId == null || it.namespaceId == namespaceId }
        return visible
            .filter { it.alias.equals(name, ignoreCase = true) }
            .maxWithOrNull(MODEL_COMPARATOR)
            ?: visible
                .filter { it.apiName.equals(name, ignoreCase = true) }
                .maxWithOrNull(MODEL_COMPARATOR)
    }

    /** Platform agent: every model matching on alias, or on apiName when no alias matches, at any scope. */
    private fun matchAnyScope(
        active: List<ModelRow>,
        name: String,
    ): List<ModelRow> =
        active.filter { it.alias.equals(name, ignoreCase = true) }.ifEmpty {
            active.filter { it.apiName.equals(name, ignoreCase = true) }
        }

    companion object : KLogging() {
        private const val DEFAULT_MODEL_NAME = "default"

        /** Same ordering as `AiModelServiceImpl`: namespace scope beats platform, then higher priority. */
        private val MODEL_COMPARATOR: Comparator<ModelRow> =
            compareBy({ if (it.namespaceId != null) 1 else 0 }, { it.priority })
    }
}
