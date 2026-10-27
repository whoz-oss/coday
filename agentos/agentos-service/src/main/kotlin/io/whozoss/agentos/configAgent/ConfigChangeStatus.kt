package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Tells where each operation of a plan stands against the real state, read with one user's rights.
 *
 * Pure with respect to the plan: recomputed at every view and before every write, so a reload, a
 * closed tab or a second apply never relies on what a previous request remembered.
 */
@Component
class ConfigChangeStatus(
    private val configReadService: ConfigReadService,
) {
    /**
     * [createdIds] maps the refs evaluated so far to the entity they created or matched, so a
     * `{"$op": ref}` can be compared and a namespace the plan creates can be read.
     */
    fun evaluate(
        op: PlannedOperation,
        plan: ConfigChangePlan,
        viewerId: UUID,
        createdIds: Map<String, UUID>,
    ): OperationEvaluation =
        when (op.action) {
            ConfigAction.CREATE -> evaluateCreate(op, plan, viewerId, createdIds)
            ConfigAction.UPDATE -> evaluateUpdate(op, plan, viewerId, createdIds)
            ConfigAction.ENABLE, ConfigAction.DISABLE -> evaluateEnabled(op, plan, viewerId)
            ConfigAction.DEPLOY, ConfigAction.UNDEPLOY -> evaluateDeployment(op, plan, createdIds)
        }

    /**
     * A namespace matched by name and values counts as created, like any other entity, so proposing
     * a plan again converges; the operations that reference it are then read inside it.
     */
    private fun evaluateCreate(
        op: PlannedOperation,
        plan: ConfigChangePlan,
        viewerId: UUID,
        createdIds: Map<String, UUID>,
    ): OperationEvaluation {
        val name = op.value?.path("name")?.asText().orEmpty()
        val existing = findExisting(op, name, plan, viewerId, createdIds)
        return when {
            existing == null -> OperationEvaluation(ConfigOperationStatus.PENDING)
            !matches(op.value as? ObjectNode, existing, op.secrets.map { it.path }.toSet(), createdIds) ->
                OperationEvaluation(ConfigOperationStatus.CONFLICT, existing.id, existing, "an entity named '$name' already exists with other values")
            awaitsDeployment(op, existing.id, plan, createdIds) -> OperationEvaluation(ConfigOperationStatus.PENDING, existing.id, existing)
            else -> OperationEvaluation(ConfigOperationStatus.ALREADY_APPLIED, existing.id, existing)
        }
    }

    /** An agent created with `deployTo: NAMESPACE` that exists but is not deployed yet: its deployment is still to apply. */
    private fun awaitsDeployment(
        op: PlannedOperation,
        agentId: UUID,
        plan: ConfigChangePlan,
        createdIds: Map<String, UUID>,
    ): Boolean = op.kind == ConfigKind.AGENT && op.deployTo == ConfigDeployTarget.NAMESPACE && !isDeployed(agentId, plan.deploymentNamespaceOf(op, createdIds))

    /** The entity a `CREATE` would duplicate, read within the plan's namespace or within the namespace the plan creates. */
    private fun findExisting(
        op: PlannedOperation,
        name: String,
        plan: ConfigChangePlan,
        viewerId: UUID,
        createdIds: Map<String, UUID>,
    ): ConfigEntity? {
        val scope = op.scope
        val namespaceId = scope?.namespaceIn(createdIds)
        return when {
            scope?.namespaceRef == null -> configReadService.findByName(op.kind, name, scope?.namespaceId, scope?.userId, viewerId, plan.namespaceId)
            // The namespace it goes into does not exist yet, so neither can the entity.
            namespaceId == null -> null
            else -> configReadService.findByName(op.kind, name, namespaceId, scope.userId, viewerId, namespaceId)
        }
    }

    /**
     * Whether the stored entity holds every proposed field, secret paths and unresolved references aside.
     * A top-level field the DTO leaves out when null counts as empty.
     */
    private fun matches(
        value: ObjectNode?,
        existing: ConfigEntity,
        secretPaths: Set<String>,
        createdIds: Map<String, UUID>,
    ): Boolean =
        value?.properties().orEmpty().all { (field, proposed) ->
            ConfigJson.opRefs(proposed).any { it !in createdIds } ||
                ConfigValueMatch.holds(existing.dto.get(field), ConfigJson.resolveOpRefs(proposed, createdIds), ConfigJson.pointerOf(listOf(field)), secretPaths)
        }

    private fun evaluateUpdate(
        op: PlannedOperation,
        plan: ConfigChangePlan,
        viewerId: UUID,
        createdIds: Map<String, UUID>,
    ): OperationEvaluation {
        val target = op.target!!
        val entity = configReadService.find(op.kind, target.id, viewerId, plan.namespaceId)
        val masked = entity?.let(configReadService::masked)
        val pending = op.changes.filterNot { change -> entity != null && isApplied(entity, change, createdIds) }
        val conflict = pending.firstOrNull { change -> masked != null && ConfigJson.hash(ConfigJson.valueAt(masked, change.path)) != change.beforeHash }
        return when {
            entity == null -> OperationEvaluation(ConfigOperationStatus.NOT_VISIBLE, reason = "'${target.name}' is not visible to you")
            conflict != null -> OperationEvaluation(ConfigOperationStatus.CONFLICT, entity.id, entity, "${conflict.path} changed since the plan was made")
            pending.isEmpty() && op.secrets.isEmpty() -> OperationEvaluation(ConfigOperationStatus.APPLIED, entity.id, entity)
            else -> OperationEvaluation(ConfigOperationStatus.PENDING, entity.id, entity)
        }
    }

    /**
     * Compared with the unmasked value, so a change of an integration parameter is seen as applied. A
     * removal is applied once the field is empty: absent, or null or blank where the DTO keeps it.
     */
    private fun isApplied(
        entity: ConfigEntity,
        change: PlannedChange,
        createdIds: Map<String, UUID>,
    ): Boolean {
        val current = ConfigJson.valueAt(entity.dto, change.path)
        val after = change.after
        return when {
            change.remove -> ConfigValueMatch.isEmpty(current)
            after == null || after.isNull -> current?.isNull == true
            ConfigJson.opRefs(after).all { it in createdIds } -> ConfigValueMatch.holds(current, ConfigJson.resolveOpRefs(after, createdIds), change.path)
            else -> false
        }
    }

    private fun evaluateEnabled(
        op: PlannedOperation,
        plan: ConfigChangePlan,
        viewerId: UUID,
    ): OperationEvaluation {
        val target = op.target!!
        val entity = configReadService.find(op.kind, target.id, viewerId, plan.namespaceId)
        return when {
            entity == null -> OperationEvaluation(ConfigOperationStatus.NOT_VISIBLE, reason = "'${target.name}' is not visible to you")
            entity.enabled == (op.action == ConfigAction.ENABLE) -> OperationEvaluation(ConfigOperationStatus.APPLIED, entity.id, entity)
            else -> OperationEvaluation(ConfigOperationStatus.PENDING, entity.id, entity)
        }
    }

    private fun evaluateDeployment(
        op: PlannedOperation,
        plan: ConfigChangePlan,
        createdIds: Map<String, UUID>,
    ): OperationEvaluation {
        val target = op.target!!
        val done = isDeployed(target.id, plan.deploymentNamespaceOf(op, createdIds)) == (op.action == ConfigAction.DEPLOY)
        return OperationEvaluation(if (done) ConfigOperationStatus.APPLIED else ConfigOperationStatus.PENDING, target.id)
    }

    /** Nothing is deployed on a namespace that does not exist yet ([namespaceId] null). */
    private fun isDeployed(
        agentId: UUID,
        namespaceId: UUID?,
    ): Boolean = namespaceId != null && agentId in configReadService.deployedAgentIds(namespaceId)
}
