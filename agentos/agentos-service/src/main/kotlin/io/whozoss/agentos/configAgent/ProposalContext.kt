package io.whozoss.agentos.configAgent

import java.time.Instant
import java.util.UUID

/** Who proposes, for whom, and where the question will be asked. */
data class ProposalContext(
    val callerId: UUID,
    val namespaceId: UUID,
    val caseId: UUID,
    val agentName: String,
    val questionId: UUID,
    val now: Instant,
)
