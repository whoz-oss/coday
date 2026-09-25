/**
 * Forge routes — Epic/Story run projections, gates, and run creation.
 *
 * Covers:
 *   GET/POST  /api/forge/runs                         (and /api/factory/forge/runs)
 *   GET/POST  /api/forge/runs/:id/gates/G1
 *   GET/POST  /api/forge/runs/:id/gates/G2            (and /api/factory/forge/runs/:id/gates/G2)
 *   POST      /api/forge/runs/:id/gates/G1/decision   (and /api/factory/forge/...)
 *   GET/POST  /api/forge/runs/:epicRunId/stories/:storyRunId/executions
 *   GET/POST  /api/forge/runs/:epicRunId/stories/:storyRunId/oracles
 *   GET/POST  /api/forge/runs/:epicRunId/stories/:storyRunId/edits
 *   POST      /api/factory/forge/runs/create
 *
 * Returns true when the request was handled, false otherwise.
 */

import { join } from 'node:path'
import { sendError, isAllowedStoryEditRequestBody } from './http-utils.mjs'

/**
 * @param {{
 *   method: string,
 *   path: string,
 *   url: URL,
 *   readBody: () => Promise<object>,
 *   send: (status: number, body: unknown) => void,
 *   proxy: object,
 *   log?: Console,
 *   forgeLedger: object,
 *   forgeG2: object,
 *   forgeStoryAnalysis: object,
 *   forgeStoryEdit: object,
 *   forgeStoryOracles: object,
 *   forgeHumanDecision: object,
 *   forgeRoots: object,
 *   orchestratorDir: string,
 * }} ctx
 * @returns {Promise<boolean>}
 */
export async function handleForgeRunRequest({
  method,
  path,
  url,
  readBody,
  send,
  proxy,
  log = console,
  forgeLedger,
  forgeG2,
  forgeStoryAnalysis,
  forgeStoryEdit,
  forgeStoryOracles,
  forgeHumanDecision,
  forgeRoots,
  orchestratorDir,
}) {
  const { listForgeRunProjections, parseForgeLedger, projectForgeRun, createEpicRun } = forgeLedger
  const { evaluateG2 } = forgeG2
  const { executeStoryAnalysis } = forgeStoryAnalysis
  const { executeStoryEdit } = forgeStoryEdit
  const { executeStoryOracles, isAllowedStoryOracleRequestBody } = forgeStoryOracles
  const { recordHumanDecision } = forgeHumanDecision
  const { resolveForgeRoots, defaultRunStoreRoot, REPO_RUN_STORE_POLICY } = forgeRoots

  // ---------------------------------------------------------------------------
  // GET /api/forge/runs  and  GET /api/factory/forge/runs
  // ---------------------------------------------------------------------------
  if (method === 'GET' && (path === '/api/forge/runs' || path === '/api/factory/forge/runs')) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return sendError(send, 400, 'MISSING_NAMESPACE_ID', 'namespaceId query param is required'), true
    try {
      const runStoreRoot = await proxy.resolveRunStoreRoot(namespaceId)
      if (!runStoreRoot) return sendError(send, 422, 'NAMESPACE_REPO_UNAVAILABLE', 'Namespace not found or has no configPath configured'), true
      return send(200, listForgeRunProjections(runStoreRoot)), true
    } catch (err) {
      return sendError(send, 500, 'FORGE_STORAGE_FAILURE', String(err)), true
    }
  }

  // ---------------------------------------------------------------------------
  // Story executions: GET/POST /api/forge/runs/:epicRunId/stories/:storyRunId/executions
  // ---------------------------------------------------------------------------
  const forgeStoryExecutionsMatch = path.match(/^\/api\/forge\/runs\/([^/]+)\/stories\/([^/]+)\/executions$/)
  if (forgeStoryExecutionsMatch) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return sendError(send, 400, 'MISSING_NAMESPACE_ID', 'namespaceId query param is required'), true

    if (method === 'GET') {
      try {
        const runStoreRoot = await proxy.resolveRunStoreRoot(namespaceId)
        if (!runStoreRoot) return sendError(send, 422, 'NAMESPACE_REPO_UNAVAILABLE', 'Namespace not found or has no configPath configured'), true
        const projection = projectForgeRun(parseForgeLedger(join(runStoreRoot, `${forgeStoryExecutionsMatch[1]}.jsonl`)))
        const story = projection?.stories.find((item) => item.runId === forgeStoryExecutionsMatch[2])
        return story ? send(200, story.executions) : sendError(send, 404, 'STORY_RUN_NOT_FOUND', 'Story run not found.'), true
      } catch (err) { return sendError(send, 404, 'STORY_RUN_NOT_FOUND', String(err)), true }
    }

    if (method === 'POST') {
      const body = await readBody()
      if (Object.keys(body).some((key) => !['namespaceId', 'agentName', 'expectedSpecHash', 'supplement'].includes(key))) {
        return sendError(send, 400, 'INVALID_STORY_ANALYSIS_REQUEST', 'Unsupported Story analysis request field.'), true
      }
      try {
        const runStoreRoot = await proxy.resolveRunStoreRoot(namespaceId)
        if (!runStoreRoot) return sendError(send, 422, 'NAMESPACE_REPO_UNAVAILABLE', 'Namespace not found or has no configPath configured'), true
        const events = parseForgeLedger(join(runStoreRoot, `${forgeStoryExecutionsMatch[1]}.jsonl`))
        const start = events.find((event) => event.event === 'run_started')
        const result = await executeStoryAnalysis({
          roots: start.roots,
          epicRunId: forgeStoryExecutionsMatch[1],
          storyRunId: forgeStoryExecutionsMatch[2],
          namespaceId: body.namespaceId,
          agentName: body.agentName,
          supplement: body.supplement,
          expectedSpecHash: body.expectedSpecHash,
        })
        return send(201, result), true
      } catch (error) { return sendError(send, 409, 'STORY_ANALYSIS_FAILED', String(error.message ?? error)), true }
    }
  }

  // ---------------------------------------------------------------------------
  // Story oracles: GET/POST /api/forge/runs/:epicRunId/stories/:storyRunId/oracles
  // ---------------------------------------------------------------------------
  const forgeStoryOraclesMatch = path.match(/^\/api\/forge\/runs\/([^/]+)\/stories\/([^/]+)\/oracles$/)
  if (forgeStoryOraclesMatch) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return sendError(send, 400, 'MISSING_NAMESPACE_ID', 'namespaceId query param is required'), true

    if (method === 'GET') {
      try {
        const runStoreRoot = await proxy.resolveRunStoreRoot(namespaceId)
        if (!runStoreRoot) return sendError(send, 422, 'NAMESPACE_REPO_UNAVAILABLE', 'Namespace not found or has no configPath configured'), true
        const projection = projectForgeRun(parseForgeLedger(join(runStoreRoot, `${forgeStoryOraclesMatch[1]}.jsonl`)))
        const story = projection?.stories.find((item) => item.runId === forgeStoryOraclesMatch[2])
        return story ? send(200, story.oracleCampaigns) : sendError(send, 404, 'STORY_RUN_NOT_FOUND', 'Story run not found.'), true
      } catch (err) { return sendError(send, 404, 'STORY_RUN_NOT_FOUND', String(err)), true }
    }

    if (method === 'POST') {
      const body = await readBody()
      if (!isAllowedStoryOracleRequestBody(body)) return sendError(send, 400, 'INVALID_STORY_ORACLE_REQUEST', 'Unsupported Story oracle request field.'), true
      try {
        const runStoreRoot = await proxy.resolveRunStoreRoot(namespaceId)
        if (!runStoreRoot) return sendError(send, 422, 'NAMESPACE_REPO_UNAVAILABLE', 'Namespace not found or has no configPath configured'), true
        const events = parseForgeLedger(join(runStoreRoot, `${forgeStoryOraclesMatch[1]}.jsonl`))
        const start = events.find((event) => event.event === 'run_started')
        return send(201, await executeStoryOracles({ roots: start.roots, epicRunId: forgeStoryOraclesMatch[1], storyRunId: forgeStoryOraclesMatch[2], ...body })), true
      } catch (error) { return sendError(send, 409, 'STORY_ANALYSIS_FAILED', String(error.message ?? error)), true }
    }
  }

  // ---------------------------------------------------------------------------
  // Story edits: GET/POST /api/forge/runs/:epicRunId/stories/:storyRunId/edits
  // ---------------------------------------------------------------------------
  const forgeStoryEditsMatch = path.match(/^\/api\/forge\/runs\/([^/]+)\/stories\/([^/]+)\/edits$/)
  if (forgeStoryEditsMatch) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return sendError(send, 400, 'MISSING_NAMESPACE_ID', 'namespaceId query param is required'), true

    if (method === 'GET') {
      try {
        const runStoreRoot = await proxy.resolveRunStoreRoot(namespaceId)
        if (!runStoreRoot) return sendError(send, 422, 'NAMESPACE_REPO_UNAVAILABLE', 'Namespace not found or has no configPath configured'), true
        const projection = projectForgeRun(parseForgeLedger(join(runStoreRoot, `${forgeStoryEditsMatch[1]}.jsonl`)))
        const story = projection?.stories.find((item) => item.runId === forgeStoryEditsMatch[2])
        return story ? send(200, story.edits) : sendError(send, 404, 'STORY_RUN_NOT_FOUND', 'Story run not found.'), true
      } catch (err) { return sendError(send, 404, 'STORY_RUN_NOT_FOUND', String(err)), true }
    }

    if (method === 'POST') {
      const body = await readBody()
      if (!isAllowedStoryEditRequestBody(body)) return sendError(send, 400, 'INVALID_STORY_EDIT_REQUEST', 'Unsupported Story edit request field.'), true
      try {
        const runStoreRoot = await proxy.resolveRunStoreRoot(namespaceId)
        if (!runStoreRoot) return sendError(send, 422, 'NAMESPACE_REPO_UNAVAILABLE', 'Namespace not found or has no configPath configured'), true
        const events = parseForgeLedger(join(runStoreRoot, `${forgeStoryEditsMatch[1]}.jsonl`))
        const start = events.find((event) => event.event === 'run_started')
        const result = await executeStoryEdit({ roots: start.roots, epicRunId: forgeStoryEditsMatch[1], storyRunId: forgeStoryEditsMatch[2], ...body })
        return send(201, result), true
      } catch (error) { return sendError(send, 409, 'STORY_ANALYSIS_FAILED', String(error.message ?? error)), true }
    }
  }

  // ---------------------------------------------------------------------------
  // Gate G1: GET /api/forge/runs/:id/gates/G1
  // ---------------------------------------------------------------------------
  const forgeG1Match = path.match(/^\/api\/forge\/runs\/([^/]+)\/gates\/G1$/)
  if (method === 'GET' && forgeG1Match) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return sendError(send, 400, 'MISSING_NAMESPACE_ID', 'namespaceId query param is required'), true
    try {
      const runStoreRoot = await proxy.resolveRunStoreRoot(namespaceId)
      if (!runStoreRoot) return sendError(send, 422, 'NAMESPACE_REPO_UNAVAILABLE', 'Namespace not found or has no configPath configured'), true
      const projection = projectForgeRun(parseForgeLedger(join(runStoreRoot, `${forgeG1Match[1]}.jsonl`)))
      if (!projection) return sendError(send, 404, 'FORGE_RUN_NOT_FOUND', 'Forge run not found.'), true
      return send(200, projection.gates.find((gate) => gate.gate === 'G1') ?? null), true
    } catch (err) { return sendError(send, 404, 'STORY_RUN_NOT_FOUND', String(err)), true }
  }

  // ---------------------------------------------------------------------------
  // Gate G2: GET/POST /api/forge/runs/:id/gates/G2  (and /api/factory/forge/...)
  // ---------------------------------------------------------------------------
  const forgeG2Match = path.match(/^\/api\/(?:factory\/)?forge\/runs\/([^/]+)\/gates\/G2$/)
  if (forgeG2Match) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return sendError(send, 400, 'MISSING_NAMESPACE_ID', 'namespaceId query param is required'), true

    if (method === 'GET') {
      try {
        const runStoreRoot = await proxy.resolveRunStoreRoot(namespaceId)
        if (!runStoreRoot) return sendError(send, 422, 'NAMESPACE_REPO_UNAVAILABLE', 'Namespace not found or has no configPath configured'), true
        const projection = projectForgeRun(parseForgeLedger(join(runStoreRoot, `${forgeG2Match[1]}.jsonl`)))
        if (!projection) return sendError(send, 404, 'FORGE_RUN_NOT_FOUND', 'Forge run not found.'), true
        return send(200, projection.gates.find((gate) => gate.gate === 'G2') ?? { gate: 'G2', status: 'not_evaluated' }), true
      } catch (err) { return sendError(send, 404, 'STORY_RUN_NOT_FOUND', String(err)), true }
    }

    if (method === 'POST') {
      const body = await readBody()
      try {
        const runStoreRoot = await proxy.resolveRunStoreRoot(namespaceId)
        if (!runStoreRoot) return sendError(send, 422, 'NAMESPACE_REPO_UNAVAILABLE', 'Namespace not found or has no configPath configured'), true
        const events = parseForgeLedger(join(runStoreRoot, `${forgeG2Match[1]}.jsonl`))
        const start = events.find((event) => event.event === 'run_started' && event.runId === forgeG2Match[1])
        if (!start?.roots) return sendError(send, 409, 'FORGE_RUN_ROOTS_MISSING', 'Forge run roots are missing from the ledger.'), true
        const result = evaluateG2({ roots: start.roots, runId: forgeG2Match[1], specPath: body.specPath })
        return send(result.status === 'recorded' ? 201 : (result.status === 'conflict' ? 409 : 200), result), true
      } catch (error) { return sendError(send, 409, 'STORY_ANALYSIS_FAILED', String(error.message ?? error)), true }
    }
  }

  // ---------------------------------------------------------------------------
  // Gate G1 decision: POST /api/forge/runs/:id/gates/G1/decision  (and /api/factory/forge/...)
  //
  // actorId and authorityId are read from trusted headers, never from the body.
  // ---------------------------------------------------------------------------
  const forgeG1DecisionMatch = path.match(/^\/api\/(?:factory\/)?forge\/runs\/([^/]+)\/gates\/G1\/decision$/)
  if (method === 'POST' && forgeG1DecisionMatch) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return sendError(send, 400, 'MISSING_NAMESPACE_ID', 'namespaceId query param is required'), true
    const body = await readBody()
    // actorId / authorityId are injected by the caller (from trusted headers in server.mjs)
    const actorId = body._actorId
    const authorityId = body._authorityId
    const cleanBody = Object.fromEntries(Object.entries(body).filter(([k]) => !k.startsWith('_')))
    const identityPort = {
      actorId: async () => Array.isArray(actorId) ? actorId[0] : actorId,
      authorize: async ({ actorId: verifiedActor }) =>
        typeof authorityId === 'string' && authorityId && verifiedActor ? { authorityId } : null,
    }
    try {
      const runStoreRoot = await proxy.resolveRunStoreRoot(namespaceId)
      if (!runStoreRoot) return sendError(send, 422, 'NAMESPACE_REPO_UNAVAILABLE', 'Namespace not found or has no configPath configured'), true
      const roots = { runStoreRoot }
      const result = await recordHumanDecision({ roots, runId: forgeG1DecisionMatch[1], decision: cleanBody, identityPort })
      return send(result.status === 'recorded' ? 201 : 200, result), true
    } catch (error) { return sendError(send, 409, 'STORY_ANALYSIS_FAILED', String(error.message ?? error)), true }
  }

  // ---------------------------------------------------------------------------
  // POST /api/factory/forge/runs/create
  // Body: { roots: { orchestratorRoot?, repoRoot }, epic: { id, kind }, stories: [...], runId? }
  // ---------------------------------------------------------------------------
  if (method === 'POST' && path === '/api/factory/forge/runs/create') {
    const body = await readBody()
    if (!body.roots?.repoRoot) return sendError(send, 400, 'INVALID_FORGE_RUN_REQUEST', 'roots.repoRoot is required'), true
    if (!body.epic?.id || !body.epic?.kind) return sendError(send, 400, 'INVALID_FORGE_RUN_REQUEST', 'epic.id and epic.kind are required'), true
    if (!Array.isArray(body.stories) || body.stories.length === 0) {
      return sendError(send, 400, 'INVALID_FORGE_RUN_REQUEST', 'stories must be a non-empty array'), true
    }
    try {
      const orchestratorRoot = body.roots.orchestratorRoot ?? orchestratorDir
      const repoRoot = body.roots.repoRoot
      const roots = resolveForgeRoots({
        ...body.roots,
        orchestratorRoot,
        runStoreRoot: defaultRunStoreRoot(repoRoot),
        runStorePolicy: REPO_RUN_STORE_POLICY,
      })
      const result = createEpicRun({
        roots,
        epic: body.epic,
        stories: body.stories,
        ...(body.runId ? { runId: body.runId } : {}),
      })
      return send(201, { runId: result.runId, filePath: result.filePath }), true
    } catch (err) {
      return sendError(send, 400, 'INVALID_FORGE_RUN_REQUEST', String(err.message ?? err)), true
    }
  }

  return false
}
