package io.whozoss.agentos.a2a

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.a2a.dto.JsonRpcError
import io.whozoss.agentos.a2a.dto.JsonRpcRequest
import io.whozoss.agentos.a2a.dto.JsonRpcResponse
import io.whozoss.agentos.a2a.dto.SendMessageParams
import io.whozoss.agentos.a2a.dto.TaskIdParams
import io.whozoss.agentos.a2a.dto.TaskQueryParams
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.exception.ResourceNotFoundException
import mu.KLogging
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Dispatches A2A JSON-RPC methods to [A2AService].
 *
 * Supported methods (prototype):
 * - `message/send`    → returns Task snapshot
 * - `message/stream`  → NOT handled here; the controller opens SSE directly.
 *                       This handler validates the request shape only if called.
 * - `tasks/get`       → returns Task snapshot
 * - `tasks/cancel`    → cancels + returns Task snapshot
 *
 * Unsupported methods return a JSON-RPC error (spec §9.5).
 * See docs/a2a.md for the list of methods intentionally left out.
 */
@Component
class A2AJsonRpcHandler(
    private val a2aService: A2AService,
    private val objectMapper: ObjectMapper,
) {
    fun handle(
        namespaceId: UUID,
        config: AgentConfig,
        request: JsonRpcRequest,
    ): JsonRpcResponse {
        if (request.jsonrpc != "2.0") {
            return errorResponse(request, JsonRpcError.INVALID_REQUEST, "jsonrpc must be '2.0'")
        }

        return try {
            when (request.method) {
                "message/send" -> handleSendMessage(namespaceId, config, request)
                "message/stream" -> errorResponse(
                    request,
                    JsonRpcError.INVALID_REQUEST,
                    "message/stream must be issued as an SSE request, not a regular JSON-RPC call",
                )
                "tasks/get" -> handleGetTask(request)
                "tasks/cancel" -> handleCancelTask(request)
                else -> errorResponse(
                    request,
                    JsonRpcError.METHOD_NOT_FOUND,
                    "Method '${request.method}' is not supported by this prototype",
                )
            }
        } catch (e: ResourceNotFoundException) {
            errorResponse(request, JsonRpcError.TASK_NOT_FOUND, e.message ?: "Not found")
        } catch (e: IllegalArgumentException) {
            errorResponse(request, JsonRpcError.INVALID_PARAMS, e.message ?: "Invalid params")
        } catch (e: IllegalStateException) {
            // Used by A2AService.cancelTask when a case is already terminal.
            errorResponse(request, JsonRpcError.TASK_NOT_CANCELABLE, e.message ?: "Task not cancelable")
        } catch (e: Exception) {
            logger.error("A2A JSON-RPC handler failed for method ${request.method}", e)
            errorResponse(request, JsonRpcError.INTERNAL_ERROR, e.message ?: "Internal error")
        }
    }

    /**
     * Parse and validate the params of a `message/send` or `message/stream` request.
     * Exposed so the SSE controller can reuse it before opening the stream.
     */
    fun parseSendMessageParams(request: JsonRpcRequest): SendMessageParams {
        val paramsNode = request.params
            ?: throw IllegalArgumentException("Missing 'params' object")
        return objectMapper.treeToValue(paramsNode, SendMessageParams::class.java)
    }

    // ------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------

    private fun handleSendMessage(
        namespaceId: UUID,
        config: AgentConfig,
        request: JsonRpcRequest,
    ): JsonRpcResponse {
        val params = parseSendMessageParams(request)
        val task = a2aService.sendMessage(namespaceId, config, params.message)
        return JsonRpcResponse(id = request.id, result = task)
    }

    private fun handleGetTask(request: JsonRpcRequest): JsonRpcResponse {
        val paramsNode = request.params
            ?: throw IllegalArgumentException("Missing 'params' object")
        val params = objectMapper.treeToValue(paramsNode, TaskQueryParams::class.java)
        val caseId = parseTaskId(params.id)
        val task = a2aService.getTask(caseId)
        return JsonRpcResponse(id = request.id, result = task)
    }

    private fun handleCancelTask(request: JsonRpcRequest): JsonRpcResponse {
        val paramsNode = request.params
            ?: throw IllegalArgumentException("Missing 'params' object")
        val params = objectMapper.treeToValue(paramsNode, TaskIdParams::class.java)
        val caseId = parseTaskId(params.id)
        val task = a2aService.cancelTask(caseId)
        return JsonRpcResponse(id = request.id, result = task)
    }

    private fun parseTaskId(raw: String): UUID =
        runCatching { UUID.fromString(raw) }
            .getOrElse { throw IllegalArgumentException("Invalid task id: $raw (expected UUID)") }

    private fun errorResponse(request: JsonRpcRequest, code: Int, message: String): JsonRpcResponse =
        JsonRpcResponse(id = request.id, error = JsonRpcError(code = code, message = message))

    companion object : KLogging()
}
