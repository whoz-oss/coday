package io.whozoss.factory.forge.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.forge.domain.asMap
import io.whozoss.factory.forge.port.AgentOsProxyClient
import io.whozoss.factory.forge.service.CreateForgeRunCommand
import io.whozoss.factory.forge.service.ForgeGateService
import io.whozoss.factory.forge.service.ForgeRunService
import io.whozoss.factory.forge.service.StoryOperationService
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Path

/**
 * Forge Epic/Story run projections, gates and run creation.
 *
 * Port of `factory/dashboard/forge-routes.mjs`. Identity is resolved from the
 * verified [TrustContext] (`401 TRUST_CONTEXT_UNAVAILABLE` when missing); the
 * AgentOS namespace is supplied by the `namespaceId` query parameter exactly like
 * the Node routes. Ledger state stays file-based (JSONL).
 */
@RestController
@RequestMapping("/api")
@Tag(name = "forge", description = "Forge/BMAD runs, gates and story operations")
class ForgeRunController(
    private val runService: ForgeRunService,
    private val gateService: ForgeGateService,
    private val storyService: StoryOperationService,
    private val proxy: AgentOsProxyClient,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    private fun caller(trustContext: TrustContext?): ForgeCaller = resolveForgeCaller(trustContext, tenantScopeProvider)

    private fun runStoreRoot(namespaceId: String, externalUserId: String?): String =
        proxy.resolveRunStoreRoot(namespaceId, externalUserId)
            ?: forgeError(422, "NAMESPACE_REPO_UNAVAILABLE", "Namespace not found or has no configPath configured")

    private fun ledgerFor(runStoreRoot: String, runId: String): String = Path.of(runStoreRoot, "$runId.jsonl").toString()

    // -----------------------------------------------------------------------
    // GET /api/forge/runs  and  GET /api/factory/forge/runs
    // -----------------------------------------------------------------------

    @GetMapping(path = ["/forge/runs", "/factory/forge/runs"])
    @Operation(summary = "List Forge run projections for a namespace.")
    fun list(
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): Any {
        val caller = caller(trustContext)
        val ns = requireNamespaceQuery(namespaceId)
        return try {
            runService.listProjections(runStoreRoot(ns, caller.externalUserId))
        } catch (error: Exception) {
            forgeStorageFailure(error)
        }
    }

    // -----------------------------------------------------------------------
    // POST /api/factory/forge/runs/create
    // -----------------------------------------------------------------------

    @PostMapping(path = ["/factory/forge/runs/create"])
    @Operation(summary = "Create a Forge EpicRun ledger.")
    fun create(
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<Any> {
        caller(trustContext)
        val request = body ?: forgeError(400, "INVALID_FORGE_RUN_REQUEST", "Request body is required")
        val roots = asMap(request["roots"])
            ?: forgeError(400, "INVALID_FORGE_RUN_REQUEST", "roots.repoRoot is required")
        if ((roots["repoRoot"] as? String).isNullOrBlank()) {
            forgeError(400, "INVALID_FORGE_RUN_REQUEST", "roots.repoRoot is required")
        }
        val epic = asMap(request["epic"])
            ?: forgeError(400, "INVALID_FORGE_RUN_REQUEST", "epic.id and epic.kind are required")
        if ((epic["id"] as? String).isNullOrBlank() || (epic["kind"] as? String).isNullOrBlank()) {
            forgeError(400, "INVALID_FORGE_RUN_REQUEST", "epic.id and epic.kind are required")
        }
        val stories = (request["stories"] as? List<*>)?.mapNotNull { asMap(it) }
        if (stories.isNullOrEmpty()) {
            forgeError(400, "INVALID_FORGE_RUN_REQUEST", "stories must be a non-empty array")
        }
        return try {
            val result = runService.createEpicRun(
                CreateForgeRunCommand(
                    roots = roots,
                    epic = epic,
                    stories = stories,
                    runId = request["runId"] as? String,
                ),
                defaultOrchestratorRoot(),
            )
            ResponseEntity.status(201).body(result)
        } catch (error: io.whozoss.factory.forge.domain.ForgeCodedException) {
            forgeError(400, "INVALID_FORGE_RUN_REQUEST", error.message ?: "invalid forge run request")
        }
    }

    private fun defaultOrchestratorRoot(): String = System.getProperty("user.dir")

    // -----------------------------------------------------------------------
    // Gate G1
    // -----------------------------------------------------------------------

    @GetMapping(path = ["/forge/runs/{id}/gates/G1"])
    @Operation(summary = "Read the G1 gate of a Forge run.")
    fun g1(
        @PathVariable id: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): Any? {
        val caller = caller(trustContext)
        val ns = requireNamespaceQuery(namespaceId)
        val root = runStoreRoot(ns, caller.externalUserId)
        val projection = try {
            runService.project(ledgerFor(root, id))
        } catch (_: Exception) {
            forgeError(404, "STORY_RUN_NOT_FOUND", "Forge run not found.")
        } ?: forgeError(404, "FORGE_RUN_NOT_FOUND", "Forge run not found.")
        return (projection["gates"] as? List<*>)?.mapNotNull { asMap(it) }?.firstOrNull { it["gate"] == "G1" }
    }

    // -----------------------------------------------------------------------
    // Gate G2
    // -----------------------------------------------------------------------

    @GetMapping(path = ["/forge/runs/{id}/gates/G2", "/factory/forge/runs/{id}/gates/G2"])
    @Operation(summary = "Read the G2 gate of a Forge run.")
    fun g2(
        @PathVariable id: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): Any? {
        val caller = caller(trustContext)
        val ns = requireNamespaceQuery(namespaceId)
        val root = runStoreRoot(ns, caller.externalUserId)
        val projection = try {
            runService.project(ledgerFor(root, id))
        } catch (_: Exception) {
            forgeError(404, "STORY_RUN_NOT_FOUND", "Forge run not found.")
        } ?: forgeError(404, "FORGE_RUN_NOT_FOUND", "Forge run not found.")
        return (projection["gates"] as? List<*>)?.mapNotNull { asMap(it) }?.firstOrNull { it["gate"] == "G2" }
            ?: mapOf("gate" to "G2", "status" to "not_evaluated")
    }

    @PostMapping(path = ["/forge/runs/{id}/gates/G2", "/factory/forge/runs/{id}/gates/G2"])
    @Operation(summary = "Evaluate the deterministic G2 gate against a spec file.")
    fun evaluateG2(
        @PathVariable id: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<Any> {
        val caller = caller(trustContext)
        val ns = requireNamespaceQuery(namespaceId)
        val root = runStoreRoot(ns, caller.externalUserId)
        val events = runService.readEvents(mapOf("runStoreRoot" to root), id)
        val start = events.firstOrNull { it["event"] == "run_started" && it["runId"] == id }
        val roots = asMap(start?.get("roots"))
            ?: forgeError(409, "FORGE_RUN_ROOTS_MISSING", "Forge run roots are missing from the ledger.")
        val specPath = body?.get("specPath") as? String
            ?: forgeError(400, "G2_SPEC_PATH_INVALID", "specPath is required")
        val result = gateService.evaluateG2(roots, id, specPath)
        val status = when (result["status"]) {
            "recorded" -> 201
            "conflict" -> 409
            else -> 200
        }
        return ResponseEntity.status(status).body(result)
    }

    // -----------------------------------------------------------------------
    // Gate G1 decision
    // -----------------------------------------------------------------------

    @PostMapping(path = ["/forge/runs/{id}/gates/G1/decision", "/factory/forge/runs/{id}/gates/G1/decision"])
    @Operation(summary = "Record a human G1 decision.")
    fun g1Decision(
        @PathVariable id: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<Any> {
        val caller = caller(trustContext)
        val ns = requireNamespaceQuery(namespaceId)
        val root = runStoreRoot(ns, caller.externalUserId)
        val result = try {
            runService.recordHumanDecision(
                roots = mapOf("runStoreRoot" to root),
                runId = id,
                decision = body ?: emptyMap(),
                actorId = caller.actorId,
                authorityId = caller.authorityId,
            )
        } catch (error: io.whozoss.factory.forge.domain.ForgeCodedException) {
            forgeError(409, "STORY_ANALYSIS_FAILED", error.message ?: "G1 decision failed")
        }
        return ResponseEntity.status(if (result["status"] == "recorded") 201 else 200).body(result)
    }

    // -----------------------------------------------------------------------
    // Story executions / oracles / edits (GET slices + POST phases)
    // -----------------------------------------------------------------------

    @GetMapping(path = ["/forge/runs/{epicRunId}/stories/{storyRunId}/executions"])
    @Operation(summary = "List Story executions.")
    fun executions(
        @PathVariable epicRunId: String,
        @PathVariable storyRunId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): Any? = storySlice(epicRunId, storyRunId, namespaceId, trustContext, "executions")

    @GetMapping(path = ["/forge/runs/{epicRunId}/stories/{storyRunId}/oracles"])
    @Operation(summary = "List Story oracle campaigns.")
    fun oracles(
        @PathVariable epicRunId: String,
        @PathVariable storyRunId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): Any? = storySlice(epicRunId, storyRunId, namespaceId, trustContext, "oracleCampaigns")

    @GetMapping(path = ["/forge/runs/{epicRunId}/stories/{storyRunId}/edits"])
    @Operation(summary = "List Story edits.")
    fun edits(
        @PathVariable epicRunId: String,
        @PathVariable storyRunId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): Any? = storySlice(epicRunId, storyRunId, namespaceId, trustContext, "edits")

    private fun storySlice(
        epicRunId: String,
        storyRunId: String,
        namespaceId: String?,
        trustContext: TrustContext?,
        field: String,
    ): Any? {
        val caller = caller(trustContext)
        val ns = requireNamespaceQuery(namespaceId)
        val root = runStoreRoot(ns, caller.externalUserId)
        val projection = try {
            runService.project(ledgerFor(root, epicRunId))
        } catch (_: Exception) {
            forgeError(404, "STORY_RUN_NOT_FOUND", "Story run not found.")
        } ?: forgeError(404, "STORY_RUN_NOT_FOUND", "Story run not found.")
        val story = (projection["stories"] as? List<*>)?.mapNotNull { asMap(it) }?.firstOrNull { it["runId"] == storyRunId }
            ?: forgeError(404, "STORY_RUN_NOT_FOUND", "Story run not found.")
        return story[field]
    }

    @PostMapping(path = ["/forge/runs/{epicRunId}/stories/{storyRunId}/executions"])
    @Operation(summary = "Execute the read-only Story analysis phase.")
    fun executeAnalysis(
        @PathVariable epicRunId: String,
        @PathVariable storyRunId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<Any> {
        val caller = caller(trustContext)
        val ns = requireNamespaceQuery(namespaceId)
        val allowed = setOf("namespaceId", "agentName", "expectedSpecHash", "supplement")
        val request = body ?: emptyMap()
        if (request.keys.any { it !in allowed }) {
            forgeError(400, "INVALID_STORY_ANALYSIS_REQUEST", "Unsupported Story analysis request field.")
        }
        val root = runStoreRoot(ns, caller.externalUserId)
        return try {
            val result = storyService.executeStoryAnalysis(
                runStoreRoot = root,
                epicRunId = epicRunId,
                storyRunId = storyRunId,
                namespaceId = request["namespaceId"] as? String ?: ns,
                agentName = request["agentName"] as? String ?: "",
                expectedSpecHash = request["expectedSpecHash"] as? String,
                storySpecHash = null,
                supplement = request["supplement"] as? String,
            )
            ResponseEntity.status(201).body(result)
        } catch (error: io.whozoss.factory.forge.domain.ForgeCodedException) {
            forgeError(409, "STORY_ANALYSIS_FAILED", error.message ?: "Story analysis failed")
        }
    }

    @PostMapping(path = ["/forge/runs/{epicRunId}/stories/{storyRunId}/edits"])
    @Operation(summary = "Execute the write-enabled Story edit phase.")
    fun executeEdit(
        @PathVariable epicRunId: String,
        @PathVariable storyRunId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<Any> {
        val caller = caller(trustContext)
        val ns = requireNamespaceQuery(namespaceId)
        val request = body ?: emptyMap()
        if (!io.whozoss.factory.forge.domain.ForgeStoryOperations.isAllowedStoryEditRequestBody(request)) {
            forgeError(400, "INVALID_STORY_EDIT_REQUEST", "Unsupported Story edit request field.")
        }
        val root = runStoreRoot(ns, caller.externalUserId)
        return try {
            val result = storyService.executeStoryEdit(
                runStoreRoot = root,
                epicRunId = epicRunId,
                storyRunId = storyRunId,
                analysisExecutionId = request["analysisExecutionId"] as? String ?: "",
                namespaceId = request["namespaceId"] as? String ?: ns,
                agentName = request["agentName"] as? String ?: "",
                expectedSpecHash = request["expectedSpecHash"] as? String,
                storySpecHash = request["storySpecHash"] as? String,
                supplement = request["supplement"] as? String,
            )
            ResponseEntity.status(201).body(result)
        } catch (error: io.whozoss.factory.forge.domain.ForgeCodedException) {
            forgeError(409, "STORY_ANALYSIS_FAILED", error.message ?: "Story edit failed")
        }
    }

    @PostMapping(path = ["/forge/runs/{epicRunId}/stories/{storyRunId}/oracles"])
    @Operation(summary = "Execute the Story oracle campaign (G3).")
    fun executeOracles(
        @PathVariable epicRunId: String,
        @PathVariable storyRunId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<Any> {
        val caller = caller(trustContext)
        val ns = requireNamespaceQuery(namespaceId)
        val request = body ?: emptyMap()
        if (!io.whozoss.factory.forge.domain.ForgeStoryOperations.isAllowedStoryOracleRequestBody(request)) {
            forgeError(400, "INVALID_STORY_ORACLE_REQUEST", "Unsupported Story oracle request field.")
        }
        val root = runStoreRoot(ns, caller.externalUserId)
        return try {
            val result = storyService.executeStoryOracles(
                runStoreRoot = root,
                epicRunId = epicRunId,
                storyRunId = storyRunId,
                editId = request["editId"] as? String ?: "",
                expectedSpecHash = request["expectedSpecHash"] as? String ?: "",
                attempt = (request["attempt"] as? Number)?.toInt() ?: 1,
            )
            ResponseEntity.status(201).body(result)
        } catch (error: io.whozoss.factory.forge.domain.ForgeCodedException) {
            forgeError(409, "STORY_ANALYSIS_FAILED", error.message ?: "Story oracle campaign failed")
        }
    }
}
