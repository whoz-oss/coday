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
export * from '../domain/environment/work-unit-environment.js'

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

// --------------------------------------------------------------------------
// Oracle measurement: pure domain plus application execution.
//
// Le domaine oracle ne dépend d'aucun accès `node:fs`, process ou Git ; les
// commandes, l'exécution, le baseline et la classification vivent dans
// `application/oracle/`. Les façades `factory/lib/oracle*.mjs` réexportent la
// surface ci-dessous sans dupliquer d'état.
// --------------------------------------------------------------------------
export * from '../domain/oracle/oracle.js'
export * from '../domain/oracle/oracle-definition.js'
export * from '../application/oracle/oracle-definition-registry.js'
export * from '../application/oracle/oracle-command.js'
export * from '../application/oracle/oracle-executor.js'
export * from '../application/oracle/oracle-baseline.js'

// --------------------------------------------------------------------------
// Work-unit environment: pure domain, file-backed store, provisioning service
// and trusted control-plane controller.
//
// Le domaine (`domain/environment/work-unit-environment.ts`) ne porte aucune
// dépendance `node:fs`/Git/AgentOS ; le store, le service et le contrôleur
// vivent dans `adapters/persistence/` et `application/environment/`. Les
// façades `factory/lib/work-unit-environment*.mjs` réexportent la surface
// ci-dessous sans dupliquer d'état.
// --------------------------------------------------------------------------
export * from '../adapters/persistence/work-unit-environment-store.js'
export * from '../application/environment/work-unit-environment-service.js'
export * from '../application/environment/work-unit-environment-controller.js'

// --------------------------------------------------------------------------
// Delivery (tranche 8): pure domain, file-backed stores, control-plane
// adapters and trusted application controllers.
//
// Le domaine (`domain/delivery/*`) ne porte aucune dépendance node:fs/HTTP/Git
// ; les stores, adaptateurs et contrôleurs vivent dans `adapters/` et
// `application/`. Les façades `factory/lib/delivery-*.mjs` réexportent la
// surface ci-dessous sans dupliquer d'état.
// --------------------------------------------------------------------------
export * from '../domain/delivery/delivery-definition.js'
export * from '../domain/delivery/delivery-policy.js'
export * from '../domain/delivery/delivery-operation-definition.js'
export * from '../domain/delivery/delivery-operation-policy.js'
export * from '../adapters/persistence/delivery-store.js'
export * from '../adapters/persistence/delivery-evidence-store.js'
export * from '../adapters/delivery/delivery-target-registry.js'
export * from '../adapters/delivery/delivery-git-control-plane.js'
export * from '../adapters/delivery/delivery-pr-adapter.js'
export * from '../adapters/delivery/delivery-deployment-adapter.js'
export * from '../application/delivery/delivery-controller.js'
export * from '../application/delivery/delivery-operation-controller.js'

// --------------------------------------------------------------------------
// Forge/BMAD (tranche 9): pure domain, filesystem/HTTP adapters and trusted
// application services.
//
// Le domaine (`domain/forge-bmad/*`) ne porte aucune dépendance node:fs, HTTP,
// AgentOS ou Git CLI ; les lectures/écritures de fichiers et le client Jira
// vivent dans `adapters/forge/` et `adapters/jira/`, et les services
// d'orchestration dans `application/forge-bmad/`. Les façades
// `factory/lib/forge-*.mjs` et `factory/lib/jira.mjs` réexportent la surface
// ci-dessous sans dupliquer d'état.
// --------------------------------------------------------------------------
export * from '../domain/forge-bmad/types.js'
export * from '../domain/forge-bmad/forge-roots.js'
export * from '../domain/forge-bmad/forge-human-decision.js'
export * from '../domain/forge-bmad/forge-spec.js'
export * from '../domain/forge-bmad/forge-story-spec.js'
export * from '../domain/forge-bmad/forge-bmad-parser.js'
export * from '../domain/forge-bmad/forge-ledger.js'
export * from '../domain/forge-bmad/forge-workflow-adapter.js'
export * from '../domain/forge-bmad/jira.js'
export * from '../adapters/forge/forge-roots-resolver.js'
export * from '../adapters/forge/forge-bmad-file-reader.js'
export * from '../adapters/forge/forge-spec-reader.js'
export * from '../adapters/forge/forge-ledger-store.js'
export * from '../adapters/jira/jira-client.js'
export * from '../application/forge-bmad/forge-human-decision.js'
export * from '../application/forge-bmad/forge-g2.js'
export * from '../application/forge-bmad/forge-story-analysis.js'
export * from '../application/forge-bmad/forge-story-edit.js'
export * from '../application/forge-bmad/forge-story-oracles.js'
export * from '../application/forge-bmad/forge-workflow-sync.js'
export * from '../application/forge-bmad/forge-front-oracle-resolution.js'
