import {
  createAgentOsRuntimeAdapter,
  type AgentOsRuntimeAdapter,
  type RunAgentTurnOptions,
  type WorkerPreflightResult,
  type WorkspacePreflightIntegrationResult,
  type WorkspacePreflightResult,
} from '../adapters/agentos/agentos-runtime-adapter.js'
import type { ExecutionObservation, WorkerConfig } from '../ports/agent-runtime-gateway.js'

/**
 * Legacy-compatible AgentOS operations of the operational entrypoint.
 *
 * A single lazily-created default adapter (environment-driven) backs these
 * functions. They exist so `factory/lib/agentos.mjs` can be a stateless
 * delegation facade and so the agent-step executor can default its injected
 * `agentOps` without duplicating the default adapter state.
 */
let defaultAgentOsAdapter: AgentOsRuntimeAdapter | null = null

/** Returns the environment-driven default adapter, creating it on first use. */
export function getAgentOsRuntimeAdapter(): AgentOsRuntimeAdapter {
  defaultAgentOsAdapter ??= createAgentOsRuntimeAdapter()
  return defaultAgentOsAdapter
}

export function createCase(namespaceId: string, title: string): ReturnType<AgentOsRuntimeAdapter['createCase']> {
  return getAgentOsRuntimeAdapter().createCase(namespaceId, title)
}

export function postMessage(caseId: string, content: string): Promise<void> {
  return getAgentOsRuntimeAdapter().postMessage(caseId, content)
}

export function bindFactoryStepResult(caseId: string, binding: unknown): Promise<void> {
  return getAgentOsRuntimeAdapter().bindFactoryStepResult(caseId, binding)
}

export function getCase(caseId: string): Promise<Record<string, unknown>> {
  return getAgentOsRuntimeAdapter().getCase(caseId)
}

export function listEvents(caseId: string): ReturnType<AgentOsRuntimeAdapter['listEvents']> {
  return getAgentOsRuntimeAdapter().listEvents(caseId)
}

export function killCase(caseId: string): Promise<void> {
  return getAgentOsRuntimeAdapter().killCase(caseId)
}

export function listAgents(namespaceId: string): ReturnType<AgentOsRuntimeAdapter['listAgents']> {
  return getAgentOsRuntimeAdapter().listAgents(namespaceId)
}

export function preflightAgent(namespaceId: string, agentName: string): Promise<WorkerPreflightResult> {
  return getAgentOsRuntimeAdapter().preflightAgent(namespaceId, agentName)
}

export function listIntegrations(namespaceId: string): ReturnType<AgentOsRuntimeAdapter['listIntegrations']> {
  return getAgentOsRuntimeAdapter().listIntegrations(namespaceId)
}

export function preflightWorkspace(
  namespaceId: string,
  agent: WorkerConfig,
  repoRoot: string
): Promise<WorkspacePreflightResult> {
  return getAgentOsRuntimeAdapter().preflightWorkspace(namespaceId, agent, repoRoot)
}

export function preflightWritableWorkspace(
  namespaceId: string,
  agent: WorkerConfig,
  repoRoot: string
): Promise<WorkspacePreflightIntegrationResult> {
  return getAgentOsRuntimeAdapter().preflightWritableWorkspace(namespaceId, agent, repoRoot)
}

export function preflightReadOnlyWorkspace(
  namespaceId: string,
  agent: WorkerConfig,
  repoRoot: string
): Promise<WorkspacePreflightIntegrationResult> {
  return getAgentOsRuntimeAdapter().preflightReadOnlyWorkspace(namespaceId, agent, repoRoot)
}

export function runAgentTurn(
  caseId: string,
  agentName: string,
  brief: string,
  options?: RunAgentTurnOptions
): Promise<ExecutionObservation> {
  return getAgentOsRuntimeAdapter().runAgentTurn(caseId, agentName, brief, options)
}
