/**
 * Compatibility facade for the AgentOS runtime boundary.
 *
 * The canonical implementation now lives in TypeScript under
 * `factory/src/adapters/agentos/` (behind the `AgentRuntimeGateway` port) and is
 * bundled into the operational runtime `factory/runtime/factory-operational.mjs`.
 *
 * This module is intentionally stateless: it owns no HTTP logic, no endpoint,
 * no event translation and no duplicate state. Every export is a direct
 * delegation to the operational bundle, so callers keep their exact signatures
 * and return structures.
 *
 * DETTE TECHNIQUE (inchangée) : l'orchestrateur s'authentifie sous une identité
 * humaine (FACTORY_USER) via l'adaptateur. Les runs sont donc attribués à
 * quelqu'un qui ne les a pas faits. Il faudra un utilisateur technique dédié
 * (ex. `factory-bot`) dès qu'AgentOS supportera les comptes de service.
 */

export {
  createCase,
  postMessage,
  bindFactoryStepResult,
  getCase,
  listEvents,
  killCase,
  listAgents,
  preflightAgent,
  listIntegrations,
  preflightWorkspace,
  preflightWritableWorkspace,
  preflightReadOnlyWorkspace,
  runAgentTurn,
} from '../runtime/factory-operational.mjs'
