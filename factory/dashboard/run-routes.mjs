/**
 * Run router — legacy workflow JSONL runs, SSE streaming, review gates.
 *
 * Covers:
 *   GET      /api/runs                              — list runs
 *   GET      /api/runs/:id                          — run detail
 *   POST     /api/runs                              — launch run (returns { pid })
 *   GET      /api/runs/:id/stream                   — SSE (live stdout)
 *   GET      /api/factory/runs[?namespaceId=]       — list runs (alias, optional ns filter)
 *   GET      /api/factory/runs/:id                  — run detail alias
 *   POST     /api/factory/runs                      — launch run, waits for runId
 *   POST     /api/factory/runs/:id/stop             — send SIGTERM
 *   GET      /api/factory/runs/:id/stream           — SSE alias
 *   GET      /api/factory/runs/:id/review-gate      — gate state
 *   POST     /api/factory/runs/:id/review-gate/reply— deliver decision
 *   GET      /api/review-gate                       — 410 Gone (deprecated)
 *   POST     /api/review-gate/reply                 — 410 Gone (deprecated)
 *
 * Exported for backward compat: parseJsonl, reconstructPhases
 *
 * Factory function signature:
 *   createRunRouter({ runsDir, runEntry, agentosUrl, resolvedFactoryUser,
 *                    jiraBaseUrl, jiraEmail, jiraApiToken,
 *                    registerGate, unregisterGate, getGate, writeGateReply, validateGateSignal,
 *                    log })
 *   Returns: { handleRequest, parseJsonl, reconstructPhases }
 */

import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { spawn } from 'node:child_process'
import { randomBytes } from 'node:crypto'

// ---------------------------------------------------------------------------
// Pure JSONL helpers (exported for backward compatibility)
// ---------------------------------------------------------------------------

/**
 * Read and parse a JSONL file. Returns [] on any error.
 * @param {string} filePath
 * @returns {object[]}
 */
export function parseJsonl(filePath) {
  try {
    return readFileSync(filePath, 'utf8')
      .split('\n')
      .filter(Boolean)
      .map((line) => { try { return JSON.parse(line) } catch { return null } })
      .filter(Boolean)
  } catch {
    return []
  }
}

/**
 * Rebuild phases exclusively from registry records.
 * A phase start timestamp is written on the `phase` record;
 * `phase_end` contributes only the outcome and duration.
 *
 * @param {object[]} lines
 * @returns {object[]}
 */
export function reconstructPhases(lines) {
  const phaseOrder = []
  const phaseStarts = new Map()
  const phaseEnds = new Map()
  for (const line of lines) {
    if (line.kind === 'phase') {
      if (!phaseStarts.has(line.name)) phaseOrder.push(line.name)
      phaseStarts.set(line.name, line)
    } else if (line.kind === 'phase_end') {
      phaseEnds.set(line.name, line)
    }
  }
  return phaseOrder.map((name) => {
    const start = phaseStarts.get(name)
    const end = phaseEnds.get(name)
    return {
      name,
      phaseKind: start?.phaseKind ?? '?',
      status: end?.status ?? 'running',
      startedAt: start?.startedAt ?? null,
      durationMs: end?.durationMs ?? null,
      facts: end?.facts ?? {},
    }
  })
}

// ---------------------------------------------------------------------------
// Factory
// ---------------------------------------------------------------------------

/**
 * @param {{
 *   runsDir: string,
 *   runEntry: string,
 *   agentosUrl: string,
 *   resolvedFactoryUser: string | undefined,
 *   jiraBaseUrl: string | null,
 *   jiraEmail: string | null,
 *   jiraApiToken: string | null,
 *   registerGate: Function,
 *   unregisterGate: Function,
 *   getGate: Function,
 *   writeGateReply: Function,
 *   validateGateSignal: Function,
 *   log?: Console,
 * }} config
 * @returns {{ handleRequest: Function, parseJsonl: Function, reconstructPhases: Function }}
 */
export function createRunRouter({
  runsDir,
  runEntry,
  agentosUrl,
  resolvedFactoryUser,
  jiraBaseUrl,
  jiraEmail,
  jiraApiToken,
  registerGate,
  unregisterGate,
  getGate,
  writeGateReply,
  validateGateSignal,
  log = console,
}) {
  // ---------------------------------------------------------------------------
  // In-memory registry of active run processes
  // { runId | 'pid:<pid>' → { child, listeners, lines, stopping, gateIpcSecret, trackedRunId } }
  // ---------------------------------------------------------------------------
  const activeRuns = new Map()

  // ---------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------

  function extractContext(lines) {
    const ctx = {}
    for (const l of lines) {
      if (l.kind !== 'phase_end') continue
      const f = l.facts ?? {}
      if (f.domain && !ctx.domain) ctx.domain = f.domain
      if (f.rootPath && !ctx.rootPath) ctx.rootPath = f.rootPath
      if (f.command && !ctx.command) ctx.command = f.command
      if (f.ticketId && !ctx.ticketId) ctx.ticketId = f.ticketId
      if (f.summary && !ctx.ticketSummary) ctx.ticketSummary = f.summary
      if (f.agentName && !ctx.roles) ctx.roles = [f.agentName]
      if (f.analystName || f.editorName) {
        ctx.roles = [f.analystName, f.editorName].filter(Boolean)
      }
    }
    return ctx
  }

  function summarizeRun(runId) {
    const filePath = join(runsDir, `${runId}.jsonl`)
    const lines = parseJsonl(filePath)

    const start = lines.find((l) => l.kind === 'run_start')
    const end = lines.find((l) => l.kind === 'run_end')
    const phaseEnds = lines.filter((l) => l.kind === 'phase_end')

    let status = 'running'
    if (end) status = end.status
    else if (!activeRuns.has(runId)) status = 'crashed'

    const namespaceId = start?.namespaceId ?? undefined

    const summary = {
      runId,
      workflow: start?.workflow ?? '?',
      startedAt: start?.startedAt ?? null,
      endedAt: end?.endedAt ?? null,
      durationMs: end?.durationMs ?? null,
      status,
      phaseCount: phaseEnds.length,
      context: extractContext(lines),
    }
    if (namespaceId !== undefined) summary.namespaceId = namespaceId
    return summary
  }

  function detailRun(runId) {
    const filePath = join(runsDir, `${runId}.jsonl`)
    if (!existsSync(filePath)) return null
    const lines = parseJsonl(filePath)
    const summary = summarizeRun(runId)
    const phases = reconstructPhases(lines)
    const logLines = activeRuns.get(runId)?.lines ?? []
    return { ...summary, phases, logLines }
  }

  function listRuns() {
    let files
    try { files = readdirSync(runsDir).filter((f) => f.endsWith('.jsonl')) }
    catch { return [] }
    return files
      .sort((a, b) => b.localeCompare(a))
      .map((f) => summarizeRun(f.replace('.jsonl', '')))
  }

  // ---------------------------------------------------------------------------
  // Launch a run
  // ---------------------------------------------------------------------------

  /**
   * Spawn node factory/run.mjs <workflow>.
   *
   * @param {object} params
   * @returns {{ pid: number, error?: string }}
   */
  function launchRun(params) {
    const {
      workflow = 'fix-loop',
      FACTORY_NAMESPACE_ID,
      FACTORY_AGENT,
      FACTORY_AGENT_ANALYST,
      FACTORY_AGENT_EDITOR,
      FACTORY_TASK,
      FACTORY_SCOPE,
      FACTORY_DOMAIN,
      AGENTOS_URL: paramsAgentosUrl,
      FACTORY_USER: paramsFactoryUser,
    } = params

    const ITEMS_WITH_SINGLE_ROLE = new Set(['fix-loop', 'smoke', 'agentos-smoke'])
    const ITEMS_WITHOUT_AGENT = new Set(['backend-oracle-check', 'verify-back'])

    if (!FACTORY_NAMESPACE_ID && !ITEMS_WITHOUT_AGENT.has(workflow)) return { error: 'FACTORY_NAMESPACE_ID manquant' }
    if (!FACTORY_TASK && !ITEMS_WITHOUT_AGENT.has(workflow)) return { error: 'FACTORY_TASK manquant' }
    if (ITEMS_WITH_SINGLE_ROLE.has(workflow) && !FACTORY_AGENT) {
      return { error: `FACTORY_AGENT manquant (requis par "${workflow}")` }
    }

    if (params.FACTORY_TICKET) {
      const missingJira = [
        !jiraBaseUrl ? 'JIRA_BASE_URL' : null,
        !jiraEmail ? 'JIRA_EMAIL' : null,
        !jiraApiToken ? 'JIRA_API_TOKEN' : null,
      ].filter(Boolean)
      if (missingJira.length > 0) {
        return {
          error:
            `Le serveur du dashboard n'a pas de credentials Jira configurés ` +
            `(manquant : ${missingJira.join(', ')}). ` +
            `Relancez-le avec ces variables dans son environnement : ` +
            `JIRA_BASE_URL=https://votre-instance.atlassian.net ` +
            `JIRA_EMAIL=votre@email.com ` +
            `JIRA_API_TOKEN=votre-token ` +
            `node factory/dashboard/server.mjs`,
        }
      }
    }

    let existingFiles
    try { existingFiles = new Set(readdirSync(runsDir)) }
    catch { existingFiles = new Set() }

    const gateIpcSecret = randomBytes(32).toString('hex')
    const env = {
      ...process.env,
      FACTORY_GATE_IPC_SECRET: gateIpcSecret,
      FACTORY_NAMESPACE_ID,
      FACTORY_TASK,
      AGENTOS_URL: paramsAgentosUrl ?? agentosUrl,
      FACTORY_USER: paramsFactoryUser ?? resolvedFactoryUser,
    }
    if (FACTORY_AGENT) env.FACTORY_AGENT = FACTORY_AGENT
    if (FACTORY_AGENT_ANALYST) env.FACTORY_AGENT_ANALYST = FACTORY_AGENT_ANALYST
    if (FACTORY_AGENT_EDITOR) env.FACTORY_AGENT_EDITOR = FACTORY_AGENT_EDITOR
    if (FACTORY_SCOPE) env.FACTORY_SCOPE = FACTORY_SCOPE
    if (FACTORY_DOMAIN) env.FACTORY_DOMAIN = FACTORY_DOMAIN
    if (params.FACTORY_ROOT) env.FACTORY_ROOT = params.FACTORY_ROOT
    if (params.FACTORY_COMMAND_FRONT) env.FACTORY_COMMAND_FRONT = params.FACTORY_COMMAND_FRONT
    if (params.FACTORY_COMMAND_BACK) env.FACTORY_COMMAND_BACK = params.FACTORY_COMMAND_BACK
    if (params.FACTORY_CWD_FRONT) env.FACTORY_CWD_FRONT = params.FACTORY_CWD_FRONT
    if (params.FACTORY_CWD_BACK) env.FACTORY_CWD_BACK = params.FACTORY_CWD_BACK
    if (params.FACTORY_TICKET) env.FACTORY_TICKET = params.FACTORY_TICKET
    // Propagation explicite des credentials Jira depuis les constantes du module.
    // Les valeurs éventuellement envoyées par le client dans `params` sont ignorées.
    if (jiraBaseUrl) env.JIRA_BASE_URL = jiraBaseUrl
    if (jiraEmail) env.JIRA_EMAIL = jiraEmail
    if (jiraApiToken) env.JIRA_API_TOKEN = jiraApiToken

    const WORKFLOWS = new Set(['fix-loop', 'us-loop'])
    const DIAGNOSTICS = new Set(['agentos-smoke', 'backend-oracle-check'])
    let runArgs
    if (WORKFLOWS.has(workflow)) runArgs = ['workflow', workflow]
    else if (DIAGNOSTICS.has(workflow)) runArgs = ['diagnostic', workflow]
    else runArgs = [workflow]

    const child = spawn(process.execPath, [runEntry, ...runArgs], {
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
    })

    const pidKey = `pid:${child.pid}`
    activeRuns.set(pidKey, {
      child,
      listeners: new Set(),
      lines: [],
      stopping: false,
      gateIpcSecret,
      trackedRunId: null,
    })

    let runId = null
    const entry = () => activeRuns.get(runId ?? pidKey)

    function broadcast(line) {
      const e = entry()
      if (!e) return
      e.lines.push(line)
      for (const res of e.listeners) {
        try { res.write(`data: ${JSON.stringify({ line })}\n\n`) }
        catch { e.listeners.delete(res) }
      }
    }

    // Poll to discover the JSONL file created by the child in the first few ms
    const pollInterval = setInterval(() => {
      let files
      try { files = readdirSync(runsDir).filter((f) => f.endsWith('.jsonl')) }
      catch { return }
      const newFile = files.find((f) => !existingFiles.has(f))
      if (newFile && !runId) {
        runId = newFile.replace('.jsonl', '')
        const old = activeRuns.get(pidKey)
        if (old) {
          old.trackedRunId = runId
          activeRuns.delete(pidKey)
          activeRuns.set(runId, old)
        }
      }
    }, 200)

    let stdoutBuf = ''
    child.stdout.on('data', (chunk) => {
      stdoutBuf += chunk.toString()
      const parts = stdoutBuf.split('\n')
      stdoutBuf = parts.pop()
      for (const line of parts) {
        let signal
        try { signal = JSON.parse(line) } catch { /* ordinary stdout */ }
        if (signal?.__factory_gate === 'open') {
          const childState = entry()
          const trackedRunId = childState?.trackedRunId
          const validation = trackedRunId
            ? validateGateSignal(signal, trackedRunId, childState.gateIpcSecret)
            : { ok: false }
          if (validation.ok) {
            const registration = registerGate({
              runId: trackedRunId,
              gateInstanceId: signal.gateInstanceId,
              gateType: signal.gateType,
              findings: signal.findings,
              outcomes: signal.outcomes,
              oracleGate: signal.oracleGate,
            })
            if (registration.ok) broadcast(`[gate:open] runId=${trackedRunId}`)
            else broadcast(`[gate:conflict] runId=${trackedRunId}`)
          }
          continue
        }
        broadcast(line)
      }
    })

    let stderrBuf = ''
    child.stderr.on('data', (chunk) => {
      stderrBuf += chunk.toString()
      const parts = stderrBuf.split('\n')
      stderrBuf = parts.pop()
      for (const line of parts) broadcast(`[stderr] ${line}`)
    })

    child.on('close', () => {
      clearInterval(pollInterval)
      if (stdoutBuf) broadcast(stdoutBuf)
      if (stderrBuf) broadcast(`[stderr] ${stderrBuf}`)
      if (runId) unregisterGate(runId)
      const e = entry()
      if (e) {
        for (const res of e.listeners) {
          try { res.write(`data: ${JSON.stringify({ done: true })}\n\n`) } catch {}
          try { res.end() } catch {}
        }
        e.listeners.clear()
        e.child = null
      }
    })

    return { pid: child.pid }
  }

  // ---------------------------------------------------------------------------
  // SSE helper — write headers + replay buffered lines + register listener
  // ---------------------------------------------------------------------------

  function attachSseStream(runId, res, req) {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'Access-Control-Allow-Origin': '*',
    })
    res.write(': connected\n\n')

    const e = activeRuns.get(runId)
    if (!e || !e.child) {
      res.write(`data: ${JSON.stringify({ done: true })}\n\n`)
      res.end()
      return
    }
    for (const line of e.lines) {
      res.write(`data: ${JSON.stringify({ line })}\n\n`)
    }
    e.listeners.add(res)
    req.on('close', () => e.listeners.delete(res))
  }

  // ---------------------------------------------------------------------------
  // Request handler
  // ---------------------------------------------------------------------------

  /**
   * @param {{ method: string, path: string, url: URL, readBody: () => Promise<object>, send: (status: number, body: unknown) => void, req: import('node:http').IncomingMessage, res: import('node:http').ServerResponse, log?: Console }} ctx
   * @returns {Promise<boolean>}
   */
  async function handleRequest({ method, path, url, readBody, send: sendFn, req, res }) {
    // GET /api/runs
    if (method === 'GET' && path === '/api/runs') {
      return sendFn(200, listRuns()), true
    }

    // POST /api/runs
    if (method === 'POST' && path === '/api/runs') {
      const body = await readBody()
      const result = launchRun(body)
      if (result.error) return sendFn(400, { error: result.error }), true
      return sendFn(202, result), true
    }

    // GET /api/runs/:id
    const detailMatch = path.match(/^\/api\/runs\/([^/]+)$/)
    if (method === 'GET' && detailMatch) {
      const detail = detailRun(detailMatch[1])
      if (!detail) return sendFn(404, { error: 'Run introuvable' }), true
      return sendFn(200, detail), true
    }

    // GET /api/runs/:id/stream (SSE)
    const streamMatch = path.match(/^\/api\/runs\/([^/]+)\/stream$/)
    if (method === 'GET' && streamMatch) {
      attachSseStream(streamMatch[1], res, req)
      return true
    }

    // GET /api/factory/runs[?namespaceId=<uuid>]
    if (method === 'GET' && path === '/api/factory/runs') {
      const nsFilter = url.searchParams.get('namespaceId')
      const all = listRuns()
      if (!nsFilter) return sendFn(200, all), true
      const filtered = all.filter((r) => r.namespaceId === nsFilter)
      return sendFn(200, filtered), true
    }

    // POST /api/factory/runs — launch and wait for runId
    if (method === 'POST' && path === '/api/factory/runs') {
      const body = await readBody()
      const result = launchRun(body)
      if (result.error) return sendFn(400, { error: result.error }), true
      const pid = result.pid
      const deadline = Date.now() + 3000
      const runId = await new Promise((resolve) => {
        const check = () => {
          for (const [key, entry] of activeRuns) {
            if (!key.startsWith('pid:') && entry.child?.pid === pid) return resolve(key)
          }
          if (Date.now() >= deadline) return resolve(null)
          setTimeout(check, 200)
        }
        check()
      })
      return sendFn(202, { pid, runId }), true
    }

    // GET /api/factory/runs/:id
    const factoryDetailMatch = path.match(/^\/api\/factory\/runs\/([^/]+)$/)
    if (method === 'GET' && factoryDetailMatch) {
      const detail = detailRun(factoryDetailMatch[1])
      if (!detail) return sendFn(404, { error: 'Run introuvable' }), true
      return sendFn(200, detail), true
    }

    // POST /api/factory/runs/:id/stop
    const factoryStopMatch = path.match(/^\/api\/factory\/runs\/([^/]+)\/stop$/)
    if (method === 'POST' && factoryStopMatch) {
      const runId = factoryStopMatch[1]
      const entry = activeRuns.get(runId)
      if (!entry) {
        const jsonlPath = join(runsDir, `${runId}.jsonl`)
        if (existsSync(jsonlPath)) {
          const lines = parseJsonl(jsonlPath)
          const hasEnd = lines.some((l) => l.kind === 'run_end')
          if (hasEnd) return sendFn(410, { error: 'Run already finished.' }), true
        }
        return sendFn(404, { error: 'Run not found.' }), true
      }
      if (!entry.child) return sendFn(410, { error: 'Run already finished.' }), true
      if (entry.stopping) return sendFn(409, { error: 'Stop already requested.' }), true
      entry.stopping = true
      try { entry.child.kill('SIGTERM') } catch { /* child may have already exited */ }
      return sendFn(202, { runId, stopping: true }), true
    }

    // GET /api/factory/runs/:id/stream (SSE alias)
    const factoryStreamMatch = path.match(/^\/api\/factory\/runs\/([^/]+)\/stream$/)
    if (method === 'GET' && factoryStreamMatch) {
      attachSseStream(factoryStreamMatch[1], res, req)
      return true
    }

    // GET /api/factory/runs/:id/review-gate
    const factoryGateMatch = path.match(/^\/api\/factory\/runs\/([^/]+)\/review-gate$/)
    if (method === 'GET' && factoryGateMatch) {
      const runId = factoryGateMatch[1]
      const pendingGate = getGate(runId)
      if (pendingGate) {
        const pendingResp = {
          status: 'pending',
          gateType: pendingGate.gateType ?? 'adversarial-review',
          gateInstanceId: pendingGate.gateInstanceId,
          findings: pendingGate.findings,
          outcomes: pendingGate.outcomes,
          allowedDecisions: pendingGate.allowedDecisions,
          openedAt: pendingGate.openedAt,
        }
        if (pendingGate.oracleGate) pendingResp.oracleGate = pendingGate.oracleGate
        return sendFn(200, pendingResp), true
      }
      const jsonlPath = join(runsDir, `${runId}.jsonl`)
      if (existsSync(jsonlPath)) {
        const lines = parseJsonl(jsonlPath)
        const runEnd = lines.find((l) => l.kind === 'run_end')
        let humanDecision = null
        for (const l of lines) {
          if (l.kind === 'phase_end' && l.facts?.humanDecision) {
            humanDecision = l.facts.humanDecision
            break
          }
        }
        if (runEnd) {
          return sendFn(200, {
            status: 'terminal',
            humanDecision,
            reason: 'Run completed. A terminated process cannot resume. Only future pending gates can receive decisions.',
          }), true
        }
        return sendFn(200, { status: 'terminal', humanDecision: null, reason: 'No active review gate for this run.' }), true
      }
      return sendFn(404, { error: 'Run not found.' }), true
    }

    // POST /api/factory/runs/:id/review-gate/reply
    const factoryGateReplyMatch = path.match(/^\/api\/factory\/runs\/([^/]+)\/review-gate\/reply$/)
    if (method === 'POST' && factoryGateReplyMatch) {
      const runId = factoryGateReplyMatch[1]
      const body = await readBody()
      if (typeof body.gateInstanceId !== 'string' || !body.gateInstanceId) {
        return sendFn(400, { error: 'gateInstanceId is required.' }), true
      }
      if (typeof body.decision !== 'string' || !body.decision) {
        return sendFn(400, { error: 'decision is required.' }), true
      }
      if (body.message !== undefined && typeof body.message !== 'string') {
        return sendFn(400, { error: 'message must be a string.' }), true
      }
      const result = writeGateReply(runId, body.gateInstanceId, body.decision, body.message ?? '')
      if (!result.ok) return sendFn(result.status ?? 400, { error: result.error }), true
      return sendFn(200, { ok: true, decision: body.decision }), true
    }

    // GET /api/agents?namespaceId=
    if (method === 'GET' && path === '/api/agents') {
      return false // handled in server.mjs (needs proxy)
    }

    // GET /api/cases/:caseId/events — handled in server.mjs (needs proxy)
    // GET /api/jira/:ticketId — handled in server.mjs (needs Jira credentials)

    // GET /api/review-gate — DEPRECATED: 410 Gone
    if (method === 'GET' && path === '/api/review-gate') {
      return sendFn(410, {
        error: 'DEPRECATED: global /api/review-gate removed. Use GET /api/factory/runs/:runId/review-gate instead.',
      }), true
    }

    // POST /api/review-gate/reply — DEPRECATED: 410 Gone
    if (method === 'POST' && path === '/api/review-gate/reply') {
      return sendFn(410, {
        error: 'DEPRECATED: global /api/review-gate/reply removed. Use POST /api/factory/runs/:runId/review-gate/reply instead.',
      }), true
    }

    return false
  }

  return { handleRequest, parseJsonl, reconstructPhases }
}
