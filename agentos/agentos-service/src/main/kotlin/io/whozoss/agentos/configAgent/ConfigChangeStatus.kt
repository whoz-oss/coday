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
     * `{"$op": ref}` can be compared; [deployed] is the set of agents deployed on the plan's namespace.
     */
    fun evaluate(
        op: PlannedOperation,
        plan: ConfigChangePlan,
        viewerId: UUID,
        createdIds: Map<String, UUID>,
        deployed: Set<UUID>,
    ): OperationEvaluation =
        when (op.action) {
            ConfigAction.CREATE -> evaluateCreate(op, plan, viewerId, createdIds, deployed)
            ConfigAction.UPDATE -> evaluateUpdate(op, plan, viewerId, createdIds)
            ConfigAction.ENABLE, ConfigAction.DISABLE -> evaluateEnabled(op, plan, viewerId)
            ConfigAction.DEPLOY, ConfigAction.UNDEPLOY -> evaluateDeployment(op, deployed)
        }

    private fun evaluateCreate(
        op: PlannedOperation,
        plan: ConfigChangePlan,
        viewerId: UUID,
        createdIds: Map<String, UUID>,
        deployed: Set<UUID>,
    ): OperationEvaluation {
        val name = op.value?.path("name")?.asText().orEmpty()
        val existing = configReadService.findByName(op.kind, name, op.scope?.namespaceId, op.scope?.userId, viewerId, plan.namespaceId)
        val secretFields = op.secrets.map { ConfigJson.segments(it.path).first() }.toSet()
        return when {
            existing == null -> OperationEvaluation(ConfigOperationStatus.PENDING)
            !matches(op.value as? ObjectNode, existing, secretFields, createdIds) ->
                OperationEvaluation(ConfigOperationStatus.CONFLICT, existing.id, existing, "an entity named '$name' already exists with other values")
            op.kind == ConfigKind.AGENT && op.deployTo == ConfigDeployTarget.NAMESPACE && existing.id !in deployed ->
                OperationEvaluation(ConfigOperationStatus.PENDING, existing.id, existing)
            else -> OperationEvaluation(ConfigOperationStatus.ALREADY_APPLIED, existing.id, existing)
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
        deployed: Set<UUID>,
    ): OperationEvaluation {
        val target = op.target!!
        val done = (target.id in deployed) == (op.action == ConfigAction.DEPLOY)
        return OperationEvaluation(if (done) ConfigOperationStatus.APPLIED else ConfigOperationStatus.PENDING, target.id)
    }

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
