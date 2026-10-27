package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
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
        val secretFields = op.secrets.map { ConfigJson.segments(it.path).first() }.toSet()
        return when {
            existing == null -> OperationEvaluation(ConfigOperationStatus.PENDING)
            !matches(op.value as? ObjectNode, existing, secretFields, createdIds) ->
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

    /** Whether every proposed field, secrets and unresolved references aside, matches what is stored. */
    private fun matches(
        value: ObjectNode?,
        existing: ConfigEntity,
        secretFields: Set<String>,
        createdIds: Map<String, UUID>,
    ): Boolean =
        value?.properties().orEmpty().all { (field, proposed) ->
            field in secretFields ||
                ConfigJson.opRefs(proposed).any { it !in createdIds } ||
                sameValue(existing.dto.get(field), ConfigJson.resolveOpRefs(proposed, createdIds))
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

    /** Compared with the unmasked value, so a change of an integration parameter is seen as applied. */
    private fun isApplied(
        entity: ConfigEntity,
        change: PlannedChange,
        createdIds: Map<String, UUID>,
    ): Boolean {
        val current = ConfigJson.valueAt(entity.dto, change.path)
        return when {
            change.remove -> current == null
            ConfigJson.opRefs(change.after).all { it in createdIds } -> current == ConfigJson.resolveOpRefs(change.after, createdIds)
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

    /**
     * Whether a proposed field matches what the server stores. A field the server omits or nulls on
     * output (a deprecated or defaulted one) does not count as a difference, and strings are compared
     * trimmed, since the REST layer normalizes some of them.
     */
    private fun sameValue(
        existing: JsonNode?,
        proposed: JsonNode?,
    ): Boolean = existing == null || existing.isNull || normalized(existing) == normalized(proposed)

    private fun normalized(node: JsonNode?): JsonNode? =
        when (node) {
            is TextNode -> TextNode(node.textValue().trim())
            is ArrayNode -> JsonNodeFactory.instance.arrayNode().also { copy -> node.forEach { copy.add(normalized(it)) } }
            is ObjectNode ->
                JsonNodeFactory.instance.objectNode().also { copy ->
                    node.properties().forEach { (key, value) -> copy.set<JsonNode>(key, normalized(value)) }
                }
            else -> node
        }
}
