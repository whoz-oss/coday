package io.whozoss.factory.workflow.service

import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowPolicyDefinition
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.domain.workflowException
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import org.springframework.stereotype.Component

/**
 * The single place that computes the definition a run ACTUALLY follows: the pinned base definition
 * plus the steps the run appended through an accepted continuation (if any).
 *
 * The catalog never changes: the appended steps live on the run (`instance.expansion`) and are
 * integrity-checked against `effectiveDefinitionHash` on every resolution. Without an expansion,
 * resolution is exactly the historical registry lookup + base-hash check, so existing runs behave
 * (and hash) as before.
 *
 * The returned policy definition keeps the BASE definition hash: the instance and the transition
 * policy pin the run to the registered identity, never to a derived one.
 */
@Component
class EffectiveWorkflowDefinitionResolver(private val repository: WorkflowRepository) {

    /** The pinned base record; fails when it is absent or its hash drifted. */
    fun baseRecord(scope: TenantScope, instance: WorkflowInstanceRecord): WorkflowDefinitionRecord {
        val record = baseRecordOrNull(scope, instance)
            ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_FOUND)
        if (record.definitionHash != instance.instance["definitionHash"]) {
            throw workflowException(WorkflowErrorCodes.WORKFLOW_DEFINITION_MISMATCH)
        }
        return record
    }

    /** The pinned base record without any identity check (lenient read). */
    fun baseRecordOrNull(scope: TenantScope, instance: WorkflowInstanceRecord): WorkflowDefinitionRecord? {
        val workflowType = instance.instance["workflowType"] as? String
        val version = instance.instance["definitionVersion"] as? String
        return if (workflowType != null && version != null) repository.findDefinition(scope, workflowType, version) else null
    }

    /** Strict resolution: base identity checked, appended steps integrity-checked. */
    fun resolveRecord(scope: TenantScope, instance: WorkflowInstanceRecord): WorkflowDefinitionRecord =
        applyExpansion(baseRecord(scope, instance), instance)

    /**
     * Lenient resolution: `null` when the base definition cannot be found. The appended steps are still
     * integrity-checked and a hash mismatch still throws WORKFLOW_DEFINITION_MISMATCH on purpose: a run whose
     * added steps were altered must fail loudly, never fall back to its base definition.
     */
    fun resolveRecordOrNull(scope: TenantScope, instance: WorkflowInstanceRecord): WorkflowDefinitionRecord? =
        baseRecordOrNull(scope, instance)?.let { applyExpansion(it, instance) }

    fun resolve(scope: TenantScope, instance: WorkflowInstanceRecord): WorkflowPolicyDefinition =
        resolveRecord(scope, instance).toPolicyDefinition()

    /** The accepted expansion recorded on the run, or `null`. */
    fun expansionOf(instance: WorkflowInstanceRecord): Map<String, Any?>? =
        (instance.instance["expansion"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }

    private fun applyExpansion(base: WorkflowDefinitionRecord, instance: WorkflowInstanceRecord): WorkflowDefinitionRecord {
        val expansion = expansionOf(instance) ?: return base
        val added = (expansion["steps"] as? List<*>)?.mapNotNull { raw ->
            (raw as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        } ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_DEFINITION_MISMATCH)
        val baseSteps = (base.definition["steps"] as? List<*>).orEmpty()
        val effective = LinkedHashMap<String, Any?>(base.definition)
        effective["steps"] = baseSteps + added
        if (hashWorkflowDefinition(effective) != expansion["effectiveDefinitionHash"]) {
            throw workflowException(WorkflowErrorCodes.WORKFLOW_DEFINITION_MISMATCH)
        }
        return base.copy(definition = effective)
    }
}
