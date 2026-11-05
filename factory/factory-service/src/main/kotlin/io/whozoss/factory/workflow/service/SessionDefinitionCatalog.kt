package io.whozoss.factory.workflow.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.sdk.spi.FactoryFlowCatalogContributor
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.executionPolicyOf
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import org.pf4j.PluginManager
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Classpath + PF4J plugin catalogue of the declarative session definitions
 * (resources under `sessions/`, JSON files, plus flows contributed via
 * [FactoryFlowCatalogContributor]).
 *
 * Every resource or plugin-contributed JSON string is validated through
 * [WorkflowDefinitionValidator] (unique ids, existing/acyclic `dependsOn`,
 * `kind` in `agent|code|human`) before being returned as a canonical
 * [WorkflowDefinitionRecord]. A malformed definition fails loudly at load time
 * instead of silently seeding garbage.
 */
@Component
class SessionDefinitionCatalog(
    private val objectMapper: ObjectMapper,
    private val pluginManager: PluginManager? = null,
) {

    /** All bundled and plugin-contributed declarative definitions. */
    fun load(): List<WorkflowDefinitionRecord> {
        val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)
        val resources = resolver.getResources("classpath*:sessions/*.json")
        val bundled = resources.sortedBy { it.filename ?: "" }.map { resource ->
            val raw = resource.inputStream.use { objectMapper.readValue(it, Any::class.java) }
            validateAndBuildRecord(raw, "Bundled session definition ${resource.filename}")
        }
        val contributed = loadPluginFlows()
        val byKey = LinkedHashMap<Pair<String, String>, WorkflowDefinitionRecord>()
        for (record in bundled) {
            byKey[record.workflowType to record.version] = record
        }
        for (record in contributed) {
            byKey[record.workflowType to record.version] = record
        }
        return byKey.values.toList()
    }

    /** Loads and validates JSON definitions contributed by PF4J [FactoryFlowCatalogContributor] extensions. */
    fun loadPluginFlows(): List<WorkflowDefinitionRecord> {
        val manager = pluginManager ?: return emptyList()
        val contributors = runCatching { manager.getExtensions(FactoryFlowCatalogContributor::class.java) }
            .getOrDefault(emptyList())
        return contributors.flatMap { contributor ->
            contributor.flows().mapIndexed { index, rawJson ->
                val raw = objectMapper.readValue(rawJson, Any::class.java)
                validateAndBuildRecord(raw, "Plugin flow definition #${index + 1} from ${contributor.javaClass.name}")
            }
        }
    }

    private fun validateAndBuildRecord(raw: Any?, sourceLabel: String): WorkflowDefinitionRecord =
        when (val validation = WorkflowDefinitionValidator.validate(raw)) {
            is WorkflowDefinitionValidation.Valid -> WorkflowDefinitionRecord(
                workflowType = validation.definition["workflowType"] as String,
                version = validation.definition["version"] as String,
                definitionHash = hashWorkflowDefinition(validation.definition),
                definition = validation.definition,
                executionPolicy = executionPolicyOf(validation.definition),
            )
            is WorkflowDefinitionValidation.Invalid -> throw IllegalArgumentException(
                "$sourceLabel is invalid: ${validation.error.code} at ${validation.error.path}",
            )
        }
}

/**
 * Seeds the bundled declarative session definitions (import/bootstrap).
 *
 * The seed is an idempotent import: an already-registered
 * `workflowType@version` is left untouched, so a tenant-authored definition
 * always wins over the bundled default.
 */
@Service
class WorkflowDefinitionSeeder(
    private val repository: WorkflowRepository,
    private val catalog: SessionDefinitionCatalog,
) {

    fun seed(scope: TenantScope): List<String> = seed(io.whozoss.factory.persistence.OrganizationScope(scope.organizationId))

    /** Registers every bundled definition missing from [scope]; returns the seeded `type@version` keys. */
    @Transactional
    fun seed(scope: io.whozoss.factory.persistence.OrganizationScope): List<String> {
        val existing = repository.listDefinitions(scope).map { it.workflowType to it.version }.toSet()
        val seeded = mutableListOf<String>()
        for (record in catalog.load()) {
            if ((record.workflowType to record.version) in existing) continue
            repository.saveDefinition(scope, record)
            seeded += "${record.workflowType}@${record.version}"
        }
        return seeded
    }
}
