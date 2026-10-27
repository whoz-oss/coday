package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Turns a proposal into a frozen [ConfigChangePlan], or explains why it cannot.
 *
 * Validation rejects three things, none of them a right:
 * - what the REST API would refuse anyway: missing name, immutable field, unknown target, prompt
 *   attached after its creation;
 * - what would make the plan ambiguous: an unknown or cyclic `$op` reference, a secret written by
 *   the LLM;
 * - what the tool could not show or apply faithfully: a scope outside the current namespace (reads
 *   and statuses are bounded to it), a personal scope for agents and skills (they have none, and the
 *   REST API would silently ignore it), a secret outside a credential field, a path through a list.
 *
 * Rights are checked at apply time by the REST layer, with the identity of the user who clicks.
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
        return when {
            rawOperations.isEmpty() -> PlanningResult.Invalid(listOf("operations: at least one operation is required."))
            rawOperations.size > ConfigAgentLimits.MAX_OPERATIONS ->
                PlanningResult.Invalid(listOf("operations: at most ${ConfigAgentLimits.MAX_OPERATIONS} operations per proposal, got ${rawOperations.size}."))
            else -> planOperations(rawOperations, input.summary, context)
        }
    }

    private fun planOperations(
        rawOperations: List<OperationInput>,
        summary: String?,
        context: ProposalContext,
    ): PlanningResult {
        val refs = rawOperations.mapIndexed { index, op -> op.ref?.trim()?.takeIf { it.isNotEmpty() } ?: "op${index + 1}" }
        val errors = mutableListOf<String>()
        refs.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { errors += "ref '$it': refs must be unique." }
        val planned =
            rawOperations.mapIndexedNotNull { index, op ->
                val ref = refs[index]
                val opErrors = mutableListOf<String>()
                planOperation(ref, op, refs.toSet(), context, opErrors).also { errors += opErrors.map { "$ref: $it" } }
            }
        val order = orderOperations(planned.withIndex().toList())
        return when {
            errors.isNotEmpty() -> PlanningResult.Invalid(errors)
            order == null -> PlanningResult.Invalid(listOf("operations: the {\"\$op\": ...} references form a cycle."))
            else -> PlanningResult.Planned(freeze(planned, order, summary, context))
        }
    }

    private fun freeze(
        planned: List<PlannedOperation>,
        order: List<String>,
        summary: String?,
        context: ProposalContext,
    ): ConfigChangePlan {
        val plan =
            ConfigChangePlan(
                questionId = context.questionId,
                caseId = context.caseId,
                namespaceId = context.namespaceId,
                recipientUserId = context.callerId,
                agentName = context.agentName,
                summary = summary?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_SUMMARY,
                createdAt = context.now,
                expiresAt = context.now.plus(ConfigAgentLimits.PROPOSAL_TTL),
                order = order,
                operations = planned,
                warnings = warnings(planned, context),
            )
        return plan.copy(planHash = planHash(plan))
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
        return when {
            kind == null || action == null -> null
            action !in allowedActions(kind) -> {
                errors += "$action is not supported for $kind (allowed: ${allowedActions(kind).joinToString()})."
                null
            }
            else -> planSupported(ref, kind, action, op, allRefs, context, errors)
        }
    }

    private fun planSupported(
        ref: String,
        kind: ConfigKind,
        action: ConfigAction,
        op: OperationInput,
        allRefs: Set<String>,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedOperation? {
        val secrets = secretSlots(kind, op.secrets, errors)
        if (ConfigJson.containsMask(op.value) || op.changes.orEmpty().any { ConfigJson.containsMask(it.value) }) {
            errors += "'${ConfigJson.MASK}' is a masked secret, never a value: declare the path in 'secrets' so the user types it."
        }
        return when (action) {
            ConfigAction.CREATE -> planCreate(ref, kind, op, secrets, allRefs, context, errors)
            ConfigAction.UPDATE -> planUpdate(ref, kind, op, secrets, allRefs, context, errors)
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
    ): PlannedOperation? =
        when (val value = op.value) {
            is ObjectNode -> planCreateOf(ref, kind, value, op, secrets, allRefs, context, errors)
            else -> {
                errors += "CREATE needs 'value', an object with the fields of the new $kind."
                null
            }
        }

    private fun planCreateOf(
        ref: String,
        kind: ConfigKind,
        value: ObjectNode,
        op: OperationInput,
        secrets: List<SecretSlot>,
        allRefs: Set<String>,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedOperation? {
        val name = value.path("name").asText("").trim()
        if (name.isEmpty()) errors += "CREATE needs 'value.name'."
        listOf("id", "namespaceId", "userId").filter { value.has(it) }.forEach {
            errors += "'value.$it' is not allowed: the scope goes in 'scope' and the id is assigned by the server."
        }
        val scope = scopeOf(kind, op.scope, context, errors)
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
        return scope?.let {
            PlannedOperation(
                ref = ref,
                kind = kind,
                action = ConfigAction.CREATE,
                scope = it,
                value = value,
                secrets = secrets,
                deployTo = deployTo,
                dependsOn = dependsOn.toList().sorted(),
                affects = affects(it.namespaceId, it.userId),
            )
        }
    }

    private fun planUpdate(
        ref: String,
        kind: ConfigKind,
        op: OperationInput,
        secrets: List<SecretSlot>,
        allRefs: Set<String>,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedOperation? {
        val current = targetEntity(kind, op.id, context, errors)
        val changes = op.changes.orEmpty()
        return when {
            current == null -> null
            changes.isEmpty() && secrets.isEmpty() -> {
                errors += "UPDATE needs 'changes' (or 'secrets')."
                null
            }
            else -> planUpdateOf(ref, kind, current, changes, secrets, allRefs, errors)
        }
    }

    private fun planUpdateOf(
        ref: String,
        kind: ConfigKind,
        current: ConfigEntity,
        changes: List<ChangeInput>,
        secrets: List<SecretSlot>,
        allRefs: Set<String>,
        errors: MutableList<String>,
    ): PlannedOperation {
        val target = current.toTarget()
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
                    listPrefix(current.dto, path) != null -> {
                        errors += "'$path' goes through a list: replace the whole list with path '${listPrefix(current.dto, path)}'."
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
        val dependsOn = planned.flatMap { ConfigJson.opRefs(it.after) }.toSet()
        (dependsOn - allRefs).forEach { errors += "unknown reference {\"\$op\": \"$it\"}." }
        if (ref in dependsOn) errors += "an operation cannot reference itself."
        return PlannedOperation(
            ref = ref,
            kind = kind,
            action = ConfigAction.UPDATE,
            target = target,
            changes = planned,
            secrets = secrets,
            dependsOn = dependsOn.toList().sorted(),
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
    ): PlannedOperation? =
        targetEntity(kind, op.id, context, errors)?.toTarget()?.let { target ->
            val scopeNamespace = if (action == ConfigAction.DEPLOY || action == ConfigAction.UNDEPLOY) context.namespaceId else target.namespaceId
            PlannedOperation(
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
        return if (uuid == null) {
            errors += "'id' must be the id of an existing ${kind.name.lowercase()}, as returned by ${ConfigAgentIntegration.READ_TOOL_NAME}."
            null
        } else {
            configReadService.find(kind, uuid, context.callerId, context.namespaceId).also {
                if (it == null) errors += "no ${kind.name.lowercase()} with id $uuid is visible to the user."
            }
        }
    }

    private fun ConfigEntity.toTarget() = PlannedTarget(id = id, name = name, namespaceId = namespaceId, userId = userId)

    private fun scopeOf(
        kind: ConfigKind,
        scope: ScopeInput?,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedScope? =
        if (scope == null) {
            errors += "CREATE needs 'scope': {\"namespaceId\": \"current\" or null for platform, \"userId\": \"me\" or null}."
            null
        } else {
            scopeFrom(kind, scope, context, errors)
        }

    private fun scopeFrom(
        kind: ConfigKind,
        scope: ScopeInput,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedScope {
        val namespaceId =
            scope.namespaceId?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { raw ->
                val id = if (raw.equals("current", ignoreCase = true)) context.namespaceId else runCatching { UUID.fromString(raw) }.getOrNull()
                if (id != context.namespaceId) {
                    errors += "scope.namespaceId must be this namespace (\"current\" or ${context.namespaceId}) or null for platform."
                }
                id
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
        if (userId != null && kind in SHARED_ONLY_KINDS) {
            errors += "${kind.name.lowercase()}s have no personal scope: scope.userId must be null."
        }
        return PlannedScope(namespaceId = namespaceId, userId = userId)
    }

    /**
     * Secrets may only target fields that hold one: the credential fields of an auth setting and the
     * parameters of an integration. Anything else would store what the user types as a password in a
     * plain field (instructions, name) or move the entity (namespaceId).
     */
    private fun secretSlots(
        kind: ConfigKind,
        secrets: List<SecretInput>?,
        errors: MutableList<String>,
    ): List<SecretSlot> =
        secrets.orEmpty().mapNotNull { secret ->
            val path = secret.path?.trim().orEmpty()
            when {
                !isValidPath(path) -> {
                    errors += "secret path '$path' must be a JSON Pointer like '/apiKey' or '/parameters/token'."
                    null
                }
                !isSecretField(kind, path) -> {
                    errors += "'$path' cannot hold a secret for a ${kind.name.lowercase()}: ${secretFieldsHint(kind)}."
                    null
                }
                else -> SecretSlot(path = path, label = secret.label?.trim()?.takeIf { it.isNotEmpty() } ?: path.removePrefix("/"))
            }
        }

    private fun isSecretField(
        kind: ConfigKind,
        path: String,
    ): Boolean =
        when (kind) {
            ConfigKind.AUTH_SETTING -> path in AUTH_SETTING_SECRET_PATHS
            ConfigKind.INTEGRATION -> path.startsWith("/parameters/")
            else -> false
        }

    private fun secretFieldsHint(kind: ConfigKind): String =
        when (kind) {
            ConfigKind.AUTH_SETTING -> "use one of ${AUTH_SETTING_SECRET_PATHS.joinToString()}"
            ConfigKind.INTEGRATION -> "use a path under /parameters/"
            else -> "this kind has no secret field"
        }

    /** The first list on [path] in [dto], as a JSON Pointer, or null when the path only goes through objects. */
    private fun listPrefix(
        dto: JsonNode,
        path: String,
    ): String? {
        val segments = ConfigJson.segments(path)
        return (1 until segments.size)
            .map { length -> ConfigJson.pointerOf(segments.take(length)) }
            .firstOrNull { prefix -> ConfigJson.valueAt(dto, prefix)?.isArray == true }
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
    private tailrec fun orderOperations(
        remaining: List<IndexedValue<PlannedOperation>>,
        done: List<String> = emptyList(),
    ): List<String>? {
        val next =
            remaining
                .filter { (_, op) -> op.dependsOn.all { it in done } }
                .minWithOrNull(compareBy({ rank(it.value) }, { it.index }))
        return when {
            remaining.isEmpty() -> done
            next == null -> null
            else -> orderOperations(remaining - next, done + next.value.ref)
        }
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

        private const val DEFAULT_SUMMARY = "Configuration change"

        /** Kinds that only exist at platform or namespace level. */
        private val SHARED_ONLY_KINDS = setOf(ConfigKind.AGENT, ConfigKind.SKILL)

        private val AUTH_SETTING_SECRET_PATHS = setOf("/apiKey", "/token", "/password", "/clientSecret")
    }
}
