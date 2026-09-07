package io.whozoss.agentos.caseFlow

import java.util.UUID

/**
 * Manages per-user read state for cases.
 *
 * Read state is stored on the `(User)-[:WATCHES]->(Case)` edge's `readAt`
 * property. A null `readAt` means the case has never been opened by this user (unread).
 *
 * This service is intentionally separate from [CaseService] to keep the read-tracking
 * concern isolated. It is injected into [CaseController] and [CaseServiceImpl].
 */
interface CaseReadService {

    /**
     * Records that [userId] has read [caseId] at the current instant.
     *
     * Idempotent: calling it repeatedly only advances `readAt`. The controller must
     * already have verified that the user has Case READ permission before calling this.
     */
    fun markRead(userId: String, caseId: UUID)

    /**
     * Counts the number of unread cases in [namespaceId] for [userId].
     *
     * A case is unread when it has at least one message (`lastMessageAt` is not null) and
     * either no `WATCHES` edge exists (`readAt IS NULL`) or `max(MessageEvent.timestamp) > readAt`.
     * Only cases the user has a direct `[:ADMIN|MEMBER]` edge on are counted.
     * Cases with no messages are never counted as unread.
     */
    fun countUnread(userId: String, namespaceId: UUID): Long
}
