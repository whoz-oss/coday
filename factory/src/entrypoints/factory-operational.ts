// Operational entrypoint of the Factory runtime.
//
// This is the single public surface of the generated bundle
// (`factory/runtime/factory-operational.mjs`). It composes the active-case
// registry, the run registry, the shutdown application, the bounded AgentOS
// case terminator and the AgentOS `AgentRuntimeGateway` adapter, plus the
// legacy-compatible AgentOS operation functions that `factory/lib/agentos.mjs`
// delegates to.

export * from '../lib/active-case.js'
export * from '../lib/registry.js'
export * from '../domain/workflow/workflow-definition.js'
export * from '../domain/workflow/workflow-instance.js'
export * from '../domain/workflow/workflow-transition-policy.js'
export * from '../domain/evidence/workflow-evidence.js'
export * from '../domain/interaction/workflow-human-interaction.js'
export * from '../domain/agent-attempt/agent-step-attempt.js'
export * from '../domain/agent-attempt/agent-step-result.js'

// --------------------------------------------------------------------------
// Persistence: storage kernel, repository ports and filesystem adapters
//
// The kernel primitives are the physical authority shared by the legacy `.mjs`
// stores (which import them from this bundle) and the TypeScript adapters. The
// ports are pure types; the adapters are wired by the `.mjs` facades.
// --------------------------------------------------------------------------
export * from '../infrastructure/storage/storage-kernel.js'
export * from '../ports/persistence/index.js'
export * from '../adapters/persistence/index.js'

export { createShutdownController } from '../application/shutdown.js'
export { createAgentOsHttpCaseTerminator } from '../adapters/agentos/agentos-http-case-terminator.js'
export { installSigtermHandler, processExit } from '../adapters/process-shutdown.js'
export type { CaseTerminator } from '../ports/case-terminator.js'
export type { ShutdownController, ShutdownDependencies } from '../application/shutdown.js'

// --------------------------------------------------------------------------
// Agent runtime gateway: port, vocabulary and AgentOS adapter
// --------------------------------------------------------------------------
export {
  asRuntimeExecutionId,
  type AgentRuntimeGateway,
  type ExecutionObservation,
  type ExecutionOptions,
  type ExecutionStatus,
  type ExecutionSummary,
  type HumanInputRequest,
  type WorkspaceIntegration,
  type IntegrationParameters,
  type ObserveOptions,
  type ResultBinding,
  type RuntimeAnswerEvent,
  type RuntimeEvent,
  type RuntimeExecutionId,
  type RuntimeFailure,
  type RuntimeMessageEvent,
  type RuntimeModelUsage,
  type RuntimeOtherEvent,
  type RuntimeQuestionEvent,
  type RuntimeStatusEvent,
  type RuntimeToolResponseEvent,
  type RuntimeWorkerFinishedEvent,
  type RuntimeWorkerRunningEvent,
  type RuntimeWorkerSelectedEvent,
  type StructuredResultRef,
  type WorkerConfig,
  type WorkerIdentity,
  type WorkerInspectionResult,
  type WorkspaceInspectionResult,
} from '../ports/agent-runtime-gateway.js'

export {
  createAgentOsHttpClient,
  type AgentOsHttpClient,
  type AgentOsHttpClientConfig,
} from '../adapters/agentos/agentos-http-client.js'

export {
  createAgentOsRuntimeAdapter,
  type AgentOsRuntimeAdapter,
  type AgentOsRuntimeAdapterConfig,
  type RunAgentTurnOptions,
  type WorkerPreflightResult,
  type WorkspacePreflightIntegrationResult,
  type WorkspacePreflightResult,
} from '../adapters/agentos/agentos-runtime-adapter.js'

// --------------------------------------------------------------------------
// Legacy-compatible AgentOS operations
//
// A single lazily-created default adapter (environment-driven) backs these
// functions. They exist so `factory/lib/agentos.mjs` can be a stateless
// delegation facade and so the agent-step executor can default its injected
// `agentOps` without duplicating the default adapter state.
// --------------------------------------------------------------------------
export * from '../application/agentos-operations.js'

// --------------------------------------------------------------------------
// Agent step attempt / result stores and step executor
// --------------------------------------------------------------------------
export * from '../application/agent-attempt/factory-agent-step-executor.js'
