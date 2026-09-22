package io.whozoss.agentos.git

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * The workspace allocated to one case family: a worktree with an optional observed branch, owned by the root case and
 * shared by every descendant.
 *
 * Exactly one binding per equipped root case; ordinary families have none at all rather than a row
 * saying "no resource". The presence of a binding — never the namespace's current configuration —
 * is what makes a family equipped: enabling automation later must not retro-equip existing
 * families, and disabling it must not strip equipped ones.
 *
 * [baseSha] is frozen once, when preparation first resolves the main branch, and never
 * recomputed: a retry after a crash must reuse the same base, without
 * silently rebase the case onto whatever was pushed meanwhile.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CaseResourceBinding(
    override val metadata: EntityMetadata = EntityMetadata(),
    /** The root case owning this workspace. Descendants resolve through it. */
    val rootCaseId: UUID,
    val namespaceId: UUID,
    /** The `GIT_REPOSITORY` configuration this workspace was provisioned from. */
    val integrationConfigId: UUID,
    val status: CaseResourceStatus = CaseResourceStatus.REQUESTED,
    /** Last observed worktree branch; null for a detached HEAD. Never controls provisioning. */
    val branchName: String? = null,
    /** Starting commit of the detached worktree, frozen at first successful resolution. */
    val baseSha: String? = null,
    /** Operator-facing failure cause; never a secret. */
    val failureReason: String? = null,
    val settingsJson: String? = null,
    val summaryJson: String? = null,
    val cleanupReason: String? = null,
    val setupStarted: Boolean = false,
    val setupCompleted: Boolean = false,
) : Entity
