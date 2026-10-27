package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.user.User
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Shows, applies and rejects the configuration plans frozen by `AGENTOS_CONFIG__propose`.
 *
 * Every method runs inside the HTTP request of the user who called the endpoint. Reads go through
 * [ConfigReadService] with that user's rights; writes go through [ConfigWriter], i.e. through the
 * REST controllers with the same request identity. After an apply or a reject the server posts the
 * answer to the question ([ConfigChangeAnswer]), which closes the plan and resumes the agent.
 *
 * The plan comes from [ConfigPlanReader], and each operation's status from [ConfigChangeStatus].
 */
@Service
class ConfigChangeService(
    private val configPlanReader: ConfigPlanReader,
    private val configChangeStatus: ConfigChangeStatus,
    private val configChangeAnswer: ConfigChangeAnswer,
    private val configReadService: ConfigReadService,
    private val configWriter: ConfigWriter,
    private val clock: Clock,
) {
    /**
     * Questions whose plan is being applied or rejected. Entries live only for the duration of one
     * request (removed in a `finally`). Single instance, like the case runtime registry.
     */
    private val claims = ConcurrentHashMap.newKeySet<UUID>()

    fun view(
        caseId: UUID,
        questionId: UUID,
        viewer: User,
    ): ConfigChangeViewDto {
        val loaded = configPlanReader.load(caseId, questionId)
        val plan = loaded.plan
        val state = stateOf(loaded, claimedByCaller = false)
        val createdIds = mutableMapOf<String, UUID>()
        val operations =
            plan.order.mapNotNull(plan::operation).mapIndexed { index, op ->
                val evaluation = configChangeStatus.evaluate(op, plan, viewer.id, createdIds)
                evaluation.entityId?.let { createdIds[op.ref] = it }
                viewOf(op, index, evaluation, plan, createdIds, viewer.id)
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
            canReject = state in REJECTABLE_STATES && viewer.id == plan.recipientUserId,
            operations = operations,
            warnings = plan.warnings,
        )
    }

    fun apply(
        caseId: UUID,
        questionId: UUID,
        caller: User,
        request: ConfigChangeApplyRequest,
    ): ConfigChangeReportDto =
        withClaim(questionId) {
            // Read under the claim: an answer posted by a concurrent apply or reject is visible.
            val loaded = configPlanReader.load(caseId, questionId)
            val plan = loaded.plan
            requireRecipient(plan, caller)
            if (request.expectedPlanHash != plan.planHash) throw ConflictException("PLAN_HASH_MISMATCH: the plan changed since it was displayed")
            requireState(stateOf(loaded, claimedByCaller = true), setOf(ConfigChangeState.OPEN))
            missingSecrets(plan, caller.id, request.secrets).takeIf { it.isNotEmpty() }?.let { missing ->
                throw UnprocessableEntityException("MISSING_SECRET: ${missing.joinToString()}")
            }
            val report = applyPlan(plan, caller, request.secrets)
            report.copy(answerPosted = configChangeAnswer.post(caseId, questionId, caller, configChangeAnswer.applied(plan, report, caller)))
        }

    fun reject(
        caseId: UUID,
        questionId: UUID,
        caller: User,
        comment: String?,
    ) {
        withClaim(questionId) {
            val loaded = configPlanReader.load(caseId, questionId)
            requireRecipient(loaded.plan, caller)
            // An expired plan can still be rejected: the answer closes the question the agent waits on.
            requireState(stateOf(loaded, claimedByCaller = true), REJECTABLE_STATES)
            if (!configChangeAnswer.post(caseId, questionId, caller, configChangeAnswer.rejected(questionId, caller, comment))) {
                throw IllegalStateException("The rejection could not be delivered to the agent")
            }
        }
    }

    /** Runs [block] while holding the per-question claim, so apply and reject never overlap. */
    private fun <T> withClaim(
        questionId: UUID,
        block: () -> T,
    ): T {
        if (!claims.add(questionId)) throw ConflictException("IN_PROGRESS: this plan is already being applied or rejected")
        return try {
            block()
        } finally {
            claims.remove(questionId)
        }
    }

    private fun stateOf(
        loaded: LoadedPlan,
        claimedByCaller: Boolean,
    ): ConfigChangeState = configPlanReader.state(loaded, Instant.now(clock), claimed = !claimedByCaller && loaded.plan.questionId in claims)

    /** Keys of the secrets the operations still to write need and the request does not carry. */
    private fun missingSecrets(
        plan: ConfigChangePlan,
        callerId: UUID,
        secrets: Map<String, String>,
    ): List<String> {
        val createdIds = mutableMapOf<String, UUID>()
        return plan.order.mapNotNull(plan::operation).flatMap { op ->
            val evaluation = configChangeStatus.evaluate(op, plan, callerId, createdIds)
            evaluation.entityId?.let { createdIds[op.ref] = it }
            val needsWrite = evaluation.status == ConfigOperationStatus.PENDING && !(op.action == ConfigAction.CREATE && evaluation.entityId != null)
            op.secrets.map { secretKey(op, it) }.filter { needsWrite && secrets[it].isNullOrEmpty() }
        }
    }

    /** Applies the operations in order, and once one does not succeed reports the rest as NOT_RUN. */
    private fun applyPlan(
        plan: ConfigChangePlan,
        caller: User,
        secrets: Map<String, String>,
    ): ConfigChangeReportDto {
        val createdIds = mutableMapOf<String, UUID>()
        val results =
            plan.order.mapNotNull(plan::operation).fold(emptyList<ConfigOperationResultDto>()) { done, op ->
                done +
                    if (done.all { it.status.isSuccess }) {
                        applyOperation(op, plan, caller, secrets, createdIds)
                    } else {
                        ConfigOperationResultDto(op.ref, ConfigResultStatus.NOT_RUN)
                    }
            }
        // A CREATE that fails after writing (the deployment of a new agent) still created its entity.
        val written = results.count { it.status.isSuccess || (it.entityId != null && plan.operation(it.ref)?.action == ConfigAction.CREATE) }
        val decision =
            when {
                results.all { it.status.isSuccess } -> ConfigChangeDecision.APPLIED
                written == 0 -> ConfigChangeDecision.FAILED
                else -> ConfigChangeDecision.PARTIAL
            }
        return ConfigChangeReportDto(decision = decision, appliedBy = caller.id, results = results)
    }

    /** Evaluates then, when pending, writes one operation; records the entity it created or matched. */
    private fun applyOperation(
        op: PlannedOperation,
        plan: ConfigChangePlan,
        caller: User,
        secrets: Map<String, String>,
        createdIds: MutableMap<String, UUID>,
    ): ConfigOperationResultDto {
        val evaluation =
            runCatching { configChangeStatus.evaluate(op, plan, caller.id, createdIds) }
                .onFailure { logger.warn(it) { "[ConfigChange] could not evaluate ${op.ref}" } }
                .getOrNull()
        evaluation?.entityId?.let { createdIds[op.ref] = it }
        val result =
            when (evaluation?.status) {
                null -> ConfigOperationResultDto(op.ref, ConfigResultStatus.FAILED, message = UNEXPECTED_ERROR)
                ConfigOperationStatus.APPLIED, ConfigOperationStatus.ALREADY_APPLIED ->
                    ConfigOperationResultDto(op.ref, ConfigResultStatus.ALREADY_APPLIED, evaluation.entityId)
                ConfigOperationStatus.CONFLICT -> ConfigOperationResultDto(op.ref, ConfigResultStatus.CONFLICT, evaluation.entityId, evaluation.reason)
                ConfigOperationStatus.NOT_VISIBLE -> ConfigOperationResultDto(op.ref, ConfigResultStatus.NOT_FOUND, message = evaluation.reason)
                ConfigOperationStatus.PENDING -> write(op, plan, evaluation, secrets, createdIds)
            }
        result.entityId?.let { createdIds[op.ref] = it }
        return result
    }

    private fun write(
        op: PlannedOperation,
        plan: ConfigChangePlan,
        evaluation: OperationEvaluation,
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
                            configWriter.setDeployed(deploymentNamespace(op, plan, createdIds), id, deployed = true)
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
                        configWriter.setDeployed(deploymentNamespace(op, plan, createdIds), op.target!!.id, deployed = op.action == ConfigAction.DEPLOY)
                        op.target.id
                    }
                }
            ConfigOperationResultDto(op.ref, ConfigResultStatus.APPLIED, entityId)
        } catch (e: CancellationException) {
            throw e
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

    /** Operations run in dependency order and stop at the first failure, so a namespace the plan creates exists by then. */
    private fun deploymentNamespace(
        op: PlannedOperation,
        plan: ConfigChangePlan,
        createdIds: Map<String, UUID>,
    ): UUID = checkNotNull(plan.deploymentNamespaceOf(op, createdIds)) { "The namespace ${op.scope?.namespaceRef} of ${op.ref} was not created" }

    private fun failureOf(
        op: PlannedOperation,
        e: Exception,
    ): Pair<ConfigResultStatus, String?> =
        when (e) {
            is AccessDeniedException -> ConfigResultStatus.DENIED to "The user is not allowed to do this."
            is ResourceNotFoundException -> ConfigResultStatus.NOT_FOUND to e.message
            is ConflictException -> ConfigResultStatus.CONFLICT to e.message
            is BadRequestException, is UnprocessableEntityException -> ConfigResultStatus.INVALID to e.message
            // A DTO the REST API cannot even read (missing type, wrong field type) would have been a 400.
            is JsonProcessingException -> ConfigResultStatus.INVALID to e.originalMessage
            is ResponseStatusException -> statusOf(e) to (e.reason ?: e.message)
            // A service's own validation, e.g. a duplicate agent name; ConfigJson refuses paths through lists.
            is IllegalArgumentException -> ConfigResultStatus.INVALID to e.message
            else -> {
                logger.warn(e) { "[ConfigChange] ${op.ref} ${op.action} ${op.kind} failed" }
                ConfigResultStatus.FAILED to UNEXPECTED_ERROR
            }
        }

    private fun statusOf(e: ResponseStatusException): ConfigResultStatus =
        when (e.statusCode.value()) {
            HttpStatus.BAD_REQUEST.value(), HttpStatus.UNPROCESSABLE_ENTITY.value() -> ConfigResultStatus.INVALID
            HttpStatus.FORBIDDEN.value() -> ConfigResultStatus.DENIED
            HttpStatus.NOT_FOUND.value() -> ConfigResultStatus.NOT_FOUND
            HttpStatus.CONFLICT.value() -> ConfigResultStatus.CONFLICT
            else -> ConfigResultStatus.FAILED
        }

    private fun createPayload(
        op: PlannedOperation,
        secrets: Map<String, String>,
        createdIds: Map<String, UUID>,
    ): ObjectNode {
        val overrides = (ConfigJson.resolveOpRefs(op.value, createdIds) as ObjectNode).deepCopy()
        val payload = op.copyFrom?.let { source -> copyBase(op.kind, source).also { ConfigJson.merge(it, overrides) } } ?: overrides
        injectSecrets(payload, op, secrets)
        val namespaceId = op.scope?.namespaceIn(createdIds)
        check(op.scope?.namespaceRef == null || namespaceId != null) { "The namespace ${op.scope?.namespaceRef} of ${op.ref} was not created" }
        // Last, so nothing in the plan can move the entity away from the scope the card shows.
        payload.put("namespaceId", namespaceId?.toString())
        payload.put("userId", op.scope?.userId?.toString())
        return payload
    }

    /** The copied entity as the REST API returns it to the user who applies, without its identity and scope. */
    private fun copyBase(
        kind: ConfigKind,
        source: PlannedTarget,
    ): ObjectNode = configWriter.read(kind, source.id).apply { remove(COPY_EXCLUDED_FIELDS) }

    private fun applyChanges(
        dto: ObjectNode,
        op: PlannedOperation,
        secrets: Map<String, String>,
        createdIds: Map<String, UUID>,
    ) {
        op.changes.forEach { change ->
            val value = ConfigJson.resolveOpRefs(change.after, createdIds)
            when {
                // The auth setting API reads a missing or null field as "keep", and an empty one as "clear".
                op.kind == ConfigKind.AUTH_SETTING && (change.remove || value == null || value.isNull) -> ConfigJson.setAt(dto, change.path, TextNode(""))
                change.remove -> ConfigJson.removeAt(dto, change.path)
                else -> ConfigJson.setAt(dto, change.path, value)
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

    private fun viewOf(
        op: PlannedOperation,
        index: Int,
        evaluation: OperationEvaluation,
        plan: ConfigChangePlan,
        createdIds: Map<String, UUID>,
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
            namespaceId = op.target?.namespaceId ?: op.scope?.namespaceIn(createdIds),
            userId = op.target?.userId ?: op.scope?.userId,
            namespaceName = namespaceNameOf(op, plan, viewerId),
            affects = op.affects,
            deployTo = op.deployTo,
            copyFrom = op.copyFrom?.name,
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

    /**
     * The namespace an operation acts in when it is not the plan's own: one the plan creates, the
     * namespace an `UPDATE NAMESPACE` changes, or another one the viewer may read.
     */
    private fun namespaceNameOf(
        op: PlannedOperation,
        plan: ConfigChangePlan,
        viewerId: UUID,
    ): String? {
        val ref = op.scope?.namespaceRef
        val namespaceId = op.scope?.namespaceId ?: op.target?.namespaceId
        return when {
            ref != null -> plan.operation(ref)?.value?.path("name")?.asText()
            op.kind == ConfigKind.NAMESPACE -> op.target?.name
            namespaceId != null && namespaceId != plan.namespaceId ->
                configReadService.find(ConfigKind.NAMESPACE, namespaceId, viewerId, plan.namespaceId)?.name
            else -> null
        }
    }

    private fun requireRecipient(
        plan: ConfigChangePlan,
        caller: User,
    ) {
        if (caller.id != plan.recipientUserId) throw AccessDeniedException("NOT_RECIPIENT: only the user this plan was proposed to can apply or reject it")
    }

    private fun requireState(
        state: ConfigChangeState,
        allowed: Set<ConfigChangeState>,
    ) {
        if (state !in allowed) throw ConflictException("${state.name}: this plan can no longer be applied or rejected")
    }

    private fun secretKey(
        op: PlannedOperation,
        slot: SecretSlot,
    ) = "${op.ref}.${slot.path}"

    companion object : KLogging() {
        private const val UNEXPECTED_ERROR = "Unexpected error, see the server logs."

        private val REJECTABLE_STATES = setOf(ConfigChangeState.OPEN, ConfigChangeState.EXPIRED)

        /** Fields of a copied entity the copy never keeps: the server assigns the id, the plan sets the scope. */
        private val COPY_EXCLUDED_FIELDS = listOf("id", "namespaceId", "userId")
    }
}
