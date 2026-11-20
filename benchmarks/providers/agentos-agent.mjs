import { AgentosClient, estimateCost, readSse } from './agentos-client.mjs'

/**
 * promptfoo provider running one AgentOS agent turn per test.
 *
 * Flow: create a case (with a title, which skips the automatic naming LLM call) → open the
 * case event stream → post `@agent <prompt>` → collect events until the case leaves RUNNING
 * → read the recorded usage of the case tree (sub-agents included).
 *
 * Cost (config `costSource`):
 * - `recorded` (default): what AgentOS stored — the provider-reported cost (e.g. Requesty
 *   `usage.cost`) when the gateway returns one, otherwise the AiModel.pricing estimate.
 * - `pricing`: recomputed here from tokens × AiModel.pricing (scenario A only).
 * Both figures are always available in the result metadata.
 *
 * Config: agent, namespace, url, headers, timeoutMs, deleteCase, titlePrefix, costSource, reasoningEffort
 * (case-level override of the models' effort: NONE, LOW, MEDIUM, HIGH, XHIGH, MAX), subscribeGraceMs.
 */
export default class AgentosAgentProvider {
  constructor(options = {}) {
    this.config = options.config ?? {}
    this.client = new AgentosClient({ url: this.config.url, headers: this.config.headers })
  }

  id() {
    return `agentos-agent:${this.config.agent ?? 'default'}`
  }

  async callApi(prompt, context) {
    const config = this.config
    const timeoutMs = config.timeoutMs ?? 600_000
    if (config.agent && !MENTIONABLE.test(config.agent)) {
      return {
        error: `Agent '${config.agent}' cannot be mentioned: AgentOS only routes @names made of letters, digits, _ and -`,
      }
    }
    const namespaceId = await this.client.resolveNamespaceId(config.namespace)
    const title = `${config.titlePrefix ?? '[bench]'} ${context?.test?.description ?? prompt}`.slice(0, 120)
    const created = await this.client.post('/api/cases', {
      namespaceId,
      title,
      ...(config.reasoningEffort ? { reasoningEffort: config.reasoningEffort.toUpperCase() } : {}),
    })
    const caseId = created.id
    const caseUrl = `${process.env.AGENTOS_UI_URL ?? 'http://localhost:4200'}/agentos/home?ns=${namespaceId}&case=${caseId}`

    const abort = new AbortController()
    const timer = setTimeout(() => abort.abort(new Error('timeout')), timeoutMs)
    try {
      // AgentOS sends no bytes (not even headers) on a new case's stream until the first event or
      // the 30 s keep-alive, so the stream is not awaited before posting. Persisted events are
      // replayed whatever the subscription timing; only live text chunks (TTFT) need it early.
      const streaming = fetch(`${this.client.url}/api/cases/${caseId}/events?includePreviousEvents=true`, {
        headers: { ...this.client.headers, Accept: 'text/event-stream' },
        signal: abort.signal,
      })
      streaming.catch(() => {})
      await new Promise((resolve) => setTimeout(resolve, config.subscribeGraceMs ?? 300))

      const content = config.agent ? `@${config.agent} ${prompt}` : prompt
      const startedAt = performance.now()
      await this.client.post(`/api/cases/${caseId}/messages`, { content })

      const stream = await streaming
      if (!stream.ok) throw new Error(`AgentOS event stream failed: ${stream.status}`)
      const run = await collectTurn(stream, startedAt)
      clearTimeout(timer)
      abort.abort()
      return await this.buildResult({ run, caseId, caseUrl, namespaceId, latencyMs: performance.now() - startedAt })
    } catch (error) {
      clearTimeout(timer)
      abort.abort()
      if (error?.name === 'AbortError' || abort.signal.reason?.message === 'timeout') {
        const pause = await this.client.get(`/api/cases/${caseId}/run-cost`).catch(() => null)
        await this.client.post(`/api/cases/${caseId}/interrupt`).catch(() => {})
        const reason = pause?.paused ? 'paused on its run cost threshold' : `no end of turn after ${timeoutMs} ms`
        return { error: `AgentOS case ${caseId} ${reason}`, metadata: { caseId, caseUrl } }
      }
      return { error: String(error?.message ?? error), metadata: { caseId, caseUrl } }
    } finally {
      if (config.deleteCase) await this.client.request('DELETE', `/api/cases/${caseId}`).catch(() => {})
    }
  }

  async buildResult({ run, caseId, caseUrl, namespaceId, latencyMs }) {
    const usage = await this.readUsage(caseId, run, namespaceId)
    const cost = this.config.costSource === 'pricing' ? usage.pricingCost : usage.recordedCost
    const metadata = {
      caseId,
      caseUrl,
      selectedAgent: run.selectedAgent,
      agents: run.agents,
      ttftMs: run.firstChunkAt ? Math.round(run.firstChunkAt - run.startedAt) : null,
      toolCalls: run.toolCalls,
      toolNames: run.toolCalls.map((call) => call.toolName),
      steps: run.intentions,
      subCases: run.subCaseIds.length,
      maxIterationsReached: run.maxIterationsReached,
      warnings: run.warnings,
      usage,
    }
    const tokenUsage =
      usage.source === 'unavailable'
        ? undefined
        : {
            prompt: usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens,
            completion: usage.outputTokens,
            total: usage.totalTokens,
            cached: usage.cacheReadTokens,
          }
    const failure =
      wrongAgentFailure(this.config.agent, run) ?? run.errors[0] ?? terminalFailure(run) ?? blockedFailure(run)
    return {
      output: run.answers.join('\n\n'),
      ...(failure ? { error: failure } : {}),
      cost: cost ?? undefined,
      tokenUsage,
      latencyMs: Math.round(latencyMs),
      cached: false,
      metadata,
    }
  }

  /** Recorded totals come from AgentOS; the pricing estimate is recomputed per usage record. */
  async readUsage(caseId, run, namespaceId) {
    try {
      const tree = await this.client.get(`/api/usage-records/aggregate/by-case-tree/${caseId}`)
      const caseIds = [caseId, ...run.subCaseIds]
      const [records, pricing] = await Promise.all([
        Promise.all(caseIds.map((id) => this.client.get(`/api/usage-records/by-case/${id}`))).then((r) => r.flat()),
        this.client.pricingIndex(namespaceId),
      ])
      const estimates = records.map((r) => estimateCost(pricing.get(`${r.providerName}|${r.apiModelName}`), r))
      return {
        source: 'agentos-usage-records',
        inputTokens: tree.inputTokens,
        outputTokens: tree.outputTokens,
        cacheReadTokens: tree.cacheReadTokens,
        cacheWriteTokens: tree.cacheWriteTokens,
        totalTokens: tree.totalTokens,
        // Requesty-reported cost when the gateway returned one, else the pricing estimate.
        // Unknown when any record has no cost; the known part stays available as a lower bound.
        recordedCost: tree.unknownCostCount > 0 ? null : tree.cost,
        recordedCostLowerBound: tree.cost,
        recordedUnknownCostCount: tree.unknownCostCount,
        pricingCost: estimates.some((e) => e === null) ? null : estimates.reduce((a, b) => a + b, 0),
        // Delegations nested deeper than one level are in the recorded total only.
        pricingCoveredRecords: records.length,
        recordCount: tree.recordCount,
      }
    } catch (error) {
      if (error.status !== 503) throw error
      // Usage tracking disabled in AgentOS: nothing is measured (AgentFinishedEvent carries no
      // llmUsage either), so tokens and cost are unknown rather than zero.
      return {
        source: 'unavailable',
        reason: 'AgentOS usage tracking is disabled (AGENTOS_USAGE_ENABLED)',
        recordedCost: null,
        pricingCost: null,
      }
    }
  }
}

/** Reads case events until the case leaves RUNNING (IDLE) or reaches a terminal status. */
async function collectTurn(stream, startedAt) {
  const run = {
    startedAt,
    firstChunkAt: null,
    status: null,
    agents: [],
    answers: [],
    toolCalls: [],
    intentions: 0,
    errors: [],
    warnings: [],
    question: null,
    pendingConfirmation: null,
    subCaseIds: [],
    selectedAgent: null,
    maxIterationsReached: null,
  }
  const requests = new Map()
  let sawRunning = false
  for await (const data of readSse(stream)) {
    const event = JSON.parse(data)
    switch (event.type) {
      case 'AgentSelectedEvent':
        // The first selection answers the mention; later ones are redirects within the turn.
        run.selectedAgent ??= event.agentName
        break
      case 'TextChunkEvent':
        run.firstChunkAt ??= performance.now()
        break
      case 'AgentRunningEvent':
        run.agents.push({ name: event.agentName, provider: event.llmProvider, model: event.llmModel })
        break
      case 'IntentionGeneratedEvent':
        run.intentions++
        break
      case 'MessageEvent':
        if (event.actor?.role === 'AGENT') {
          run.answers.push(
            event.content
              .filter((c) => c.type === 'Text')
              .map((c) => c.content)
              .join('')
          )
        }
        break
      case 'ToolRequestEvent':
        requests.set(event.toolRequestId, { toolName: event.toolName, args: event.args })
        break
      case 'ToolResponseEvent':
        run.toolCalls.push({
          ...(requests.get(event.toolRequestId) ?? { toolName: event.toolName }),
          success: event.success,
          durationMs: event.durationMs ?? null,
        })
        break
      case 'SubCaseStartedEvent':
        run.subCaseIds.push(event.subCaseId)
        break
      case 'AgentFinishedEvent':
        // The agent stopped at its tool-round limit and answered with a summary of its progress.
        if (event.maxIterationsReached) run.maxIterationsReached = event.maxIterationsReached
        break
      case 'ErrorEvent':
        run.errors.push(event.message)
        break
      case 'WarnEvent':
        run.warnings.push(event.message)
        break
      case 'QuestionEvent':
        run.question = event.question
        break
      case 'PendingConfirmationEvent':
        run.pendingConfirmation = event.toolName
        break
      case 'CaseStatusEvent':
        if (event.status === 'RUNNING') sawRunning = true
        if (event.status === 'KILLED' || event.status === 'ERROR' || (event.status === 'IDLE' && sawRunning)) {
          run.status = event.status
          return run
        }
        break
    }
  }
  throw new Error('AgentOS event stream ended before the end of the turn')
}

/**
 * AgentOS's mention grammar (AgentMention.kt `^@([\w-]+)`, ASCII): a name outside it is cut short or
 * ignored, and the default agent runs.
 */
const MENTIONABLE = /^[\w-]+$/

/**
 * AgentOS answers an unknown or inaccessible mention with a warning and runs the default agent, and
 * resolves a mention by name prefix (`@Ali` may select `Alice`); grading that answer as the requested
 * agent's would be wrong.
 */
function wrongAgentFailure(agent, run) {
  if (!agent || !run.selectedAgent || run.selectedAgent.toLowerCase() === agent.toLowerCase()) return null
  return `Agent '${agent}' not found or not accessible in the namespace; AgentOS ran '${run.selectedAgent}' instead`
}

function terminalFailure(run) {
  return run.status === 'ERROR' || run.status === 'KILLED' ? `AgentOS case ended in ${run.status}` : null
}

/** A turn that waits for a human cannot be graded as an answer. */
function blockedFailure(run) {
  if (run.pendingConfirmation) return `Agent is waiting for confirmation of tool ${run.pendingConfirmation}`
  if (run.question && run.answers.length === 0) return `Agent asked a question: ${run.question}`
  if (run.answers.length === 0) return run.warnings[0] ?? 'Agent produced no answer'
  return null
}
