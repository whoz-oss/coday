package io.whozoss.factory.worktree.domain

import io.whozoss.factory.persistence.TenantScope

enum class WorktreeExecutionOccupancyStatus { ACTIVE, RELEASED }

data class WorktreeExecutionOccupancy(
    val resourceIdentity: String,
    val ownerScope: TenantScope,
    val ownerKind: String?,
    val ownerId: String?,
    val generation: Long,
    val status: WorktreeExecutionOccupancyStatus,
    val formatVersion: Int = FORMAT_VERSION,
) {
    companion object { const val FORMAT_VERSION = 2 }
}

class WorktreeExecutionOccupancyConflict(message: String) : RuntimeException(message)
class WorktreeExecutionOccupancyStaleRelease(message: String) : RuntimeException(message)
class WorktreeExecutionOccupancySchemaUnavailable(message: String) : RuntimeException(message)
class WorktreeExecutionOccupancyLegacyData(message: String) : RuntimeException(message)
