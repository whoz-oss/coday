package io.whozoss.factory.workstream

import io.whozoss.factory.error.BadRequestException
import io.whozoss.factory.error.ConflictException
import io.whozoss.factory.error.ResourceNotFoundException
import io.whozoss.factory.error.UnprocessableEntityException
import io.whozoss.factory.web.FactoryCaller
import io.whozoss.factory.workstream.domain.ControllerCaseBounds
import io.whozoss.factory.workstream.domain.ControllerCaseExecution
import io.whozoss.factory.workstream.domain.ControllerCaseStatus
import io.whozoss.factory.workstream.domain.Workstream
import io.whozoss.factory.workstream.persistence.Neo4jControllerCaseRepository
import io.whozoss.factory.workstream.projection.ControllerResumptionPackageBuilder
import io.whozoss.factory.workstream.projection.WorkstreamProjectionService
import io.whozoss.factory.workstream.web.CompactControllerCaseRequest
import io.whozoss.factory.workstream.web.ControllerResumptionPackage
import io.whozoss.factory.workstream.web.StartControllerCaseRequest
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Controller case lifecycle of the Workstream Agent (Phase 9).
 *
 * The Workstream Agent is a single stable conceptual interlocutor — its
 * identity is the workstream registry `controllerAgentRef` — but it is NOT an
 * eternal case: a workstream has at most one *active* controller case at a
 * time. Starting binds the first case; explicit compaction
 * ([compactControllerCase]) archives the current case and binds a fresh one
 * with the same agent and workstream identity. Every start rebuilds a bounded
 * resumption context package from the Phase 5 aggregated projection — never
 * a raw conversation history.
 *
 * ## Resiliency / unavailability policy
 *
 * The controller case governs only the *conversational* interlocution with
 * the Workstream Agent. If the Workstream Agent or its controller case is
 * unavailable (no active case, agent offline, or mid-compaction), the
 * underlying workflows, durable attempts, oracle executions and environments
 * continue to progress according to their own policies — this service
 * performs ZERO writes to those aggregates and holds no lease over them. Only
 * new conversational interactions wait for a controller case to be (re)bound.
 *
 * All identity (`organizationId`, `workstreamId`) comes from
 * [FactoryCaller.scope], never from request bodies or paths beyond the
 * [WorkstreamService.assertWithinWorkstream] trust-boundary guard.
 */
@Service
class ControllerCaseService(
    private val workstreamService: WorkstreamService,
    private val repository: Neo4jControllerCaseRepository,
    private val projectionService: WorkstreamProjectionService,
    private val packageBuilder: ControllerResumptionPackageBuilder,
) {

    /** The current active controller case of [workstreamId], or `null` when none started yet. */
    fun getActiveCase(caller: FactoryCaller, workstreamId: String): ControllerCaseExecution? {
        requireWorkstream(caller, workstreamId)
        return repository.findActive(caller.scope, workstreamId)
    }

    /** The active case plus every archived case of [workstreamId], ordered by sequence (Cockpit history view). */
    fun listHistory(caller: FactoryCaller, workstreamId: String): List<ControllerCaseExecution> {
        requireWorkstream(caller, workstreamId)
        return repository.listHistory(caller.scope, workstreamId)
    }

    /**
     * Build the bounded resumption context package of [workstreamId] from the
     * live aggregated projection — a preview, persisting nothing. Used by the
     * Cockpit "preview resumption context" call.
     */
    fun getContextPackage(
        caller: FactoryCaller,
        workstreamId: String,
        namespaceId: String?,
        limit: Int?,
    ): ControllerResumptionPackage {
        workstreamService.assertWithinWorkstream(caller, workstreamId)
        val projection = projectionService.getAggregatedProjection(caller, workstreamId, namespaceId, limit)
        return packageBuilder.build(projection)
    }

    /**
     * Start the FIRST controller case of a workstream. The workstream must
     * declare a `controllerAgentRef` in its registry entry; compaction is the
     * only path to renew an already-active case. The bounded resumption
     * package is rebuilt from the current projection and persisted on the new
     * case.
     *
     * @throws UnprocessableEntityException `CONTROLLER_AGENT_REF_REQUIRED` when the registry entry declares no agent ref;
     * @throws ConflictException `CONTROLLER_CASE_ALREADY_ACTIVE` when an active case already exists.
     */
    fun startControllerCase(
        caller: FactoryCaller,
        workstreamId: String,
        request: StartControllerCaseRequest,
    ): ControllerCaseExecution {
        val workstream = requireWorkstream(caller, workstreamId)
        val scope = caller.scope
        val controllerAgentRef = workstream.controllerAgentRef
            ?: throw UnprocessableEntityException(
                "Le workstream '$workstreamId' ne déclare aucun controllerAgentRef",
                mapOf("code" to "CONTROLLER_AGENT_REF_REQUIRED", "workstreamId" to workstreamId),
            )
        if (repository.findActive(scope, workstreamId) != null) {
            throw ConflictException(
                "Le workstream '$workstreamId' a déjà un case contrôleur actif",
                mapOf("code" to "CONTROLLER_CASE_ALREADY_ACTIVE", "workstreamId" to workstreamId),
            )
        }
        val contextPackage = buildContextPackage(caller, workstreamId)
        val now = Instant.now()
        return repository.startFirst(
            scope,
            ControllerCaseExecution(
                organizationId = scope.organizationId,
                workstreamId = workstreamId,
                caseId = request.caseId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                controllerAgentRef = controllerAgentRef,
                status = ControllerCaseStatus.ACTIVE,
                sequence = 1,
                startedAt = now,
                contextSummary = packageBuilder.toBoundedJson(contextPackage),
                contextRevision = contextPackage.sourceRevision,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /**
     * Explicit compaction: archive the current active controller case
     * (stamping the optional [CompactControllerCaseRequest.compactionReason])
     * and bind a fresh active case — same workstream identity, same
     * `controllerAgentRef` (read from the active case, it never changes),
     * next sequence, and a resumption package rebuilt from the fresh
     * projection.
     *
     * @throws BadRequestException `INVALID_COMPACTION_REASON` when the reason exceeds the bound;
     * @throws ConflictException `NO_ACTIVE_CONTROLLER_CASE` when no case is active.
     */
    fun compactControllerCase(
        caller: FactoryCaller,
        workstreamId: String,
        request: CompactControllerCaseRequest,
    ): ControllerCaseExecution {
        requireWorkstream(caller, workstreamId)
        val scope = caller.scope
        val compactionReason = request.compactionReason?.takeIf { it.isNotBlank() }
        if (compactionReason != null && compactionReason.length > ControllerCaseBounds.MAX_COMPACTION_REASON_CHARS) {
            throw BadRequestException(
                "compactionReason ne peut pas dépasser ${ControllerCaseBounds.MAX_COMPACTION_REASON_CHARS} caractères",
                mapOf("code" to "INVALID_COMPACTION_REASON"),
            )
        }
        val active = repository.findActive(scope, workstreamId)
            ?: throw ConflictException(
                "Le workstream '$workstreamId' n'a aucun case contrôleur actif à compacter",
                mapOf("code" to "NO_ACTIVE_CONTROLLER_CASE", "workstreamId" to workstreamId),
            )
        val contextPackage = buildContextPackage(caller, workstreamId)
        return repository.archiveAndStart(
            scope = scope,
            workstreamId = workstreamId,
            caseId = request.caseId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            controllerAgentRef = active.controllerAgentRef,
            compactionReason = compactionReason,
            contextSummary = packageBuilder.toBoundedJson(contextPackage),
            contextRevision = contextPackage.sourceRevision,
            now = Instant.now(),
        )
    }

    /**
     * Trust-boundary + existence guard: the path [workstreamId] must be the
     * caller's trusted workstream and the registry entry must exist.
     *
     * @return the domain registry entry;
     * @throws ResourceNotFoundException `WORKSTREAM_NOT_FOUND` when the entry is absent.
     */
    private fun requireWorkstream(caller: FactoryCaller, workstreamId: String): Workstream {
        workstreamService.assertWithinWorkstream(caller, workstreamId)
        return workstreamService.findDomain(caller.scope, workstreamId)
            ?: throw ResourceNotFoundException(
                "Le workstream '$workstreamId' n'existe pas",
                mapOf("code" to "WORKSTREAM_NOT_FOUND"),
            )
    }

    private fun buildContextPackage(caller: FactoryCaller, workstreamId: String): ControllerResumptionPackage =
        packageBuilder.build(projectionService.getAggregatedProjection(caller, workstreamId, null, null))
}
