package io.whozoss.agentos.git

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [CaseResourceBinding].
 *
 * An active row also carries the `ActiveCaseResourceBinding` label, set and removed by
 * [Neo4jCaseResourceBindingRepository]. The "one active binding per root case" constraint is
 * declared on that label, like `ActiveNamespace`, so soft deletion frees the slot by removing it.
 *
 * No `BELONGS_TO` edge is materialised: a binding is reached by its root case id, and adding a
 * second edge into the case graph would make the family-enumeration queries ambiguous.
 *
 * [version] carries optimistic locking and tells Spring Data whether a save creates the row, so the
 * creation fields are audited only once.
 *
 * [settingsJson] holds the frozen [GitRepositorySettings]. Unlike other nodes, reading it never
 * fails: one unreadable row would otherwise break every sweep and file access of the namespace that
 * lists it. Such a binding reads with null settings, which fails its preparation with a clear reason
 * and gives its family no Git tool. The warning names the binding, never the JSON, since an admin
 * wrote its setup command.
 */
@Node("CaseResourceBinding")
data class CaseResourceBindingNode(
    @Id
    val id: String,
    val rootCaseId: String,
    val namespaceId: String,
    val integrationConfigId: String,
    val status: String,
    val baseSha: String? = null,
    val failureReason: String? = null,
    val settingsJson: String? = null,
    val cleanupReason: String? = null,
    /** A [SetupState] name, like [status]. */
    val setupState: String = SetupState.NOT_STARTED.name,
    // EntityMetadata fields
    @Version val version: Long? = null,
    @CreatedDate val created: Instant = Instant.now(),
    @CreatedBy val createdBy: String? = null,
    @LastModifiedDate val modified: Instant = Instant.now(),
    @LastModifiedBy val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): CaseResourceBinding =
        CaseResourceBinding(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    removed = removed ?: false,
                    version = version,
                ),
            rootCaseId = UUID.fromString(rootCaseId),
            namespaceId = UUID.fromString(namespaceId),
            integrationConfigId = UUID.fromString(integrationConfigId),
            status = CaseResourceStatus.valueOf(status),
            baseSha = baseSha,
            failureReason = failureReason,
            settings = readSettings(id, settingsJson),
            cleanupReason = cleanupReason,
            setup = SetupState.valueOf(setupState),
        )

    companion object : KLogging() {
        /** Settings are written and read here only. Source text stays out of parse errors. */
        private val MAPPER =
            JsonMapper.builder().addModule(kotlinModule()).disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build()

        private fun readSettings(
            id: String,
            json: String?,
        ): GitRepositorySettings? =
            json?.let {
                try {
                    MAPPER.readValue(it, GitRepositorySettings::class.java)
                } catch (e: JacksonException) {
                    logger.warn(e) { "Binding $id has unreadable workspace settings and is read without them" }
                    null
                }
            }

        fun fromDomain(binding: CaseResourceBinding): CaseResourceBindingNode =
            CaseResourceBindingNode(
                id = binding.id.toString(),
                rootCaseId = binding.rootCaseId.toString(),
                namespaceId = binding.namespaceId.toString(),
                integrationConfigId = binding.integrationConfigId.toString(),
                status = binding.status.name,
                baseSha = binding.baseSha,
                failureReason = binding.failureReason,
                settingsJson = binding.settings?.let { MAPPER.writeValueAsString(it) },
                cleanupReason = binding.cleanupReason,
                setupState = binding.setup.name,
                version = binding.metadata.version,
                created = binding.metadata.created,
                createdBy = binding.metadata.createdBy,
                modified = binding.metadata.modified,
                modifiedBy = binding.metadata.modifiedBy,
                removed = binding.metadata.removed.takeIf { it },
            )
    }
}
