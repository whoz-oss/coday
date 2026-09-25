// Stateless compatibility facade. The structured result schema, capability
// ledger and append-only result journal live only in the generated operational
// bundle, built from the TypeScript source
// `factory/src/domain/agent-attempt/agent-step-result.ts` and
// `factory/src/adapters/persistence/agent-step-result-store.ts`.
import { AgentStepResultStore, createFilesystemAgentStepResultRepository } from '../runtime/factory-operational.mjs'

export {
  AgentStepResultStore,
  hashAgentStepResult,
  FilesystemAgentStepResultRepository,
  createFilesystemAgentStepResultRepository,
} from '../runtime/factory-operational.mjs'

/**
 * Wires the TypeScript filesystem result-repository adapter around a concrete
 * store. The adapter implements `AgentStepResultRepository` from
 * `factory/src/ports/persistence`; the store remains the `.mjs` runtime
 * authority during the migration.
 */
export function createAgentStepResultRepository(dataRoot, options) {
  return createFilesystemAgentStepResultRepository(new AgentStepResultStore(dataRoot, options))
}
