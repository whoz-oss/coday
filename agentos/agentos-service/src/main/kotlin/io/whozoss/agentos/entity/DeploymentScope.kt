package io.whozoss.agentos.entity

import java.util.UUID

data class DeploymentScope(
    val scopeType: ScopeType,
    val id: UUID,
)

/**
 * Maps to the primary Neo4j node label of the deployment target.
 * [label] must match the first label returned by `labels(scope)[0]` in Cypher.
 */
enum class ScopeType(
    val label: String,
) {
    NAMESPACE("Namespace"),
    USER_GROUP("UserGroup"),
}
