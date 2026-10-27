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
        val planRefs = PlanRefs(all = refs.toSet(), namespaces = refs.filterIndexed { index, _ -> isNamespaceCreate(rawOperations[index]) }.toSet())
        val errors = mutableListOf<String>()
        refs.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { errors += "ref '$it': refs must be unique." }
        val planned =
            rawOperations.mapIndexedNotNull { index, op ->
                val ref = refs[index]
                val opErrors = mutableListOf<String>()
                planOperation(ref, op, planRefs, context, opErrors).also { errors += opErrors.map { "$ref: $it" } }
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
        val name = effective.path("name").asText("").trim()
        if (name.isEmpty()) errors += "CREATE needs 'value.name'."
        if (kind == ConfigKind.AGENT && effective.get("enabled")?.isBoolean != true) {
            errors += "creating an agent needs 'value.enabled': true (it can run) or false (it stays a draft nobody can use)."
        }
        listOf("id", "namespaceId", "userId").filter { effective.has(it) }.forEach {
            errors += "'value.$it' is not allowed: the scope goes in 'scope' and the id is assigned by the server."
        }
        val scope = scopeOf(kind, op.scope, refs, context, errors)
        secrets.forEach { slot ->
            ConfigJson.valueAt(effective, slot.path)?.takeUnless { it.isNull }?.let {
                errors += "secret path '${slot.path}' must not carry a value in 'value'."
            }
        }
        val authType = effective.path("authType").asText(null)
        checkAuthSettingSecrets(kind, authType, secrets, errors)
        clearTextSecrets(kind, authType, effective, path = "").forEach { errors += clearTextSecretError(it) }
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
                affects = affects(namespaced = it.namespaceId != null || it.namespaceRef != null, userId = it.userId),
            )
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
        val authType = current.dto.path("authType").asText(null)
        checkAuthSettingSecrets(kind, authType, secrets, errors)
        changes
            .filter { it.remove != true }
            .flatMap { change -> clearTextSecrets(kind, authType, change.value, path = change.path?.trim().orEmpty()) }
            .forEach { errors += clearTextSecretError(it) }
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
            scope == null -> {
                errors +=
                    "CREATE needs 'scope': {\"namespaceId\": \"current\", {\"\$op\": \"<ref>\"} of a namespace this plan creates or null for platform, " +
                    "\"userId\": \"me\" or null}."
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
                                "scope.namespaceId must be this namespace (\"current\" or ${context.namespaceId}), the id of a namespace " +
                                "the user administers, {\"\$op\": \"<ref>\"} of a namespace this plan creates, or null for platform."
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
        return if (raw.equals("current", ignoreCase = true)) context.namespaceId else raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    private fun isNamespaceCreate(op: OperationInput): Boolean =
        enumOrNull<ConfigKind>(op.kind) == ConfigKind.NAMESPACE && enumOrNull<ConfigAction>(op.action) == ConfigAction.CREATE

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

    /** An auth setting's declared secrets must be secret fields of its own type, or the typed value would be dropped. */
    private fun checkAuthSettingSecrets(
        kind: ConfigKind,
        authType: String?,
        secrets: List<SecretSlot>,
        errors: MutableList<String>,
    ) {
        val allowed = ConfigAuthSettingTypes.secretFieldsOf(authType)?.takeIf { kind == ConfigKind.AUTH_SETTING } ?: return
        secrets.filter { ConfigJson.segments(it.path).first() !in allowed }.forEach {
            errors += "'${it.path}' is not a secret field of a $authType auth setting: " +
                if (allowed.isEmpty()) "it has none." else "use ${allowed.joinToString { field -> "/$field" }}."
        }
    }

    /**
     * Pointers, under [path], where [node] writes a secret in clear: a secret field of an auth setting
     * (of its type, or of any type when the type is unknown), or an integration parameter named like a
     * credential. A secret is declared in `secrets` and typed by the user, never written by the LLM.
     */
    private fun clearTextSecrets(
        kind: ConfigKind,
        authType: String?,
        node: JsonNode?,
        path: String,
    ): List<String> =
        when (kind) {
            ConfigKind.AUTH_SETTING -> {
                val fields = ConfigAuthSettingTypes.secretFieldsOf(authType) ?: ConfigSecretMasker.AUTH_SETTING_SECRET_FIELDS
                secretLeaves(node, path) { pointer -> ConfigJson.segments(pointer).let { it.size == 1 && it.single() in fields } }
            }
            ConfigKind.INTEGRATION ->
                secretLeaves(node, path) { pointer ->
                    ConfigJson.segments(pointer).let { it.size > 1 && it.first() == PARAMETERS && it.last().lowercase() in SECRET_PARAMETER_KEYS }
                }
            else -> emptyList()
        }

    /** Pointers of the non-blank text leaves of [node], rooted at [path], that [isSecret] flags. */
    private fun secretLeaves(
        node: JsonNode?,
        path: String,
        isSecret: (String) -> Boolean,
    ): List<String> =
        when {
            node == null -> emptyList()
            node.isObject -> node.properties().flatMap { (key, value) -> secretLeaves(value, path + ConfigJson.pointerOf(listOf(key)), isSecret) }
            node.isArray -> node.flatMapIndexed { index, value -> secretLeaves(value, "$path/$index", isSecret) }
            node.isTextual && node.textValue().isNotBlank() && path.isNotEmpty() && isSecret(path) -> listOf(path)
            else -> emptyList()
        }

    private fun clearTextSecretError(pointer: String): String = "'$pointer' holds a secret: never write it, declare it in 'secrets' so the user types it in the card."

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
            ConfigKind.NAMESPACE -> if (op.action == ConfigAction.CREATE) 0 else 9
            ConfigKind.AUTH_SETTING -> 1
            ConfigKind.INTEGRATION -> 2
            ConfigKind.SKILL -> 3
            ConfigKind.AGENT ->
                when (op.action) {
                    ConfigAction.CREATE -> 4
                    ConfigAction.UPDATE -> 5
                    ConfigAction.ENABLE, ConfigAction.DISABLE -> 7
                    ConfigAction.DEPLOY, ConfigAction.UNDEPLOY -> 8
                }
            ConfigKind.PROMPT -> 6
        }

    private fun allowedActions(kind: ConfigKind): Set<ConfigAction> =
        when (kind) {
            ConfigKind.AGENT -> ConfigAction.entries.toSet()
            ConfigKind.NAMESPACE -> setOf(ConfigAction.CREATE, ConfigAction.UPDATE)
            else -> setOf(ConfigAction.CREATE, ConfigAction.UPDATE)
        }

    /** Fields the REST API refuses to change on PUT, so a plan never fails on them at apply time. */
    private fun immutableFields(kind: ConfigKind): Set<String> =
        setOf("id", "namespaceId", "userId") +
            when (kind) {
                ConfigKind.PROMPT -> setOf("agentConfigId")
                ConfigKind.INTEGRATION -> setOf("integrationType")
                ConfigKind.AUTH_SETTING -> setOf("authType")
                // The REST API keeps a namespace's externalId as created.
                ConfigKind.NAMESPACE -> setOf("externalId")
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

        private const val DEFAULT_SUMMARY = "Configuration change"

        /** Kinds that only exist at platform or namespace level. */
        private val SHARED_ONLY_KINDS = setOf(ConfigKind.AGENT, ConfigKind.SKILL)

        private val AUTH_SETTING_SECRET_PATHS = ConfigSecretMasker.AUTH_SETTING_SECRET_FIELDS.map { "/$it" }.toSet()

        private const val PARAMETERS = "parameters"

        /** Integration parameter names that hold a credential, compared in lower case. */
        private val SECRET_PARAMETER_KEYS =
            setOf(
                "token",
                "secret",
                "password",
                "apikey",
                "api_key",
                "clientsecret",
                "client_secret",
                "accesstoken",
                "access_token",
                "refreshtoken",
                "refresh_token",
            )
    }
}
