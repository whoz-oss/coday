package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** What the LLM sends to `AGENTOS_CONFIG__propose`. Every field is optional so a malformed call gets a precise error. */
data class ProposeInput(
    val summary: String? = null,
    val operations: List<OperationInput>? = null,
)

data class OperationInput(
    val ref: String? = null,
    val kind: String? = null,
    val action: String? = null,
    val id: String? = null,
    val scope: ScopeInput? = null,
    val value: JsonNode? = null,
    val changes: List<ChangeInput>? = null,
    val secrets: List<SecretInput>? = null,
    val deployTo: String? = null,
)

data class ScopeInput(
    val namespaceId: String? = null,
    val userId: String? = null,
)

data class ChangeInput(
    val path: String? = null,
    val value: JsonNode? = null,
    val remove: Boolean? = null,
)

data class SecretInput(
    val path: String? = null,
    val label: String? = null,
)

/** Who proposes, for whom, and where the question will be asked. */
data class ProposalContext(
    val callerId: UUID,
    val namespaceId: UUID,
    val caseId: UUID,
    val agentName: String,
    val questionId: UUID,
    val now: Instant,
)

sealed interface PlanningResult {
    data class Planned(
        val plan: ConfigChangePlan,
    ) : PlanningResult

    /** One message per offending operation (prefixed by its ref), so the LLM can fix its call. */
    data class Invalid(
        val errors: List<String>,
    ) : PlanningResult
}

/**
 * Turns a proposal into a frozen [ConfigChangePlan], or explains why it cannot.
 *
 * Validation only rejects what the REST API would refuse anyway (missing name, immutable field,
 * unknown target, prompt attached after creation...) or what would make the plan ambiguous
 * (unknown or cyclic `$op` reference, secret written by the LLM). It never restricts what the user
 * may do: rights are checked at apply time by the REST layer, with the identity of who clicks.
 */
@Service
class ConfigPlanner(
    private val configReadService: ConfigReadService,
    private val objectMapper: ObjectMapper,
) {
    fun plan(
        input: ProposeInput,
        context: ProposalContext,
    ): PlanningResult {
        val rawOperations = input.operations.orEmpty()
        if (rawOperations.isEmpty()) return PlanningResult.Invalid(listOf("operations: at least one operation is required."))
        if (rawOperations.size > ConfigAgentLimits.MAX_OPERATIONS) {
            return PlanningResult.Invalid(listOf("operations: at most ${ConfigAgentLimits.MAX_OPERATIONS} operations per proposal, got ${rawOperations.size}."))
        }

        val refs = rawOperations.mapIndexed { index, op -> op.ref?.trim()?.takeIf { it.isNotEmpty() } ?: "op${index + 1}" }
        val errors = mutableListOf<String>()
        refs.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { errors += "ref '$it': refs must be unique." }

        val planned =
            rawOperations.mapIndexedNotNull { index, op ->
                val ref = refs[index]
                val opErrors = mutableListOf<String>()
                val result = planOperation(ref, op, refs.toSet(), context, opErrors)
                errors += opErrors.map { "$ref: $it" }
                result
            }
        if (errors.isNotEmpty()) return PlanningResult.Invalid(errors)

        val order =
            orderOperations(planned) ?: return PlanningResult.Invalid(listOf("operations: the {\"\$op\": ...} references form a cycle."))
        val warnings = warnings(planned, context)
        val plan =
            ConfigChangePlan(
                questionId = context.questionId,
                caseId = context.caseId,
                namespaceId = context.namespaceId,
                recipientUserId = context.callerId,
                agentName = context.agentName,
                summary = input.summary?.trim()?.takeIf { it.isNotEmpty() } ?: "Configuration change",
                createdAt = context.now,
                expiresAt = context.now.plus(ConfigAgentLimits.PROPOSAL_TTL),
                order = order,
                operations = planned,
                warnings = warnings,
            )
        return PlanningResult.Planned(plan.copy(planHash = planHash(plan)))
    }

    /** Hash of the plan without its own `planHash` field. */
    fun planHash(plan: ConfigChangePlan): String = planHash(objectMapper.valueToTree<JsonNode>(plan))

    fun planHash(planJson: JsonNode): String {
        val copy = planJson.deepCopy<JsonNode>()
        (copy as? ObjectNode)?.remove("planHash")
        return ConfigJson.hash(copy)
    }

    private fun planOperation(
        ref: String,
        op: OperationInput,
        allRefs: Set<String>,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedOperation? {
        val kind = enumOrNull<ConfigKind>(op.kind)
        val action = enumOrNull<ConfigAction>(op.action)
        if (kind == null) errors += "kind must be one of ${ConfigKind.entries.joinToString()}."
        if (action == null) errors += "action must be one of ${ConfigAction.entries.joinToString()}."
        if (kind == null || action == null) return null
        if (action !in allowedActions(kind)) {
            errors += "$action is not supported for $kind (allowed: ${allowedActions(kind).joinToString()})."
            return null
        }
        val secrets = secretSlots(op.secrets, errors)
        if (ConfigJson.containsMask(op.value) || op.changes.orEmpty().any { ConfigJson.containsMask(it.value) }) {
            errors += "'${ConfigJson.MASK}' is a masked secret, never a value: declare the path in 'secrets' so the user types it."
        }
        return when (action) {
            ConfigAction.CREATE -> planCreate(ref, kind, op, secrets, allRefs, context, errors)
            ConfigAction.UPDATE -> planUpdate(ref, kind, op, secrets, context, errors)
            else -> planTargetOnly(ref, kind, action, op, context, errors)
        }
    }

    private fun planCreate(
        ref: String,
        kind: ConfigKind,
        op: OperationInput,
        secrets: List<SecretSlot>,
        allRefs: Set<String>,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedOperation? {
        val value = op.value as? ObjectNode
        if (value == null) {
            errors += "CREATE needs 'value', an object with the fields of the new $kind."
            return null
        }
        val name = value.path("name").asText("").trim()
        if (name.isEmpty()) errors += "CREATE needs 'value.name'."
        listOf("id", "namespaceId", "userId").filter { value.has(it) }.forEach {
            errors += "'value.$it' is not allowed: the scope goes in 'scope' and the id is assigned by the server."
        }
        val scope = scopeOf(op.scope, context, errors)
        secrets.forEach { slot ->
            ConfigJson.valueAt(value, slot.path)?.takeUnless { it.isNull }?.let {
                errors += "secret path '${slot.path}' must not carry a value in 'value'."
            }
        }
        val dependsOn = ConfigJson.opRefs(value)
        (dependsOn - allRefs).forEach { errors += "unknown reference {\"\$op\": \"$it\"}." }
        if (ref in dependsOn) errors += "an operation cannot reference itself."
        val deployTo =
            if (kind == ConfigKind.AGENT) {
                enumOrNull<ConfigDeployTarget>(op.deployTo).also {
                    if (it == null) errors += "creating an agent needs 'deployTo': NAMESPACE (members can use it) or NONE."
                }
            } else {
                null
            }
        if (scope == null) return null
        return PlannedOperation(
            ref = ref,
            kind = kind,
            action = ConfigAction.CREATE,
            scope = scope,
            value = value,
            secrets = secrets,
            deployTo = deployTo,
            dependsOn = dependsOn.toList().sorted(),
            affects = affects(scope.namespaceId, scope.userId),
        )
    }

    private fun planUpdate(
        ref: String,
        kind: ConfigKind,
        op: OperationInput,
        secrets: List<SecretSlot>,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedOperation? {
        val current = targetEntity(kind, op.id, context, errors) ?: return null
        val target = current.toTarget()
        val changes = op.changes.orEmpty()
        if (changes.isEmpty() && secrets.isEmpty()) {
            errors += "UPDATE needs 'changes' (or 'secrets')."
            return null
        }
        val masked = configReadService.masked(current)
        val secretPaths = secrets.map { it.path }.toSet()
        val planned =
            changes.mapNotNull { change ->
                val path = change.path?.trim().orEmpty()
                when {
                    !isValidPath(path) -> {
                        errors += "change path '$path' must be a JSON Pointer of 1 to $MAX_PATH_DEPTH segments, like '/instructions' or '/integrations/MY_API'."
                        null
                    }
                    ConfigJson.segments(path).first() in immutableFields(kind) -> {
                        errors += "'$path' cannot be changed after creation${immutableHint(kind, path)}."
                        null
                    }
                    path in secretPaths -> {
                        errors += "'$path' is declared as a secret: remove it from 'changes'."
                        null
                    }
                    else ->
                        PlannedChange(
                            path = path,
                            after = if (change.remove == true) null else change.value ?: objectMapper.nullNode(),
                            remove = change.remove == true,
                            beforeHash = ConfigJson.hash(ConfigJson.valueAt(masked, path)),
                        )
                }
            }
        if (current.fromFilesystem) errors += "${kind.name.lowercase()} '${current.name}' is defined in a file and cannot be changed here."
        return PlannedOperation(
            ref = ref,
            kind = kind,
            action = ConfigAction.UPDATE,
            target = target,
            changes = planned,
            secrets = secrets,
            affects = affects(target.namespaceId, target.userId),
        )
    }

    private fun planTargetOnly(
        ref: String,
        kind: ConfigKind,
        action: ConfigAction,
        op: OperationInput,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedOperation? {
        val target = targetEntity(kind, op.id, context, errors)?.toTarget() ?: return null
        val scopeNamespace = if (action == ConfigAction.DEPLOY || action == ConfigAction.UNDEPLOY) context.namespaceId else target.namespaceId
        return PlannedOperation(
            ref = ref,
            kind = kind,
            action = action,
            target = target,
            affects = affects(scopeNamespace, target.userId),
        )
    }

    private fun targetEntity(
        kind: ConfigKind,
        id: String?,
        context: ProposalContext,
        errors: MutableList<String>,
    ): ConfigEntity? {
        val uuid = id?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
        if (uuid == null) {
            errors += "'id' must be the id of an existing ${kind.name.lowercase()}, as returned by ${ConfigAgentIntegration.READ_TOOL_NAME}."
            return null
        }
        return configReadService.find(kind, uuid, context.callerId, context.namespaceId).also {
            if (it == null) errors += "no ${kind.name.lowercase()} with id $uuid is visible to the user."
        }
    }

    private fun ConfigEntity.toTarget() = PlannedTarget(id = id, name = name, namespaceId = namespaceId, userId = userId)

    private fun scopeOf(
        scope: ScopeInput?,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedScope? {
        if (scope == null) {
            errors += "CREATE needs 'scope': {\"namespaceId\": \"${context.namespaceId}\" or null for platform, \"userId\": \"me\" or null}."
            return null
        }
        val namespaceId =
            scope.namespaceId?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { raw ->
                if (raw.equals("current", ignoreCase = true)) {
                    context.namespaceId
                } else {
                    runCatching { UUID.fromString(raw) }.getOrNull().also {
                        if (it == null) errors += "scope.namespaceId must be a namespace id, \"current\" or null."
                    }
                }
            }
        val userId =
            scope.userId?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { raw ->
                if (raw.equals("me", ignoreCase = true) || raw == context.callerId.toString()) {
                    context.callerId
                } else {
                    errors += "scope.userId must be \"me\" or null: an entity can only be created for the user who applies the plan."
                    null
                }
            }
        return PlannedScope(namespaceId = namespaceId, userId = userId)
    }

    private fun secretSlots(
        secrets: List<SecretInput>?,
        errors: MutableList<String>,
    ): List<SecretSlot> =
        secrets.orEmpty().mapNotNull { secret ->
            val path = secret.path?.trim().orEmpty()
            if (!isValidPath(path)) {
                errors += "secret path '$path' must be a JSON Pointer like '/apiKey' or '/parameters/token'."
                null
            } else {
                SecretSlot(path = path, label = secret.label?.trim()?.takeIf { it.isNotEmpty() } ?: path.removePrefix("/"))
            }
        }

    private fun warnings(
        operations: List<PlannedOperation>,
        context: ProposalContext,
    ): List<String> {
        val deployed = configReadService.deployedAgentIds(context.namespaceId)
        return operations.mapNotNull { op ->
            when {
                op.kind == ConfigKind.AGENT && op.action == ConfigAction.CREATE && op.deployTo == ConfigDeployTarget.NONE ->
                    "${op.ref}: the new agent is not deployed, so members of the namespace will not see it."
                op.kind == ConfigKind.AGENT && op.action == ConfigAction.ENABLE && op.target != null && op.target.id !in deployed ->
                    "${op.ref}: '${op.target.name}' is not deployed on this namespace, so members will not see it."
                else -> null
            }
        }
    }

    /** Topological order of the `$op` dependencies, ties broken by a fixed rank then by plan order; null on a cycle. */
    private fun orderOperations(operations: List<PlannedOperation>): List<String>? {
        val remaining = operations.withIndex().toMutableList()
        val done = linkedSetOf<String>()
        while (remaining.isNotEmpty()) {
            val next =
                remaining
                    .filter { (_, op) -> op.dependsOn.all { it in done } }
                    .minWithOrNull(compareBy({ rank(it.value) }, { it.index }))
                    ?: return null
            done += next.value.ref
            remaining.remove(next)
        }
        return done.toList()
    }

    private fun rank(op: PlannedOperation): Int =
        when (op.kind) {
            ConfigKind.AUTH_SETTING -> 0
            ConfigKind.INTEGRATION -> 1
            ConfigKind.SKILL -> 2
            ConfigKind.AGENT ->
                when (op.action) {
                    ConfigAction.CREATE -> 3
                    ConfigAction.UPDATE -> 4
                    ConfigAction.ENABLE, ConfigAction.DISABLE -> 6
                    ConfigAction.DEPLOY, ConfigAction.UNDEPLOY -> 7
                }
            ConfigKind.PROMPT -> 5
        }

    private fun allowedActions(kind: ConfigKind): Set<ConfigAction> =
        when (kind) {
            ConfigKind.AGENT -> ConfigAction.entries.toSet()
            else -> setOf(ConfigAction.CREATE, ConfigAction.UPDATE)
        }

    /** Fields the REST API refuses to change on PUT, so a plan never fails on them at apply time. */
    private fun immutableFields(kind: ConfigKind): Set<String> =
        setOf("id", "namespaceId", "userId") +
            when (kind) {
                ConfigKind.PROMPT -> setOf("agentConfigId")
                ConfigKind.INTEGRATION -> setOf("integrationType")
                ConfigKind.AUTH_SETTING -> setOf("authType")
                else -> emptySet()
            }

    private fun immutableHint(
        kind: ConfigKind,
        path: String,
    ): String =
        if (kind == ConfigKind.PROMPT && path.startsWith("/agentConfigId")) {
            " (a prompt is attached to an agent when it is created: create a new prompt instead)"
        } else {
            ""
        }

    private fun isValidPath(path: String): Boolean =
        path.startsWith("/") && path.length > 1 && runCatching { ConfigJson.segments(path) }.getOrNull()?.let { segments ->
            segments.size in 1..MAX_PATH_DEPTH && segments.none { it.isEmpty() }
        } == true

    private fun affects(
        namespaceId: UUID?,
        userId: UUID?,
    ): ConfigAffects =
        when {
            userId != null -> ConfigAffects.CALLER_ONLY
            namespaceId == null -> ConfigAffects.PLATFORM
            else -> ConfigAffects.NAMESPACE_MEMBERS
        }

    private inline fun <reified E : Enum<E>> enumOrNull(raw: String?): E? =
        raw?.trim()?.uppercase()?.replace('-', '_')?.let { value -> enumValues<E>().firstOrNull { it.name == value } }

    companion object {
        const val MAX_PATH_DEPTH = 3
    }
}
