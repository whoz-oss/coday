package io.whozoss.agentos.caseFlow

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Persistent data model representing a case.
 *
 * Implements [Entity] for standard CRUD operations.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) is required because the [Entity]
 * interface exposes a computed `id` property (delegating to metadata.id) which
 * Jackson serialises into the JSON but is not a constructor parameter — so
 * deserialisation must silently skip it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Case(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID,
    val status: CaseStatus = CaseStatus.PENDING,
    val title: String = "Case ${metadata.id}",
    /**
     * Opaque key-value context supplied by the integrating application at case creation time.
     * Injected into every agent prompt alongside the last user message's [sessionContext],
     * with [sessionContext] taking precedence on key conflicts.
     */
    val context: Map<String, Any?>? = null,
) : Entity
