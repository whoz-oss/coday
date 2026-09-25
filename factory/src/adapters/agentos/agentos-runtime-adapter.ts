/**
 * AgentOS implementation of the `AgentRuntimeGateway` port.
 *
 * This is the only module that composes the HTTP client, the event translator,
 * the observer and the capability inspector. It also exposes the legacy surface
 * the Factory has always consumed (`createCase`, `runAgentTurn`, preflights…)
 * so that `factory/lib/agentos.mjs` can become a pure delegation facade.
 */

import { clearActiveCaseId, setActiveCaseId } from '../../lib/active-case.js'
import {
  asRuntimeExecutionId,
  type AgentRuntimeGateway,
  type ExecutionObservation,
  type ExecutionOptions,
  type ExecutionSummary,
  type WorkspaceIntegration,
  type ObserveOptions,
  type ResultBinding,
  type RuntimeExecutionId,
  type WorkerConfig,
  type WorkerInspectionResult,
} from '../../ports/agent-runtime-gateway.js'
import { createAgentOsCapabilityInspector } from './agentos-capability-inspector.js'
import { createAgentOsHttpClient, type AgentOsHttpClient, type AgentOsHttpClientConfig } from './agentos-http-client.js'
import { CASE_STATUS_EVENT, QUIESCENT_STATUSES, type RawCaseEvent } from './agentos-event-translator.js'
import { createAgentOsRuntimeObserver, executionFailure } from './agentos-runtime-observer.js'

/** Configuration of the composite AgentOS runtime adapter. */
export interface AgentOsRuntimeAdapterConfig extends AgentOsHttpClientConfig {
  /** Polling interval for observation, in milliseconds. */
  pollIntervalMs?: number
  /** Injected sleep, for deterministic tests. */
  sleep?: (ms: number) => Promise<void>
  /** Injected clock, for deterministic tests. */
  now?: () => number
  /** Injected realpath resolver, for deterministic tests. */
  realpath?: (path: string) => string
  /** Pre-built HTTP client, for tests. */
  client?: AgentOsHttpClient
}

/** Legacy worker preflight result. */
export interface WorkerPreflightResult {
  ok: boolean
  reason: string | null
  agent: WorkerConfig | null
}

/** Legacy workspace preflight result. */
export interface WorkspacePreflightResult {
  ok: boolean
  reason: string | null
  rootPath: string | null
}

/** Legacy strict workspace preflight result, including the matched integration. */
export interface WorkspacePreflightIntegrationResult extends WorkspacePreflightResult {
  integration: WorkspaceIntegration | null
}

/** Options of a legacy agent turn. */
export interface RunAgentTurnOptions {
  startTimeoutMs?: number
  workTimeoutMs?: number
}

/**
 * Adapter surface: the port plus the legacy Factory-facing operations.
 */
export interface AgentOsRuntimeAdapter extends AgentRuntimeGateway {
  readonly client: AgentOsHttpClient
  createCase(namespaceId: string, title: string): Promise<ExecutionSummary>
  postMessage(caseId: string, content: string): Promise<void>
  bindFactoryStepResult(caseId: string, binding: unknown): Promise<void>
  getCase(caseId: string): Promise<Record<string, unknown>>
  listEvents(caseId: string): Promise<RawCaseEvent[]>
  killCase(caseId: string): Promise<void>
  listAgents(namespaceId: string): Promise<WorkerConfig[]>
  preflightAgent(namespaceId: string, agentName: string): Promise<WorkerPreflightResult>
  listIntegrations(namespaceId: string): Promise<WorkspaceIntegration[]>
  preflightWorkspace(namespaceId: string, agent: WorkerConfig, repoRoot: string): Promise<WorkspacePreflightResult>
  preflightWritableWorkspace(
    namespaceId: string,
    agent: WorkerConfig,
    repoRoot: string
  ): Promise<WorkspacePreflightIntegrationResult>
  preflightReadOnlyWorkspace(
    namespaceId: string,
    agent: WorkerConfig,
    repoRoot: string
  ): Promise<WorkspacePreflightIntegrationResult>
  runAgentTurn(
    caseId: string,
    agentName: string,
    brief: string,
    options?: RunAgentTurnOptions
  ): Promise<ExecutionObservation>
}

/** Builds the AgentOS runtime adapter. */
export function createAgentOsRuntimeAdapter(config: AgentOsRuntimeAdapterConfig = {}): AgentOsRuntimeAdapter {
  const client = config.client ?? createAgentOsHttpClient(config)

  const inspector = createAgentOsCapabilityInspector({
    listAgentConfigs: (namespaceId) => client.listAgentConfigs(namespaceId),
    listIntegrationConfigs: (namespaceId) => client.listIntegrationConfigs(namespaceId),
    ...(config.realpath ? { realpath: config.realpath } : {}),
  })

  const observer = createAgentOsRuntimeObserver({
    listEvents: (executionId) => client.listEvents(executionId),
    killCase: (executionId) => client.killCase(executionId),
    ...(config.pollIntervalMs !== undefined ? { pollIntervalMs: config.pollIntervalMs } : {}),
    ...(config.sleep ? { sleep: config.sleep } : {}),
    ...(config.now ? { now: config.now } : {}),
  })

  async function killQuietly(caseId: string): Promise<void> {
    try {
      await client.killCase(caseId)
    } catch {
      // ignored: the failure verdict takes precedence over the kill succeeding
    }
  }

  return {
    client,

    // ----- Port: capability inspection -------------------------------------
    inspectWorker(namespaceId: string, workerName: string): Promise<WorkerInspectionResult> {
      return inspector.inspectWorker(namespaceId, workerName)
    },

    // ----- Port: execution lifecycle ---------------------------------------
    async startExecution(options: ExecutionOptions): Promise<RuntimeExecutionId> {
      const caseId =
        options.caseId ??
        (await client.createCase(options.namespaceId, options.title ?? `factory ${options.workerName}`)).id
      setActiveCaseId(caseId)
      try {
        await client.postMessage(caseId, `@${options.workerName} ${options.brief}`)
      } catch (err) {
        await killQuietly(caseId)
        clearActiveCaseId(caseId)
        throw err
      }
      return asRuntimeExecutionId(caseId)
    },

    observeExecution(executionId: RuntimeExecutionId, observerOptions?: ObserveOptions): Promise<ExecutionObservation> {
      return observer.observe(executionId, observerOptions)
    },

    bindResultChannel(executionId: RuntimeExecutionId, binding: ResultBinding): Promise<void> {
      return client.bindFactoryStepResult(executionId, binding)
    },

    answerQuestion(executionId: RuntimeExecutionId, questionId: string, answer: string): Promise<void> {
      return client.postMessage(executionId, answer, { answerToEventId: questionId })
    },

    interruptExecution(executionId: RuntimeExecutionId): Promise<void> {
      return client.interruptCase(executionId)
    },

    async terminateExecution(executionId: RuntimeExecutionId): Promise<void> {
      try {
        await client.killCase(executionId)
      } finally {
        clearActiveCaseId(executionId)
      }
    },

    // ----- Legacy surface ---------------------------------------------------
    createCase(namespaceId: string, title: string): Promise<ExecutionSummary> {
      return client.createCase(namespaceId, title)
    },

    postMessage(caseId: string, content: string): Promise<void> {
      return client.postMessage(caseId, content)
    },

    bindFactoryStepResult(caseId: string, binding: unknown): Promise<void> {
      return client.bindFactoryStepResult(caseId, binding)
    },

    getCase(caseId: string): Promise<Record<string, unknown>> {
      return client.getCase(caseId)
    },

    listEvents(caseId: string): Promise<RawCaseEvent[]> {
      return client.listEvents(caseId)
    },

    killCase(caseId: string): Promise<void> {
      return client.killCase(caseId)
    },

    listAgents(namespaceId: string): Promise<WorkerConfig[]> {
      return client.listAgentConfigs(namespaceId)
    },

    async preflightAgent(namespaceId: string, agentName: string): Promise<WorkerPreflightResult> {
      const result = await inspector.inspectWorker(namespaceId, agentName)
      return { ok: result.ok, reason: result.reason, agent: result.worker }
    },

    listIntegrations(namespaceId: string): Promise<WorkspaceIntegration[]> {
      return client.listIntegrationConfigs(namespaceId)
    },

    async preflightWorkspace(
      namespaceId: string,
      agent: WorkerConfig,
      repoRoot: string
    ): Promise<WorkspacePreflightResult> {
      const result = await inspector.preflightWorkspace(namespaceId, agent, repoRoot)
      return { ok: result.ok, reason: result.reason, rootPath: result.rootPath }
    },

    preflightWritableWorkspace(
      namespaceId: string,
      agent: WorkerConfig,
      repoRoot: string
    ): Promise<WorkspacePreflightIntegrationResult> {
      return inspector.preflightWritableWorkspace(namespaceId, agent, repoRoot).then((result) => ({
        ok: result.ok,
        reason: result.reason,
        rootPath: result.rootPath,
        integration: result.integration ?? null,
      }))
    },

    preflightReadOnlyWorkspace(
      namespaceId: string,
      agent: WorkerConfig,
      repoRoot: string
    ): Promise<WorkspacePreflightIntegrationResult> {
      return inspector.preflightReadOnlyWorkspace(namespaceId, agent, repoRoot).then((result) => ({
        ok: result.ok,
        reason: result.reason,
        rootPath: result.rootPath,
        integration: result.integration ?? null,
      }))
    },

    /**
     * Runs an agent turn and waits for execution quiescence.
     *
     * The execution id is published in the active-case registry as soon as the
     * function is entered, before any network call (A5): once an execution
     * exists and can run, SIGTERM must be able to kill it.
     */
    async runAgentTurn(
      caseId: string,
      agentName: string,
      brief: string,
      options: RunAgentTurnOptions = {}
    ): Promise<ExecutionObservation> {
      setActiveCaseId(caseId)

      try {
        // --- Anchoring and quiescence check ---------------------------------
        let baselineId: string | null = null
        try {
          const existing = await client.listEvents(caseId)
          baselineId = existing.at(-1)?.id ?? null

          const lastStatus = existing.filter((e) => e.type === CASE_STATUS_EVENT).at(-1)
          if (lastStatus && typeof lastStatus.status === 'string' && !QUIESCENT_STATUSES.includes(lastStatus.status)) {
            return executionFailure(
              'case_busy',
              `Le case est en statut ${lastStatus.status}. run() est auto-gardé côté serveur : ` +
                `poster maintenant produirait un lancement silencieusement abandonné.`
            )
          }
        } catch (err) {
          return executionFailure('error', String(err))
        }

        // --- Post the message ------------------------------------------------
        try {
          await client.postMessage(caseId, `@${agentName} ${brief}`)
        } catch (err) {
          await killQuietly(caseId)
          return executionFailure('error', String(err))
        }

        // --- Wait for start then quiescence ---------------------------------
        const observeOptions: ObserveOptions = { baselineId }
        if (options.startTimeoutMs !== undefined) observeOptions.startTimeoutMs = options.startTimeoutMs
        if (options.workTimeoutMs !== undefined) observeOptions.workTimeoutMs = options.workTimeoutMs
        return await observer.observe(caseId, observeOptions)
      } finally {
        clearActiveCaseId(caseId)
      }
    },
  }
}
