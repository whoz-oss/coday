package io.whozoss.factory.workstream.projection

import io.whozoss.factory.workstream.domain.Workstream
import java.security.MessageDigest

/**
 * Computes the stable `workstreamRevision` (ETag) of the aggregated workstream
 * projection: a 16-hex-char SHA-256 prefix over a canonical, order-stable
 * string built from the registry entry (revision + timestamps) and the
 * aggregated state parts supplied by the projection service.
 *
 * The function is pure and deterministic: identical inputs always yield the
 * same revision, and any state change (revision bump, count change, newer
 * timestamp) yields a different one.
 */
object WorkstreamRevision {

    private const val REVISION_LENGTH = 16

    fun compute(workstream: Workstream, parts: List<String>): String {
        val canonical = buildList {
            add(workstream.organizationId)
            add(workstream.workstreamId)
            add(workstream.namespaceId.orEmpty())
            add(workstream.status.dbValue)
            add(workstream.revision.toString())
            add(workstream.createdAt.toString())
            add(workstream.updatedAt.toString())
            addAll(parts)
        }.joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(REVISION_LENGTH)
    }
}
