// Stateless compatibility facade. Attempt statuses, record invariants and the
// append-only attempt journal live only in the generated operational bundle,
// built from the TypeScript source
// `factory/src/domain/agent-attempt/agent-step-attempt.ts` and
// `factory/src/adapters/persistence/agent-step-attempt-store.ts`.
import { AgentStepAttemptStore, createFilesystemAgentStepAttemptRepository } from '../runtime/factory-operational.mjs'

export {
  AGENT_STEP_ATTEMPT_STATUSES,
  AgentStepAttemptStore,
  FilesystemAgentStepAttemptRepository,
  createFilesystemAgentStepAttemptRepository,
} from '../runtime/factory-operational.mjs'

/**
 * Wires the TypeScript filesystem attempt-repository adapter around a concrete
 * store. The adapter implements `AgentStepAttemptRepository` from
 * `factory/src/ports/persistence`; the store remains the `.mjs` runtime
 * authority during the migration.
 */
export function createAgentStepAttemptRepository(dataRoot) {
  return createFilesystemAgentStepAttemptRepository(new AgentStepAttemptStore(dataRoot))
}
