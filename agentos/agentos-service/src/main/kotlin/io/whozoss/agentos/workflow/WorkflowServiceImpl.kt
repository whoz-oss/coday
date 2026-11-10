package io.whozoss.agentos.workflow

import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import mu.KLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class WorkflowServiceImpl(
    private val repository: WorkflowRepository,
    private val usageChecker: WorkflowUsageChecker,
) : WorkflowService {
    override fun create(entity: Workflow): Workflow {
        WorkflowValidator.requireValid(entity)
        repository.findByName(entity.namespaceId, entity.name)?.let { throw ConflictException(conflictMessage(entity)) }
        return saveOrConflict(entity)
    }

    override fun update(entity: Workflow): Workflow {
        WorkflowValidator.requireValid(entity)
        val existing = findById(entity.id) ?: throw ResourceNotFoundException("Workflow not found: ${entity.id}")
        if (existing.isDefault() && existing.name != entity.name) {
            throw BadRequestException("The default workflow cannot be renamed")
        }
        repository
            .findByName(entity.namespaceId, entity.name)
            ?.takeIf { it.id != entity.id }
            ?.let { throw ConflictException(conflictMessage(entity)) }
        return saveOrConflict(entity)
    }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<Workflow> = repository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<Workflow> = repository.findByParent(parentId)

    override fun findPlatform(): List<Workflow> = repository.findPlatform()

    override fun findAvailable(namespaceId: UUID): List<Workflow> = repository.findByParent(namespaceId) + repository.findPlatform()

    override fun getDefault(): Workflow =
        checkNotNull(repository.findByName(null, DEFAULT_WORKFLOW_NAME)) {
            "The default workflow '$DEFAULT_WORKFLOW_NAME' is missing: it is seeded at startup"
        }

    override fun resolveForScheduledPrompt(
        workflowId: UUID?,
        namespaceId: UUID?,
    ): Workflow {
        if (workflowId == null) return getDefault()
        val workflow = findById(workflowId) ?: throw BadRequestException("Workflow not found: $workflowId")
        if (workflow.namespaceId != null && workflow.namespaceId != namespaceId) {
            throw BadRequestException("Workflow $workflowId belongs to another namespace")
        }
        return workflow
    }

    override fun delete(id: UUID): Boolean {
        val existing = findById(id) ?: return false
        if (existing.isDefault()) throw ConflictException("The default workflow cannot be deleted")
        if (usageChecker.isInUse(id)) {
            throw ConflictException("Workflow $id is referenced by at least one scheduled prompt and cannot be deleted")
        }
        return repository.delete(id)
    }

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    private fun Workflow.isDefault(): Boolean = namespaceId == null && name == DEFAULT_WORKFLOW_NAME

    private fun saveOrConflict(entity: Workflow): Workflow =
        try {
            repository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            // Race backstop: a concurrent create slipped past the pre-check and hit the constraint.
            if (!isDoubleKeyConflict(e)) throw e
            throw ConflictException(conflictMessage(entity), e)
        }

    private fun isDoubleKeyConflict(e: DataIntegrityViolationException): Boolean =
        generateSequence<Throwable>(e) { it.cause }
            .mapNotNull { it.message }
            .any { WorkflowSchemaInitializer.DOUBLE_KEY_CONSTRAINT_NAME in it || "doubleKey" in it }

    private fun conflictMessage(entity: Workflow): String =
        "A workflow named '${entity.name}' already exists in this scope (namespaceId=${entity.namespaceId ?: "platform"})"

    companion object : KLogging() {
        /** Name of the seeded platform workflow used when a scheduled prompt names none. */
        const val DEFAULT_WORKFLOW_NAME = "default-audience-fan-out"
    }
}
