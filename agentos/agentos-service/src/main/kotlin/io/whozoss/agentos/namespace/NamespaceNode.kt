package io.whozoss.agentos.namespace

import io.whozoss.agentos.sdk.api.namespace.AdminCaseListing
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [Namespace].
 *
 * Stored as a `(:Namespace)` node. Namespaces are root-level — no parent relationship.
 *
 * Properties kept flat (no nested objects) to avoid SDN's limited support for
 * embedded value types in Community Edition.
 */
@Node("Namespace")
data class NamespaceNode(
    @Id
    val id: String,
    val name: String = "",
    val description: String? = null,
    val configPath: String? = null,
    val externalId: String? = null,
    val defaultAgentName: String? = null,
    val runCostThreshold: Double? = null,
    // Nullable and absent on existing nodes: no migration needed.
    val sharedExchangePath: String? = null,
    val maxToolRounds: Int? = null,
    // Enum name, null when the namespace inherits the platform default. Absent on existing nodes.
    val adminCaseListing: String? = null,
    // EntityMetadata fields
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): Namespace =
        Namespace(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    removed = removed ?: false,
                ),
            name = name,
            description = description,
            configPath = configPath,
            externalId = externalId,
            defaultAgentName = defaultAgentName,
            runCostThreshold = runCostThreshold,
            sharedExchangePath = sharedExchangePath,
            maxToolRounds = maxToolRounds,
            adminCaseListing = adminCaseListing?.let(AdminCaseListing::valueOf),
        )

    companion object {
        fun fromDomain(ns: Namespace): NamespaceNode =
            NamespaceNode(
                id = ns.id.toString(),
                name = ns.name,
                description = ns.description,
                configPath = ns.configPath,
                externalId = ns.externalId,
                defaultAgentName = ns.defaultAgentName,
                runCostThreshold = ns.runCostThreshold,
                sharedExchangePath = ns.sharedExchangePath,
                maxToolRounds = ns.maxToolRounds,
                adminCaseListing = ns.adminCaseListing?.name,
                created = ns.metadata.created,
                createdBy = ns.metadata.createdBy,
                modified = ns.metadata.modified,
                modifiedBy = ns.metadata.modifiedBy,
                removed = ns.metadata.removed.takeIf { it },
            )
    }
}
