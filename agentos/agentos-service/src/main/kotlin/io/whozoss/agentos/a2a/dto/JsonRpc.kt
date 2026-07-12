package io.whozoss.agentos.a2a.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode

/**
 * JSON-RPC 2.0 request envelope used by the A2A JSON-RPC binding (spec §9).
 *
 * `params` is kept as a raw [JsonNode] so each method handler can deserialize it into
 * its own strongly-typed params object. This keeps the dispatcher free of generic types.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonNode? = null,
    /** Spec allows string, number or null. We accept anything and echo it verbatim. */
    val id: JsonNode? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonNode? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val result: Any? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val error: JsonRpcError? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JsonRpcError(
    val code: Int,
    val message: String,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val data: Any? = null,
) {
    companion object {
        // JSON-RPC 2.0 standard codes
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        // A2A-specific codes (spec §9.5 / §3.3.2)
        const val TASK_NOT_FOUND = -32001
        const val TASK_NOT_CANCELABLE = -32002
        const val PUSH_NOTIFICATION_NOT_SUPPORTED = -32003
        const val UNSUPPORTED_OPERATION = -32004
        const val CONTENT_TYPE_NOT_SUPPORTED = -32005
        const val INVALID_AGENT_RESPONSE = -32006
    }
}
