package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseCommandJournal
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.git.GitRepositoryAssociationService
import io.whozoss.agentos.git.WorkspaceLifecycleLocks
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.stereotype.Service
import java.util.UUID

/** The occurrence has a stable case identity; a reclaimed lease cannot allocate another worktree. */
@Service
class ScheduledWorkspaceDispatch(
    private val cases: CaseService,
    private val associations: GitRepositoryAssociationService,
    private val journal: CaseCommandJournal,
) {
    fun caseFor(occurrenceId: UUID, namespaceId: UUID, title: String, promptId: UUID): Case? {
        val id = UUID.nameUUIDFromBytes("agentos:scheduled-workspace:$occurrenceId".toByteArray(Charsets.UTF_8))
        return WorkspaceLifecycleLocks.withRoot(id) {
            val existing = cases.findById(id, withRemoved = true)
            if (existing != null) {
                check(!existing.metadata.removed && existing.namespaceId == namespaceId && existing.scheduledPromptId == promptId) {
                    "The case for this occurrence was removed or no longer matches; it must not be recreated"
                }
                existing
            } else if (associations.findSettings(namespaceId)?.autoWorktreeForRootCases == true) {
                cases.create(Case(metadata = EntityMetadata(id = id), namespaceId = namespaceId, title = title, scheduledPromptId = promptId))
            } else null
        }
    }
    fun alreadyAccepted(caseId: UUID, occurrenceId: UUID) = journal.hasReceipt(caseId, occurrenceId)
}
