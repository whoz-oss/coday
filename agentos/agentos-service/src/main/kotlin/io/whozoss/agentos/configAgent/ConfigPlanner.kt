package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.agentos.agentConfig.SubAgentSelectorValidation
import io.whozoss.agentos.sdk.api.configChange.ConfigAction
import io.whozoss.agentos.sdk.api.configChange.ConfigAffects
import io.whozoss.agentos.sdk.api.configChange.ConfigDeployTarget
import io.whozoss.agentos.sdk.api.configChange.ConfigKind
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
 * - what the tool could not show or apply faithfully: a scope outside the current namespace, the
 *   namespaces the user administers and a namespace the plan creates (reads and statuses are bounded
 *   to them), a personal scope for agents
 *   and skills (they have none, and the REST API would silently ignore it), a secret outside a
 *   credential field, a path through a list.
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
        val planRefs =
            PlanRefs(
                all = refs.toSet(),
                namespaces = refs.filterIndexed { index, _ -> isCreateOf(rawOperations[index], ConfigKind.NAMESPACE) }.toSet(),
                providers = refs.filterIndexed { index, _ -> isCreateOf(rawOperations[index], ConfigKind.AI_PROVIDER) }.toSet(),
            )
        val errors = mutableListOf<String>()
        refs.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { errors += "ref '$it': refs must be unique." }
        val planned =
            rawOperations
                .mapIndexedNotNull { index, op ->
                    val ref = refs[index]
                    val opErrors = mutableListOf<String>()
                    planOperation(ref, op, planRefs, context, opErrors).also { errors += opErrors.map { "$ref: $it" } }
                }.let(::withModelAffects)
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
        refs: PlanRefs,
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
            else -> planSupported(ref, kind, action, op, refs, context, errors)
        }
    }

    private fun planSupported(
        ref: String,
        kind: ConfigKind,
        action: ConfigAction,
        op: OperationInput,
        refs: PlanRefs,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedOperation? {
        val secrets = secretSlots(kind, op.secrets, errors)
        if (ConfigJson.containsMask(op.value) || op.changes.orEmpty().any { ConfigJson.containsMask(it.value) }) {
            errors += "'${ConfigJson.MASK}' is a masked secret, never a value: declare the path in 'secrets' so the user types it."
        }
        return when (action) {
            ConfigAction.CREATE -> planCreate(ref, kind, op, secrets, refs, context, errors)
            ConfigAction.UPDATE -> planUpdate(ref, kind, op, secrets, refs, context, errors)
            else -> planTargetOnly(ref, kind, action, op, refs, context, errors)
        }
    }

    private fun planCreate(
        ref: String,
        kind: ConfigKind,
        op: OperationInput,
        secrets: List<SecretSlot>,
        refs: PlanRefs,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedOperation? =
        when (val value = op.value) {
            is ObjectNode -> planCreateOf(ref, kind, value, op, secrets, refs, context, errors)
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
        refs: PlanRefs,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedOperation? {
        val source = op.copyFrom?.let { copySource(kind, it, context, errors) }
        // A copy keeps its source's name unless the plan gives another one.
        val effective = source?.takeUnless { value.has("name") }?.let { value.deepCopy().put("name", it.name) } ?: value
        val name = ConfigJson.nameOf(kind, effective)
        if (name.isEmpty()) errors += if (kind == ConfigKind.AI_MODEL) "CREATE needs 'value.apiModelName'." else "CREATE needs 'value.name'."
        if (kind == ConfigKind.AI_MODEL) errors += aiProviderIdErrors(effective, refs, context)
        if (kind == ConfigKind.AGENT) {
            if (effective.path("modelName").asText("").isBlank()) {
                errors += "creating an agent needs 'value.modelName': the model alias or name it runs on, e.g. BIG."
            }
            if (effective.get("enabled")?.isBoolean != true) {
                errors += "creating an agent needs 'value.enabled': true (it can run) or false (it stays a draft nobody can use)."
            }
            SubAgentSelectorValidation.idSelectors(selectorsOf(effective.get(SUB_AGENTS_FIELD)))
                .takeIf { it.isNotEmpty() }
                ?.let { errors += SubAgentSelectorValidation.correction(it) }
        }
        listOf("id", "namespaceId", "userId").filter { effective.has(it) }.forEach { field ->
            when {
                // A null is harmless: the server resolves a model's scope from its provider anyway.
                kind == ConfigKind.AI_MODEL && field != "id" ->
                    if (!effective.get(field).isNull) errors += "'value.$field' is not allowed: $MODEL_SCOPE_FROM_PROVIDER"
                else -> errors += "'value.$field' is not allowed: the scope goes in 'scope' and the id is assigned by the server."
            }
        }
        val scope = scopeOf(kind, op.scope, refs, context, errors)
        secrets.forEach { slot ->
            ConfigJson.valueAt(effective, slot.path)?.takeUnless { it.isNull }?.let {
                errors += "secret path '${slot.path}' must not carry a value in 'value'."
            }
        }
        val authType = effective.path(ConfigAuthSettingTypes.TYPE_FIELD).asText(null)
        if (kind == ConfigKind.AUTH_SETTING) errors += ConfigSecretPolicy.misplacedAuthSettingSecrets(authType, secrets)
        errors += ConfigSecretPolicy.clearTextSecrets(kind, authType, effective, path = "").map(ConfigSecretPolicy::clearTextSecretError)
        val dependsOn = ConfigJson.opRefs(effective) + listOfNotNull(scope?.namespaceRef)
        (dependsOn - refs.all).forEach { errors += "unknown reference {\"\$op\": \"$it\"}." }
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
                value = effective,
                secrets = secrets,
                deployTo = deployTo,
                copyFrom = source,
                dependsOn = dependsOn.toList().sorted(),
                affects =
                    if (kind == ConfigKind.AI_MODEL) {
                        modelAffects(effective, context)
                    } else {
                        affects(namespaced = it.namespaceId != null || it.namespaceRef != null, userId = it.userId)
                    },
            )
        }
    }

    /** What a `CREATE` of an AI model must say about its provider, which gives it its scope. */
    private fun aiProviderIdErrors(
        value: JsonNode,
        refs: PlanRefs,
        context: ProposalContext,
    ): List<String> {
        val node = value.get(AI_PROVIDER_ID_FIELD)
        val ref = node?.let(ConfigJson::opRefOf)
        val id = node?.takeIf { it.isTextual }?.textValue()?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
        return when {
            node == null || node.isNull ->
                listOf("creating an AI model needs 'value.$AI_PROVIDER_ID_FIELD': the id of its AI provider, or {\"\$op\": \"<ref>\"} of a provider this plan creates.")
            // Reported once, as an unknown reference, by the caller.
            ref != null -> if (ref in refs.all && ref !in refs.providers) listOf("value.$AI_PROVIDER_ID_FIELD {\"\$op\": \"$ref\"} must name an operation of this plan that creates an AI_PROVIDER.") else emptyList()
            id == null -> listOf("'value.$AI_PROVIDER_ID_FIELD' must be the id of an AI provider, or {\"\$op\": \"<ref>\"} of a provider this plan creates.")
            configReadService.find(ConfigKind.AI_PROVIDER, id, context.callerId, context.namespaceId) == null ->
                listOf("no ai_provider with id $id is visible to the user.")
            else -> emptyList()
        }
    }

    /** Who a new AI model concerns: whoever its provider concerns (an existing one is read, one created by the plan is corrected afterwards). */
    private fun modelAffects(
        value: JsonNode,
        context: ProposalContext,
    ): ConfigAffects {
        val provider =
            value
                .get(AI_PROVIDER_ID_FIELD)
                ?.takeIf { it.isTextual }
                ?.textValue()
                ?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
                ?.let { configReadService.find(ConfigKind.AI_PROVIDER, it, context.callerId, context.namespaceId) }
        return provider?.let { affects(namespaced = it.namespaceId != null, userId = it.userId) } ?: ConfigAffects.NAMESPACE_MEMBERS
    }

    /** A model created from a provider the same plan creates concerns the same people as that provider. */
    private fun withModelAffects(planned: List<PlannedOperation>): List<PlannedOperation> {
        val byRef = planned.associateBy { it.ref }
        return planned.map { op ->
            val providerRef =
                op
                    .takeIf { it.kind == ConfigKind.AI_MODEL && it.action == ConfigAction.CREATE }
                    ?.value
                    ?.get(AI_PROVIDER_ID_FIELD)
                    ?.let(ConfigJson::opRefOf)
            providerRef?.let { byRef[it] }?.let { op.copy(affects = it.affects) } ?: op
        }
    }

    private fun planUpdate(
        ref: String,
        kind: ConfigKind,
        op: OperationInput,
        secrets: List<SecretSlot>,
        refs: PlanRefs,
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
            else -> planUpdateOf(ref, kind, current, changes, secrets, refs, errors)
        }
    }

    private fun planUpdateOf(
        ref: String,
        kind: ConfigKind,
        current: ConfigEntity,
        changes: List<ChangeInput>,
        secrets: List<SecretSlot>,
        refs: PlanRefs,
        errors: MutableList<String>,
    ): PlannedOperation {
        val target = current.toTarget()
        val masked = configReadService.masked(current)
        val secretPaths = secrets.map { it.path }.toSet()
        val authType = current.dto.path(ConfigAuthSettingTypes.TYPE_FIELD).asText(null)
        if (kind == ConfigKind.AUTH_SETTING) errors += ConfigSecretPolicy.misplacedAuthSettingSecrets(authType, secrets)
        errors +=
            changes
                .filter { it.remove != true }
                .flatMap { change -> ConfigSecretPolicy.clearTextSecrets(kind, authType, change.value, path = change.path?.trim().orEmpty()) }
                .map(ConfigSecretPolicy::clearTextSecretError)
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
                    else -> {
                        // Last, and only here: the path is known valid and not refused for another
                        // reason, so the selectors are read once instead of for every change.
                        val newIds = newSubAgentIds(kind, path, change, current.dto)
                        if (newIds.isNotEmpty()) {
                            errors += SubAgentSelectorValidation.correction(newIds)
                            null
                        } else {
                            PlannedChange(
                                path = path,
                                after = if (change.remove == true) null else change.value ?: objectMapper.nullNode(),
                                remove = change.remove == true,
                                beforeHash = ConfigJson.hash(ConfigJson.valueAt(masked, path)),
                            )
                        }
                    }
                }
            }
        if (current.fromFilesystem) errors += "${kind.name.lowercase()} '${current.name}' is defined in a file and cannot be changed here."
        val dependsOn = planned.flatMap { ConfigJson.opRefs(it.after) }.toSet()
        (dependsOn - refs.all).forEach { errors += "unknown reference {\"\$op\": \"$it\"}." }
        if (ref in dependsOn) errors += "an operation cannot reference itself."
        return PlannedOperation(
            ref = ref,
            kind = kind,
            action = ConfigAction.UPDATE,
            target = target,
            changes = planned,
            secrets = secrets,
            dependsOn = dependsOn.toList().sorted(),
            affects = affects(namespaced = target.namespaceId != null || kind == ConfigKind.NAMESPACE, userId = target.userId),
        )
    }

    /** The integration a `CREATE` copies, read like any target: within the bounds, with the user's rights. */
    private fun copySource(
        kind: ConfigKind,
        id: String,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedTarget? =
        if (kind == ConfigKind.INTEGRATION) {
            targetEntity(kind, id, context, errors)?.toTarget()
        } else {
            errors += "'copyFrom' is only supported for INTEGRATION."
            null
        }

    /** `DEPLOY` and `UNDEPLOY` act on the current namespace, or on the one [OperationInput.scope] names. */
    private fun planTargetOnly(
        ref: String,
        kind: ConfigKind,
        action: ConfigAction,
        op: OperationInput,
        refs: PlanRefs,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedOperation? {
        val deployment = action == ConfigAction.DEPLOY || action == ConfigAction.UNDEPLOY
        val scope = op.scope?.takeIf { deployment }?.let { deploymentScope(kind, it, refs, context, errors) }
        return targetEntity(kind, op.id, context, errors)?.toTarget()?.let { target ->
            PlannedOperation(
                ref = ref,
                kind = kind,
                action = action,
                target = target,
                scope = scope,
                dependsOn = listOfNotNull(scope?.namespaceRef),
                affects = affects(namespaced = deployment || target.namespaceId != null, userId = target.userId),
            )
        }
    }

    private fun deploymentScope(
        kind: ConfigKind,
        scope: ScopeInput,
        refs: PlanRefs,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedScope =
        scopeFrom(kind, scope, refs, context, errors).also {
            if (it.namespaceId == null && it.namespaceRef == null) {
                errors += "a deployment needs a namespace: omit 'scope' for this one, or use {\"\$op\": \"<ref>\"} of a namespace this plan creates."
            }
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

    /** A namespace is a platform-level entity: it has no scope of its own. */
    private fun scopeOf(
        kind: ConfigKind,
        scope: ScopeInput?,
        refs: PlanRefs,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedScope? =
        when {
            kind == ConfigKind.NAMESPACE -> {
                if (scope != null && (namespaceNodeOf(scope) != null || !scope.userId.isNullOrBlank())) errors += "a namespace has no scope: omit 'scope'."
                PlannedScope(namespaceId = null, userId = null)
            }
            kind == ConfigKind.AI_MODEL -> {
                val ownUser = scope?.userId?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
                if (scope != null && (namespaceNodeOf(scope) != null || ownUser != null)) errors += "an AI model has no 'scope': $MODEL_SCOPE_FROM_PROVIDER"
                PlannedScope(namespaceId = null, userId = null)
            }
            scope == null -> {
                errors +=
                    "CREATE needs 'scope': {\"namespaceId\": \"${ScopeInput.CURRENT_NAMESPACE}\", {\"\$op\": \"<ref>\"} of a namespace this plan creates or null for platform, " +
                    "\"userId\": \"${ScopeInput.CALLER}\" or null}."
                null
            }
            else -> scopeFrom(kind, scope, refs, context, errors)
        }

    private fun scopeFrom(
        kind: ConfigKind,
        scope: ScopeInput,
        refs: PlanRefs,
        context: ProposalContext,
        errors: MutableList<String>,
    ): PlannedScope {
        val namespaceNode = namespaceNodeOf(scope)
        val namespaceRef = namespaceNode?.let(ConfigJson::opRefOf)
        val namespaceId =
            when {
                namespaceNode == null -> null
                namespaceRef != null -> {
                    if (namespaceRef !in refs.namespaces) {
                        errors += "scope.namespaceId {\"\$op\": \"$namespaceRef\"} must name an operation of this plan that creates a NAMESPACE."
                    }
                    null
                }
                else ->
                    namespaceIdOf(namespaceNode, context).also { id ->
                        if (id == null || (id != context.namespaceId && !configReadService.canAdminister(context.callerId, id))) {
                            errors +=
                                "scope.namespaceId must be this namespace (\"${ScopeInput.CURRENT_NAMESPACE}\" or ${context.namespaceId}), the id of a namespace " +
                                "the user administers, {\"\$op\": \"<ref>\"} of a namespace this plan creates, or null for platform."
                        }
                    }
            }
        val userId =
            scope.userId?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { raw ->
                if (raw.equals(ScopeInput.CALLER, ignoreCase = true) || raw == context.callerId.toString()) {
                    context.callerId
                } else {
                    errors += "scope.userId must be \"${ScopeInput.CALLER}\" or null: an entity can only be created for the user who applies the plan."
                    null
                }
            }
        if (userId != null && kind in SHARED_ONLY_KINDS) {
            errors += "${kind.name.lowercase()}s have no personal scope: scope.userId must be null."
        }
        return PlannedScope(namespaceId = namespaceId, userId = userId, namespaceRef = namespaceRef?.takeIf { it in refs.namespaces })
    }

    /** The namespace part of [scope], or null when it means the platform (absent, JSON null, blank or `"null"`). */
    private fun namespaceNodeOf(scope: ScopeInput): JsonNode? =
        scope.namespaceId?.takeUnless { node -> node.isNull || (node.isTextual && node.textValue().trim().let { it.isEmpty() || it == "null" }) }

    private fun namespaceIdOf(
        node: JsonNode,
        context: ProposalContext,
    ): UUID? {
        val raw = node.takeIf { it.isTextual }?.textValue()?.trim()
        return if (raw.equals(ScopeInput.CURRENT_NAMESPACE, ignoreCase = true)) context.namespaceId else raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    private fun isCreateOf(
        op: OperationInput,
        kind: ConfigKind,
    ): Boolean = enumOrNull<ConfigKind>(op.kind) == kind && enumOrNull<ConfigAction>(op.action) == ConfigAction.CREATE

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
                !ConfigSecretPolicy.canHoldSecret(kind, path) -> {
                    errors += "'$path' cannot hold a secret for a ${kind.name.lowercase()}: ${ConfigSecretPolicy.secretFieldsHint(kind)}."
                    null
                }
                else -> SecretSlot(path = path, label = secret.label?.trim()?.takeIf { it.isNotEmpty() } ?: path.removePrefix("/"))
            }
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
    ): List<String> =
        operations.mapNotNull { op ->
            when {
                op.kind == ConfigKind.AGENT && op.action == ConfigAction.CREATE && op.deployTo == ConfigDeployTarget.NONE ->
                    "${op.ref}: the new agent is not deployed, so members of the namespace will not see it."
                op.kind == ConfigKind.AGENT &&
                    op.action == ConfigAction.ENABLE &&
                    op.target != null &&
                    op.target.id !in configReadService.deployedAgentIds(op.target.namespaceId ?: context.namespaceId) ->
                    "${op.ref}: '${op.target.name}' is not deployed on its namespace, so members will not see it."
                op.kind == ConfigKind.NAMESPACE && op.action == ConfigAction.CREATE && op.value?.hasNonNull("defaultAgentName") != true ->
                    "${op.ref}: the new namespace has no default agent, so a message without @mention gets no answer there."
                else -> null
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
            // A namespace is created first; its update comes last, e.g. to name a default agent the plan creates.
            ConfigKind.NAMESPACE -> if (op.action == ConfigAction.CREATE) 0 else 11
            ConfigKind.AUTH_SETTING -> 1
            ConfigKind.AI_PROVIDER -> 2
            ConfigKind.AI_MODEL -> 3
            ConfigKind.INTEGRATION -> 4
            ConfigKind.SKILL -> 5
            ConfigKind.AGENT ->
                when (op.action) {
                    ConfigAction.CREATE -> 6
                    ConfigAction.UPDATE -> 7
                    ConfigAction.ENABLE, ConfigAction.DISABLE -> 9
                    ConfigAction.DEPLOY, ConfigAction.UNDEPLOY -> 10
                }
            ConfigKind.PROMPT -> 8
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
                ConfigKind.AUTH_SETTING -> setOf(ConfigAuthSettingTypes.TYPE_FIELD)
                // The REST API keeps a namespace's externalId as created.
                ConfigKind.NAMESPACE -> setOf("externalId")
                // The REST API keeps a provider's apiType as created, and a model stays with its provider.
                ConfigKind.AI_PROVIDER -> setOf("apiType")
                ConfigKind.AI_MODEL -> setOf(AI_PROVIDER_ID_FIELD)
                else -> emptySet()
            }

    private fun immutableHint(
        kind: ConfigKind,
        path: String,
    ): String =
        when {
            kind == ConfigKind.PROMPT && path.startsWith("/agentConfigId") ->
                " (a prompt is attached to an agent when it is created: create a new prompt instead)"
            kind == ConfigKind.AI_PROVIDER && path.startsWith("/apiType") ->
                " (create a new AI provider with the other API type instead)"
            kind == ConfigKind.AI_MODEL && path.startsWith("/$AI_PROVIDER_ID_FIELD") ->
                " (a model stays with its AI provider: create a new model instead)"
            else -> ""
        }

    /**
     * The entity ids a `/subAgents` change would add to [current], or nothing when it adds none.
     *
     * Refused here rather than only by the REST layer so the agent is told while it can still fix
     * its own call: a frozen plan is applied long after, by the user, and the first failing
     * operation stops the rest of theirs. Carried-over ids are left alone for the reason
     * [SubAgentSelectorValidation.requireNoNewIdSelectors] gives: an agent whose stored selectors
     * already hold one must stay editable.
     *
     * Only a whole-list replacement at `/subAgents` is read. A deeper path is refused anyway by the
     * list check above, and reading [ChangeInput.value] as the new list there would be wrong.
     *
     * Called from the last branch of the `when`, so [path] is already a valid pointer.
     */
    private fun newSubAgentIds(
        kind: ConfigKind,
        path: String,
        change: ChangeInput,
        current: JsonNode,
    ): List<String> {
        if (kind != ConfigKind.AGENT || change.remove == true) return emptyList()
        if (ConfigJson.segments(path) != listOf(SUB_AGENTS_FIELD)) return emptyList()
        return SubAgentSelectorValidation.newIdSelectors(
            subAgents = selectorsOf(change.value),
            stored = selectorsOf(current.get(SUB_AGENTS_FIELD)),
        )
    }

    /** A `subAgents` node as the selector list it is meant to be, or null when it is not a list. */
    private fun selectorsOf(node: JsonNode?): List<String>? =
        node?.takeIf { it.isArray }?.mapNotNull { it.takeIf(JsonNode::isTextual)?.asText() }

    private fun isValidPath(path: String): Boolean =
        path.startsWith("/") && path.length > 1 && runCatching { ConfigJson.segments(path) }.getOrNull()?.let { segments ->
            segments.size in 1..MAX_PATH_DEPTH && segments.none { it.isEmpty() }
        } == true

    private fun affects(
        namespaced: Boolean,
        userId: UUID?,
    ): ConfigAffects =
        when {
            userId != null -> ConfigAffects.CALLER_ONLY
            !namespaced -> ConfigAffects.PLATFORM
            else -> ConfigAffects.NAMESPACE_MEMBERS
        }

    private inline fun <reified E : Enum<E>> enumOrNull(raw: String?): E? =
        raw?.trim()?.uppercase()?.replace('-', '_')?.let { value -> enumValues<E>().firstOrNull { it.name == value } }

    companion object {
        const val MAX_PATH_DEPTH = 3

        /** The one agent field keyed by name, so the one an id silently breaks. */
        private const val SUB_AGENTS_FIELD = "subAgents"

        /** The field of an AI model that names its provider, from which the model takes its scope. */
        private const val AI_PROVIDER_ID_FIELD = "aiProviderId"

        private const val MODEL_SCOPE_FROM_PROVIDER =
            "its scope comes from its AI provider, set 'value.aiProviderId' to a provider in the scope you want."

        private const val DEFAULT_SUMMARY = "Configuration change"

        /** Kinds that only exist at platform or namespace level. */
        private val SHARED_ONLY_KINDS = setOf(ConfigKind.AGENT, ConfigKind.SKILL)
    }
}
