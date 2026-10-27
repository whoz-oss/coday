package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.user.User
import mu.KLogging
import org.springframework.context.annotation.Lazy
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Shows, applies and rejects the configuration plans frozen by `AGENTOS_CONFIG__propose`.
 *
 * Every method runs inside the HTTP request of the user who called the endpoint. Reads go through
 * [ConfigReadService] with that user's rights; writes go through [ConfigWriter], i.e. through the
 * REST controllers with the same request identity. After an apply or a reject the server itself
 * posts the answer to the question, which resumes the agent.
 *
 * The plan is only ever read back from the case history: the `ToolResponseEvent` of the propose
 * call that carries it, followed by the `QuestionEvent` it announced. Nothing else is trusted.
 */
@Service
class ConfigChangeService(
    private val caseEventService: CaseEventService,
    @Lazy private val caseService: CaseService,
    private val configReadService: ConfigReadService,
    private val configPlanner: ConfigPlanner,
    private val configWriter: ConfigWriter,
    private val objectMapper: ObjectMapper,
) {
    /** Questions whose plan is being applied; single instance, like the case runtime registry. */
    private val claims = ConcurrentHashMap.newKeySet<UUID>()

    internal var clock: Clock = Clock.systemUTC()

    fun view(
        caseId: UUID,
        questionId: UUID,
        viewer: User,
    ): ConfigChangeViewDto {
        val loaded = load(caseId, questionId)
        val plan = loaded.plan
        val state = state(loaded)
        val createdIds = mutableMapOf<String, UUID>()
        val deployed = configReadService.deployedAgentIds(plan.namespaceId)
        val operations =
            plan.order.mapIndexedNotNull { index, ref ->
                plan.operation(ref)?.let { op ->
                    val evaluation = evaluate(op, plan, viewer.id, createdIds, deployed)
                    evaluation.entityId?.let { createdIds[ref] = it }
                    viewOf(op, index, evaluation, plan, viewer.id)
                }
            }
        return ConfigChangeViewDto(
            questionId = questionId,
            caseId = caseId,
            recipientUserId = plan.recipientUserId,
            agentName = plan.agentName,
            summary = plan.summary,
            planHash = plan.planHash,
            createdAt = plan.createdAt,
            expiresAt = plan.expiresAt,
            state = state,
            canApply = state == ConfigChangeState.OPEN && viewer.id == plan.recipientUserId,
            operations = operations,
            warnings = plan.warnings,
        )
    }

    fun apply(
        caseId: UUID,
        questionId: UUID,
        caller: User,
        request: ConfigChangeApplyRequest,
    ): ConfigChangeReportDto {
        requireRecipient(load(caseId, questionId).plan, caller)
        return withClaim(questionId) {
            // Re-read under the claim: an answer posted by a concurrent apply or reject is now visible.
            val loaded = load(caseId, questionId)
            val plan = loaded.plan
            if (request.expectedPlanHash != plan.planHash) throw ConflictException("PLAN_HASH_MISMATCH: the plan changed since it was displayed")
            requireOpen(state(loaded, claimedByCaller = true))
            missingSecrets(plan, caller.id, request.secrets).takeIf { it.isNotEmpty() }?.let { missing ->
                throw UnprocessableEntityException("MISSING_SECRET: ${missing.joinToString()}")
            }
            val report = applyPlan(plan, caller, request.secrets)
            report.copy(answerPosted = postAnswer(caseId, questionId, caller, answerText(plan, report, caller)))
        }
    }

    fun reject(
        caseId: UUID,
        questionId: UUID,
        caller: User,
        comment: String?,
    ) {
        requireRecipient(load(caseId, questionId).plan, caller)
        withClaim(questionId) {
            requireOpen(state(load(caseId, questionId), claimedByCaller = true))
            val text =
                buildString {
                    append("[${ConfigAgentIntegration.TYPE} change ${shortId(questionId)} ${ConfigChangeDecision.REJECTED} by ${displayName(caller)}]")
                    comment?.trim()?.takeIf { it.isNotEmpty() }?.let { append("\nComment: ").append(it) }
                }
            if (!postAnswer(caseId, questionId, caller, text)) throw IllegalStateException("The rejection could not be delivered to the agent")
        }
    }

    /** Runs [block] while holding the per-question claim, so apply and reject never overlap. */
    private fun <T> withClaim(
        questionId: UUID,
        block: () -> T,
    ): T {
        if (!claims.add(questionId)) throw ConflictException("IN_PROGRESS: this plan is already being applied or rejected")
        try {
            return block()
        } finally {
            claims.remove(questionId)
        }
    }

    /** Keys of the secrets the operations still to write need and the request does not carry. */
    private fun missingSecrets(
        plan: ConfigChangePlan,
        callerId: UUID,
        secrets: Map<String, String>,
    ): List<String> {
        val createdIds = mutableMapOf<String, UUID>()
        val deployed = configReadService.deployedAgentIds(plan.namespaceId)
        return plan.order.mapNotNull(plan::operation).flatMap { op ->
            val evaluation = evaluate(op, plan, callerId, createdIds, deployed)
            evaluation.entityId?.let { createdIds[op.ref] = it }
            if (evaluation.status != ConfigOperationStatus.PENDING || evaluation.entityId != null && op.action == ConfigAction.CREATE) {
                emptyList()
            } else {
                op.secrets.map { secretKey(op, it) }.filter { secrets[it].isNullOrEmpty() }
            }
        }
    }

    private fun applyPlan(
        plan: ConfigChangePlan,
        caller: User,
        secrets: Map<String, String>,
    ): ConfigChangeReportDto {
        val createdIds = mutableMapOf<String, UUID>()
        val deployed = configReadService.deployedAgentIds(plan.namespaceId)
        val results = mutableListOf<ConfigOperationResultDto>()
        var stopped = false
        for (ref in plan.order) {
            val op = plan.operation(ref) ?: continue
            if (stopped) {
                results += ConfigOperationResultDto(ref, ConfigResultStatus.NOT_RUN)
                continue
            }
            val evaluation =
                runCatching { evaluate(op, plan, caller.id, createdIds, deployed) }
                    .onFailure { logger.warn(it) { "[ConfigChange] could not evaluate ${op.ref}" } }
                    .getOrNull()
            if (evaluation == null) {
                results += ConfigOperationResultDto(ref, ConfigResultStatus.FAILED, message = "Unexpected error, see the server logs.")
                stopped = true
                continue
            }
            evaluation.entityId?.let { createdIds[ref] = it }
            val result =
                when (evaluation.status) {
                    ConfigOperationStatus.APPLIED, ConfigOperationStatus.ALREADY_APPLIED ->
                        ConfigOperationResultDto(ref, ConfigResultStatus.ALREADY_APPLIED, evaluation.entityId)
                    ConfigOperationStatus.CONFLICT ->
                        ConfigOperationResultDto(ref, ConfigResultStatus.CONFLICT, evaluation.entityId, evaluation.reason)
                    ConfigOperationStatus.NOT_VISIBLE ->
                        ConfigOperationResultDto(ref, ConfigResultStatus.NOT_FOUND, message = evaluation.reason)
                    ConfigOperationStatus.PENDING -> write(op, plan, evaluation, secrets, createdIds)
                }
            result.entityId?.let { createdIds[ref] = it }
            results += result
            stopped = result.status !in setOf(ConfigResultStatus.APPLIED, ConfigResultStatus.ALREADY_APPLIED)
        }
        val succeeded = results.count { it.status == ConfigResultStatus.APPLIED || it.status == ConfigResultStatus.ALREADY_APPLIED }
        val decision =
            when (succeeded) {
                results.size -> ConfigChangeDecision.APPLIED
                0 -> ConfigChangeDecision.FAILED
                else -> ConfigChangeDecision.PARTIAL
            }
        return ConfigChangeReportDto(decision = decision, appliedBy = caller.id, results = results)
    }

    private fun write(
        op: PlannedOperation,
        plan: ConfigChangePlan,
        evaluation: Evaluation,
        secrets: Map<String, String>,
        createdIds: Map<String, UUID>,
    ): ConfigOperationResultDto {
        // Set as soon as a CREATE succeeds, so a later failure (the deployment) still reports the entity.
        var createdId: UUID? = null
        return try {
            val entityId =
                when (op.action) {
                    ConfigAction.CREATE -> {
                        val id = evaluation.entityId ?: configWriter.create(op.kind, createPayload(op, secrets, createdIds))
                        createdId = id
                        if (op.kind == ConfigKind.AGENT && op.deployTo == ConfigDeployTarget.NAMESPACE) {
                            configWriter.setDeployed(op.scope?.namespaceId ?: plan.namespaceId, id, deployed = true)
                        }
                        id
                    }
                    ConfigAction.UPDATE -> {
                        val target = op.target!!
                        configWriter.update(op.kind, target.id) { dto -> applyChanges(dto, op, secrets, createdIds) }
                        target.id
                    }
                    ConfigAction.ENABLE, ConfigAction.DISABLE -> {
                        configWriter.setEnabled(op.target!!.id, enabled = op.action == ConfigAction.ENABLE)
                        op.target.id
                    }
                    ConfigAction.DEPLOY, ConfigAction.UNDEPLOY -> {
                        configWriter.setDeployed(plan.namespaceId, op.target!!.id, deployed = op.action == ConfigAction.DEPLOY)
                        op.target.id
                    }
                }
            ConfigOperationResultDto(op.ref, ConfigResultStatus.APPLIED, entityId)
        } catch (e: Exception) {
            val (status, message) = failureOf(op, e)
            ConfigOperationResultDto(
                ref = op.ref,
                status = status,
                entityId = createdId,
                message = createdId?.let { "Created (id $it) but not deployed: $message" } ?: message,
            )
        }
    }

    private fun failureOf(
        op: PlannedOperation,
        e: Exception,
    ): Pair<ConfigResultStatus, String?> =
        when (e) {
            is AccessDeniedException -> ConfigResultStatus.DENIED to "The user is not allowed to do this."
            is ResourceNotFoundException -> ConfigResultStatus.NOT_FOUND to e.message
            is ConflictException -> ConfigResultStatus.CONFLICT to e.message
            is BadRequestException, is UnprocessableEntityException -> ConfigResultStatus.INVALID to e.message
            // AgentConfigServiceImpl reports a duplicate name this way; ConfigJson refuses paths through lists.
            is IllegalArgumentException ->
                (if (e.message?.contains("already exists") == true) ConfigResultStatus.CONFLICT else ConfigResultStatus.INVALID) to e.message
            else -> {
                logger.warn(e) { "[ConfigChange] ${op.ref} ${op.action} ${op.kind} failed" }
                ConfigResultStatus.FAILED to "Unexpected error, see the server logs."
            }
        }

    private fun createPayload(
        op: PlannedOperation,
        secrets: Map<String, String>,
        createdIds: Map<String, UUID>,
    ): ObjectNode {
        val payload = (ConfigJson.resolveOpRefs(op.value, createdIds) as ObjectNode).deepCopy()
        injectSecrets(payload, op, secrets)
        // Last, so nothing in the plan can move the entity away from the scope the card shows.
        payload.put("namespaceId", op.scope?.namespaceId?.toString())
        payload.put("userId", op.scope?.userId?.toString())
        return payload
    }

    private fun applyChanges(
        dto: ObjectNode,
        op: PlannedOperation,
        secrets: Map<String, String>,
        createdIds: Map<String, UUID>,
    ) {
        op.changes.forEach { change ->
            if (change.remove) {
                ConfigJson.removeAt(dto, change.path)
            } else {
                ConfigJson.setAt(dto, change.path, ConfigJson.resolveOpRefs(change.after, createdIds))
            }
        }
        injectSecrets(dto, op, secrets)
    }

    private fun injectSecrets(
        dto: ObjectNode,
        op: PlannedOperation,
        secrets: Map<String, String>,
    ) {
        op.secrets.forEach { slot ->
            val value =
                secrets[secretKey(op, slot)]?.takeIf { it.isNotEmpty() }
                    ?: throw UnprocessableEntityException("MISSING_SECRET: '${slot.label}' (${secretKey(op, slot)}) is required")
            ConfigJson.setAt(dto, slot.path, TextNode(value))
        }
    }

    /** Where [op] stands, read with [viewerId]'s rights. */
    private fun evaluate(
        op: PlannedOperation,
        plan: ConfigChangePlan,
        viewerId: UUID,
        createdIds: Map<String, UUID>,
        deployed: Set<UUID>,
    ): Evaluation =
        when (op.action) {
            ConfigAction.CREATE -> evaluateCreate(op, plan, viewerId, createdIds, deployed)
            ConfigAction.UPDATE -> evaluateUpdate(op, plan, viewerId, createdIds)
            ConfigAction.ENABLE, ConfigAction.DISABLE -> {
                val entity = configReadService.find(op.kind, op.target!!.id, viewerId, plan.namespaceId)
                when {
                    entity == null -> Evaluation(ConfigOperationStatus.NOT_VISIBLE, reason = "'${op.target.name}' is not visible to you")
                    entity.enabled == (op.action == ConfigAction.ENABLE) -> Evaluation(ConfigOperationStatus.APPLIED, entity.id, entity = entity)
                    else -> Evaluation(ConfigOperationStatus.PENDING, entity.id, entity = entity)
                }
            }
            ConfigAction.DEPLOY, ConfigAction.UNDEPLOY -> {
                val isDeployed = op.target!!.id in deployed
                if (isDeployed == (op.action == ConfigAction.DEPLOY)) {
                    Evaluation(ConfigOperationStatus.APPLIED, op.target.id)
                } else {
                    Evaluation(ConfigOperationStatus.PENDING, op.target.id)
                }
            }
        }

    private fun evaluateCreate(
        op: PlannedOperation,
        plan: ConfigChangePlan,
        viewerId: UUID,
        createdIds: Map<String, UUID>,
        deployed: Set<UUID>,
    ): Evaluation {
        val name = op.value?.path("name")?.asText().orEmpty()
        val existing =
            configReadService.findByName(op.kind, name, op.scope?.namespaceId, op.scope?.userId, viewerId, plan.namespaceId)
                ?: return Evaluation(ConfigOperationStatus.PENDING)
        val secretFields = op.secrets.map { ConfigJson.segments(it.path).first() }.toSet()
        val identical =
            (op.value as? ObjectNode)?.properties().orEmpty().all { (field, value) ->
                field in secretFields ||
                    ConfigJson.opRefs(value).any { it !in createdIds } ||
                    sameValue(existing.dto.get(field), ConfigJson.resolveOpRefs(value, createdIds))
            }
        return when {
            !identical -> Evaluation(ConfigOperationStatus.CONFLICT, existing.id, existing, "an entity named '$name' already exists with other values")
            op.kind == ConfigKind.AGENT && op.deployTo == ConfigDeployTarget.NAMESPACE && existing.id !in deployed ->
                Evaluation(ConfigOperationStatus.PENDING, existing.id, existing)
            else -> Evaluation(ConfigOperationStatus.ALREADY_APPLIED, existing.id, existing)
        }
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
            is ArrayNode ->
                objectMapper.createArrayNode().also { copy -> node.forEach { copy.add(normalized(it)) } }
            is ObjectNode ->
                objectMapper.createObjectNode().also { copy -> node.properties().forEach { (key, value) -> copy.set<JsonNode>(key, normalized(value)) } }
            else -> node
        }

    private fun evaluateUpdate(
        op: PlannedOperation,
        plan: ConfigChangePlan,
        viewerId: UUID,
        createdIds: Map<String, UUID>,
    ): Evaluation {
        val target = op.target!!
        val entity =
            configReadService.find(op.kind, target.id, viewerId, plan.namespaceId)
                ?: return Evaluation(ConfigOperationStatus.NOT_VISIBLE, reason = "'${target.name}' is not visible to you")
        val masked = configReadService.masked(entity)
        var allApplied = op.secrets.isEmpty()
        for (change in op.changes) {
            val current = ConfigJson.valueAt(entity.dto, change.path)
            val resolvable = ConfigJson.opRefs(change.after).all { it in createdIds }
            val applied =
                when {
                    change.remove -> current == null
                    resolvable -> current == ConfigJson.resolveOpRefs(change.after, createdIds)
                    else -> false
                }
            if (applied) continue
            allApplied = false
            if (ConfigJson.hash(ConfigJson.valueAt(masked, change.path)) != change.beforeHash) {
                return Evaluation(ConfigOperationStatus.CONFLICT, entity.id, entity, "${change.path} changed since the plan was made")
            }
        }
        return Evaluation(if (allApplied) ConfigOperationStatus.APPLIED else ConfigOperationStatus.PENDING, entity.id, entity)
    }

    private fun viewOf(
        op: PlannedOperation,
        index: Int,
        evaluation: Evaluation,
        plan: ConfigChangePlan,
        viewerId: UUID,
    ): ConfigOperationViewDto {
        val masked = evaluation.entity?.let(configReadService::masked)
        return ConfigOperationViewDto(
            ref = op.ref,
            order = index + 1,
            kind = op.kind,
            action = op.action,
            name = op.target?.name ?: op.value?.path("name")?.asText().orEmpty(),
            targetId = op.target?.id ?: evaluation.entityId,
            namespaceId = op.target?.namespaceId ?: op.scope?.namespaceId,
            userId = op.target?.userId ?: op.scope?.userId,
            affects = op.affects,
            deployTo = op.deployTo,
            value = op.value,
            changes =
                op.changes.map { change ->
                    ConfigFieldChangeDto(
                        path = change.path,
                        before = masked?.let { ConfigJson.valueAt(it, change.path) },
                        after = change.after,
                        remove = change.remove,
                    )
                },
            secretInputs = op.secrets.map { ConfigSecretInputDto(key = secretKey(op, it), path = it.path, label = it.label) },
            status = evaluation.status,
        )
    }

    private fun load(
        caseId: UUID,
        questionId: UUID,
    ): LoadedPlan {
        val events = caseEventService.findByParent(caseId)
        val questionIndex = events.indexOfFirst { it is QuestionEvent && it.id == questionId }
        val question = events.getOrNull(questionIndex) as? QuestionEvent ?: throw notFound(questionId)
        val planIndex =
            events.indexOfFirst {
                it is ToolResponseEvent &&
                    it.toolName == ConfigAgentIntegration.PROPOSE_TOOL_NAME &&
                    it.structuredOutput?.path("questionId")?.asText() == questionId.toString()
            }
        if (planIndex < 0 || planIndex > questionIndex) throw notFound(questionId)
        val planJson = (events[planIndex] as ToolResponseEvent).structuredOutput!!
        val plan = runCatching { objectMapper.treeToValue(planJson, ConfigChangePlan::class.java) }.getOrNull() ?: throw notFound(questionId)
        val consistent =
            plan.schema == ConfigAgentIntegration.PLAN_SCHEMA &&
                plan.caseId == caseId &&
                plan.questionId == questionId &&
                plan.agentName == question.agentName &&
                plan.recipientUserId == question.userId &&
                configPlanner.planHash(planJson) == plan.planHash
        if (!consistent) throw notFound(questionId)
        return LoadedPlan(plan, question, planIndex, events)
    }

    private fun state(
        loaded: LoadedPlan,
        claimedByCaller: Boolean = false,
    ): ConfigChangeState {
        val (plan, question, planIndex, events) = loaded
        return when {
            isAnswered(question, events) -> ConfigChangeState.ANSWERED
            !claimedByCaller && plan.questionId in claims -> ConfigChangeState.IN_PROGRESS
            Instant.now(clock).isAfter(plan.expiresAt) -> ConfigChangeState.EXPIRED
            hasNewerPlan(planIndex, events) -> ConfigChangeState.SUPERSEDED
            else -> ConfigChangeState.OPEN
        }
    }

    /** A newer plan in the same case replaces this one for good, whether it has been answered or not. */
    private fun hasNewerPlan(
        planIndex: Int,
        events: List<CaseEvent>,
    ): Boolean =
        events
            .drop(planIndex + 1)
            .any { it is ToolResponseEvent && it.toolName == ConfigAgentIntegration.PROPOSE_TOOL_NAME && it.structuredOutput != null }

    /** Same rule as the case runtime: only the addressee's answer closes a question. */
    private fun isAnswered(
        question: QuestionEvent,
        events: List<CaseEvent>,
    ): Boolean =
        events.any {
            it is AnswerEvent && it.questionId == question.id && (question.userId == null || it.actor.id == question.userId.toString())
        }

    private fun requireRecipient(
        plan: ConfigChangePlan,
        caller: User,
    ) {
        if (caller.id != plan.recipientUserId) throw AccessDeniedException("NOT_RECIPIENT: only the user this plan was proposed to can apply or reject it")
    }

    private fun requireOpen(state: ConfigChangeState) {
        if (state != ConfigChangeState.OPEN) throw ConflictException("${state.name}: this plan can no longer be applied or rejected")
    }

    /**
     * Answers the question, which resumes the agent. Returns false instead of throwing when the case
     * refuses the message (e.g. it was closed meanwhile): the configuration is already written, so the
     * caller must still get the report. The plan then stays open and applying it again re-sends it.
     */
    private fun postAnswer(
        caseId: UUID,
        questionId: UUID,
        caller: User,
        text: String,
    ): Boolean {
        val actor = Actor(id = caller.id.toString(), displayName = displayName(caller), role = ActorRole.USER)
        return runCatching {
            caseService.addMessage(caseId = caseId, actor = actor, content = listOf(MessageContent.Text(text)), answerToEventId = questionId)
        }.onFailure { logger.warn(it) { "[ConfigChange] could not answer question $questionId in case $caseId" } }
            .isSuccess
    }

    /** Fixed templates and codes only: the answer is replayed to the LLM, so it carries no parameter value. */
    private fun answerText(
        plan: ConfigChangePlan,
        report: ConfigChangeReportDto,
        caller: User,
    ): String =
        buildString {
            append("[${ConfigAgentIntegration.TYPE} change ${shortId(plan.questionId)} ${report.decision} by ${displayName(caller)}]")
            report.results.forEach { result ->
                val op = plan.operation(result.ref) ?: return@forEach
                append('\n').append(ConfigProposeTool.describe(op)).append(": ").append(result.status)
                result.entityId?.takeIf { op.action == ConfigAction.CREATE }?.let { append(" (id ").append(it).append(')') }
                if (result.status !in setOf(ConfigResultStatus.APPLIED, ConfigResultStatus.ALREADY_APPLIED, ConfigResultStatus.NOT_RUN)) {
                    result.message?.let { append(" - ").append(it.take(MAX_MESSAGE_LENGTH)) }
                }
            }
        }

    private fun displayName(user: User): String =
        listOfNotNull(user.firstname, user.lastname).joinToString(" ").ifBlank { user.metadata.id.toString() }

    private fun shortId(id: UUID) = id.toString().take(8)

    private fun secretKey(
        op: PlannedOperation,
        slot: SecretSlot,
    ) = "${op.ref}.${slot.path}"

    private fun notFound(questionId: UUID) = ResourceNotFoundException("No configuration plan for question $questionId in this case")

    private data class LoadedPlan(
        val plan: ConfigChangePlan,
        val question: QuestionEvent,
        val planIndex: Int,
        val events: List<CaseEvent>,
    )

    private data class Evaluation(
        val status: ConfigOperationStatus,
        val entityId: UUID? = null,
        val entity: ConfigEntity? = null,
        val reason: String? = null,
    )

    companion object : KLogging() {
        private const val MAX_MESSAGE_LENGTH = 200
    }
}
