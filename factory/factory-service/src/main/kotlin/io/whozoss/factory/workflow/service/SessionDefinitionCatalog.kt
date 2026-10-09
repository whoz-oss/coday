package io.whozoss.factory.workflow.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.executionPolicyOf
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Classpath catalogue of the declarative session definitions bundled with the
 * service (resources under `sessions/`, JSON files).
 *
 * Every resource is validated through [WorkflowDefinitionValidator] (unique ids,
 * existing/acyclic `dependsOn`, `kind` in `agent|code|human`) before being
 * returned as a canonical [WorkflowDefinitionRecord]. A malformed bundled
 * definition fails loudly at load time instead of silently seeding garbage.
 */
@Component
class SessionDefinitionCatalog(
    private val objectMapper: ObjectMapper,
) {

    /** All bundled declarative definitions, sorted by file name for determinism. */
    fun load(): List<WorkflowDefinitionRecord> {
        val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)
        val resources = resolver.getResources("classpath*:sessions/*.json")
        return resources.sortedBy { it.filename ?: "" }.map { resource ->
            val raw = resource.inputStream.use { objectMapper.readValue(it, Any::class.java) }
            when (val validation = WorkflowDefinitionValidator.validate(raw)) {
                is WorkflowDefinitionValidation.Valid -> WorkflowDefinitionRecord(
                    workflowType = validation.definition["workflowType"] as String,
                    version = validation.definition["version"] as String,
                    definitionHash = hashWorkflowDefinition(validation.definition),
                    definition = validation.definition,
                    executionPolicy = executionPolicyOf(validation.definition),
                )
                is WorkflowDefinitionValidation.Invalid -> throw IllegalArgumentException(
                    "Bundled session definition ${resource.filename} is invalid: " +
                        "${validation.error.code} at ${validation.error.path}",
                )
            }
        }
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

    /** Registers every bundled definition missing from [scope]; returns the seeded `type@version` keys. */
    @Transactional
    fun seed(scope: TenantScope): List<String> {
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
