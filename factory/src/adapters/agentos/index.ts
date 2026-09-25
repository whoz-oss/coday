/**
 * AgentOS adapter barrel.
 *
 * Wire DTOs (`agentos-dtos.ts`) are intentionally NOT re-exported here: they
 * must stay private to this directory. Everything else that composes the
 * `AgentRuntimeGateway` implementation is exported for the operational
 * entrypoint and contract tests.
 */

export {
  createAgentOsRuntimeAdapter,
  type AgentOsRuntimeAdapter,
  type AgentOsRuntimeAdapterConfig,
  type RunAgentTurnOptions,
  type WorkerPreflightResult,
  type WorkspacePreflightIntegrationResult,
  type WorkspacePreflightResult,
} from './agentos-runtime-adapter.js'

export {
  createAgentOsHttpClient,
  type AgentOsHttpClient,
  type AgentOsHttpClientConfig,
  type PostMessageOptions,
} from './agentos-http-client.js'

export {
  createAgentOsCapabilityInspector,
  formatAgentInventory,
  normalizeRoot,
  type AgentOsCapabilityInspector,
  type AgentOsCapabilityInspectorDeps,
} from './agentos-capability-inspector.js'

export {
  createAgentOsRuntimeObserver,
  executionFailure,
  DEFAULT_POLL_INTERVAL_MS,
  DEFAULT_START_TIMEOUT_MS,
  DEFAULT_WORK_TIMEOUT_MS,
  type AgentOsRuntimeObserver,
  type AgentOsRuntimeObserverDeps,
} from './agentos-runtime-observer.js'

export {
  buildFailedToolCalls,
  CASE_STATUS_EVENT,
  collectAgentsSelected,
  collectLlmModels,
  countType,
  extractLastAgentMessage,
  findLastStatusEvent,
  findStatusEvent,
  findUnansweredQuestions,
  QUIESCENT_STATUSES,
  sliceAfterId,
  toRuntimeEvent,
  toRuntimeEvents,
  type RawCaseEvent,
} from './agentos-event-translator.js'

export { createAgentOsHttpCaseTerminator, type AgentOsCaseTerminatorOptions } from './agentos-http-case-terminator.js'
