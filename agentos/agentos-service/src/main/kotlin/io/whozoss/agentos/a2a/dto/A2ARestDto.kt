package io.whozoss.agentos.a2a.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue

/**
 * DTOs for the A2A HTTP+JSON/REST binding (spec §11).
 *
 * The REST binding uses the proto-style constant naming convention
 * (`ROLE_USER`, `TASK_STATE_COMPLETED`, …) — different from the JSON-RPC
 * binding which uses lowercase strings (`"user"`, `"submitted"`, …).
 * Promptfoo's `a2a` provider follows the proto-style naming, so these DTOs
 * are what it consumes.
 *
 * Wire format is aligned with `a2a.proto` v1.0.
 */

// ---------------------------------------------------------------------------
// Enums (proto-style)
// ---------------------------------------------------------------------------

enum class RestRole(@JsonValue val value: String) {
    ROLE_USER("ROLE_USER"),
    ROLE_AGENT("ROLE_AGENT"),
}

enum class RestTaskState(@JsonValue val value: String) {
    TASK_STATE_UNSPECIFIED("TASK_STATE_UNSPECIFIED"),
    TASK_STATE_SUBMITTED("TASK_STATE_SUBMITTED"),
    TASK_STATE_WORKING("TASK_STATE_WORKING"),
    TASK_STATE_INPUT_REQUIRED("TASK_STATE_INPUT_REQUIRED"),
    TASK_STATE_COMPLETED("TASK_STATE_COMPLETED"),
    TASK_STATE_CANCELED("TASK_STATE_CANCELED"),
    TASK_STATE_FAILED("TASK_STATE_FAILED"),
    TASK_STATE_REJECTED("TASK_STATE_REJECTED"),
    TASK_STATE_AUTH_REQUIRED("TASK_STATE_AUTH_REQUIRED"),
    ;

    fun isTerminal(): Boolean = this == TASK_STATE_COMPLETED ||
        this == TASK_STATE_CANCELED ||
        this == TASK_STATE_FAILED ||
        this == TASK_STATE_REJECTED
}

// ---------------------------------------------------------------------------
// Parts — REST uses a "text" or "file" or "data" property discriminator too,
// but with a slightly different shape than the JSON-RPC DTOs. Promptfoo
// accepts a bare `{ "text": "..." }` for a text part, which is what the
// proto-style oneof produces when serialized to JSON.
// ---------------------------------------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class RestPart(
    @JsonInclude(JsonInclude.Include.NON_NULL) val text: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val file: FileContent? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val data: Map<String, Any?>? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val metadata: Map<String, Any?>? = null,
)

// ---------------------------------------------------------------------------
// Message
// ---------------------------------------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class RestMessage(
    val role: RestRole,
    val parts: List<RestPart>,
    val messageId: String,
    @JsonInclude(JsonInclude.Include.NON_NULL) val taskId: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val contextId: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val referenceTaskIds: List<String>? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val extensions: List<String>? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val metadata: Map<String, Any?>? = null,
)

// ---------------------------------------------------------------------------
// Task
// ---------------------------------------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class RestTaskStatus(
    val state: RestTaskState,
    val timestamp: String,
    @JsonInclude(JsonInclude.Include.NON_NULL) val message: RestMessage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RestArtifact(
    val artifactId: String,
    val parts: List<RestPart>,
    @JsonInclude(JsonInclude.Include.NON_NULL) val name: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val description: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val extensions: List<String>? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val metadata: Map<String, Any?>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RestTask(
    val id: String,
    val contextId: String,
    val status: RestTaskStatus,
    @JsonInclude(JsonInclude.Include.NON_NULL) val history: List<RestMessage>? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val artifacts: List<RestArtifact>? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val metadata: Map<String, Any?>? = null,
)

// ---------------------------------------------------------------------------
// Request body (spec §11.4)
// ---------------------------------------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class RestSendMessageRequest(
    // Field name "message" per spec §11.4 (`SendMessageRequest.message`) —
    // also what promptfoo's `a2a` provider actually sends on the wire.
    val message: RestMessage,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val configuration: SendMessageConfiguration? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val metadata: Map<String, Any?>? = null,
)

// ---------------------------------------------------------------------------
// Streaming event payloads (spec §4.2 with REST binding kind values)
// ---------------------------------------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class RestTaskStatusUpdateEvent(
    val taskId: String,
    val contextId: String,
    val status: RestTaskStatus,
    val final: Boolean = false,
    @JsonInclude(JsonInclude.Include.NON_NULL) val metadata: Map<String, Any?>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RestTaskArtifactUpdateEvent(
    val taskId: String,
    val contextId: String,
    val artifact: RestArtifact,
    val append: Boolean = false,
    val lastChunk: Boolean = true,
    @JsonInclude(JsonInclude.Include.NON_NULL) val metadata: Map<String, Any?>? = null,
)

/**
 * Envelope for a stream event — mirrors the proto `StreamResponse` oneof.
 * Exactly one of the four fields is non-null in a given frame.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RestStreamResponse(
    @JsonInclude(JsonInclude.Include.NON_NULL) val task: RestTask? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val message: RestMessage? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val statusUpdate: RestTaskStatusUpdateEvent? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val artifactUpdate: RestTaskArtifactUpdateEvent? = null,
)
