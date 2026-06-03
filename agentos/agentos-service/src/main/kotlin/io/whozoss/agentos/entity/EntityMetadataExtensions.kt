package io.whozoss.agentos.entity

import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant

/**
 * Stamps this metadata for a newly created entity.
 *
 * Sets [EntityMetadata.createdBy], [EntityMetadata.modifiedBy] to [userId],
 * and [EntityMetadata.created], [EntityMetadata.modified] to the current wall-clock time.
 */
fun EntityMetadata.stampCreated(userId: String): EntityMetadata = copy(
    createdBy = userId,
    modifiedBy = userId,
    created = Instant.now(),
    modified = Instant.now(),
)

/**
 * Stamps this metadata for an updated entity.
 *
 * Sets [EntityMetadata.modifiedBy] to [userId] and [EntityMetadata.modified]
 * to the current wall-clock time. All other fields (including [EntityMetadata.createdBy]
 * and [EntityMetadata.created]) are preserved.
 */
fun EntityMetadata.stampModified(userId: String): EntityMetadata = copy(
    modifiedBy = userId,
    modified = Instant.now(),
)
