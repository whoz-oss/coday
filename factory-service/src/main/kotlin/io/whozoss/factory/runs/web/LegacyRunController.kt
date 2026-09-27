package io.whozoss.factory.runs.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.forge.web.forgeError
import io.whozoss.factory.forge.web.resolveForgeCaller
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.runs.service.LegacyRunService
import io.whozoss.factory.web.TrustContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Legacy workflow JSONL runs and review gates.
 *
 * Port of `factory/dashboard/run-routes.mjs` (minus the SSE route, which lives
 * in [LegacyRunSseController]). The durable state remains the append-only JSONL
 * ledger under the configured runs directory.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "runs", description = "Legacy workflow JSONL runs")
class LegacyRunController(
    private val runs: LegacyRunService,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    private fun requireCaller(trustContext: TrustContext?) = resolveForgeCaller(trustContext, tenantScopeProvider)

    // ----- /api/runs --------------------------------------------------------

    @GetMapping(path = ["/runs"])
    @Operation(summary = "List legacy runs.")
    fun list(@Parameter(hidden = true) trustContext: TrustContext?): List<Map<String, Any?>> {
        requireCaller(trustContext)
        return runs.listRuns()
    }

    @PostMapping(path = ["/runs"])
    @Operation(summary = "Launch a legacy run (returns the pid).")
    fun launch(
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<Any> {
        requireCaller(trustContext)
        val result = runs.launchRun(body ?: emptyMap())
        val error = result["error"]
        if (error != null) {
            return ResponseEntity.status(400).body(
                mapOf("error" to mapOf("code" to "INVALID_RUN_REQUEST", "message" to error, "details" to null)),
            )
        }
        return ResponseEntity.status(202).body(result)
    }

    @GetMapping(path = ["/runs/{id}"])
    @Operation(summary = "Read a legacy run detail.")
    fun detail(@PathVariable id: String, @Parameter(hidden = true) trustContext: TrustContext?): Any {
        requireCaller(trustContext)
        return runs.detailRun(id) ?: forgeError(404, "RUN_NOT_FOUND", "Run introuvable")
    }

    // ----- /api/factory/runs ------------------------------------------------

    @GetMapping(path = ["/factory/runs"])
    @Operation(summary = "List legacy runs, optionally filtered by namespace.")
    fun factoryList(
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): List<Map<String, Any?>> {
        requireCaller(trustContext)
        val all = runs.listRuns()
        return if (namespaceId.isNullOrEmpty()) all else all.filter { it["namespaceId"] == namespaceId }
    }

    @PostMapping(path = ["/factory/runs"])
    @Operation(summary = "Launch a legacy run and wait for its runId.")
    fun factoryLaunch(
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<Any> {
        requireCaller(trustContext)
        val result = runs.launchRun(body ?: emptyMap())
        val error = result["error"]
        if (error != null) {
            return ResponseEntity.status(400).body(
                mapOf("error" to mapOf("code" to "INVALID_RUN_REQUEST", "message" to error, "details" to null)),
            )
        }
        return ResponseEntity.status(202).body(result)
    }

    @GetMapping(path = ["/factory/runs/{id}"])
    @Operation(summary = "Read a legacy run detail (alias).")
    fun factoryDetail(@PathVariable id: String, @Parameter(hidden = true) trustContext: TrustContext?): Any {
        requireCaller(trustContext)
        return runs.detailRun(id) ?: forgeError(404, "RUN_NOT_FOUND", "Run introuvable")
    }

    @PostMapping(path = ["/factory/runs/{id}/stop"])
    @Operation(summary = "Stop a legacy run.")
    fun stop(@PathVariable id: String, @Parameter(hidden = true) trustContext: TrustContext?): ResponseEntity<Any> {
        requireCaller(trustContext)
        val result = runs.stopRun(id)
        val status = result["status"] as? Int
        return if (status != null) {
            ResponseEntity.status(status).body(
                mapOf(
                    "error" to mapOf(
                        "code" to result["code"],
                        "message" to result["message"],
                        "details" to null,
                    ),
                ),
            )
        } else {
            ResponseEntity.status(202).body(result)
        }
    }

    @GetMapping(path = ["/factory/runs/{id}/review-gate"])
    @Operation(summary = "Read the review-gate state of a legacy run.")
    fun reviewGate(@PathVariable id: String, @Parameter(hidden = true) trustContext: TrustContext?): Any {
        requireCaller(trustContext)
        val result = runs.reviewGate(id)
        if (result["status"] == 404) forgeError(404, "RUN_NOT_FOUND", "Run not found.")
        return result
    }

    @PostMapping(path = ["/factory/runs/{id}/review-gate/reply"])
    @Operation(summary = "Deliver a review-gate decision.")
    fun reviewGateReply(
        @PathVariable id: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<Any> {
        requireCaller(trustContext)
        val request = body ?: emptyMap()
        val gateInstanceId = request["gateInstanceId"]
        if (gateInstanceId !is String || gateInstanceId.isEmpty()) {
            return ResponseEntity.status(400).body(errorEnvelope(400, "INVALID_GATE_REPLY", "gateInstanceId is required."))
        }
        val decision = request["decision"]
        if (decision !is String || decision.isEmpty()) {
            return ResponseEntity.status(400).body(errorEnvelope(400, "INVALID_GATE_REPLY", "decision is required."))
        }
        val message = request["message"]
        if (message != null && message !is String) {
            return ResponseEntity.status(400).body(errorEnvelope(400, "INVALID_GATE_REPLY", "message must be a string."))
        }
        val result = runs.replyGate(id, gateInstanceId, decision, message ?: "")
        if (result["ok"] != true) {
            val status = (result["status"] as? Int) ?: 400
            return ResponseEntity.status(status).body(
                errorEnvelope(status, "INVALID_GATE_REPLY", (result["error"] as? String) ?: "Invalid gate reply"),
            )
        }
        return ResponseEntity.ok(mapOf("ok" to true, "decision" to decision))
    }

    // ----- deprecated global review-gate ------------------------------------

    @GetMapping(path = ["/review-gate"])
    @Operation(summary = "Deprecated global review-gate route.")
    fun deprecatedGate(@Parameter(hidden = true) trustContext: TrustContext?): ResponseEntity<Any> {
        requireCaller(trustContext)
        return ResponseEntity.status(410).body(
            errorEnvelope(
                410,
                "DEPRECATED_ROUTE",
                "DEPRECATED: global /api/review-gate removed. Use GET /api/factory/runs/:runId/review-gate instead.",
            ),
        )
    }

    @PostMapping(path = ["/review-gate/reply"])
    @Operation(summary = "Deprecated global review-gate reply route.")
    fun deprecatedGateReply(
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<Any> {
        requireCaller(trustContext)
        return ResponseEntity.status(410).body(
            errorEnvelope(
                410,
                "DEPRECATED_ROUTE",
                "DEPRECATED: global /api/review-gate/reply removed. Use POST /api/factory/runs/:runId/review-gate/reply instead.",
            ),
        )
    }

    private fun errorEnvelope(status: Int, code: String, message: String): Map<String, Any?> =
        mapOf("error" to mapOf("code" to code, "message" to message, "details" to null))
}
