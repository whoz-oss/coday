// Stateless compatibility facade. Attempt statuses, record invariants and the
// append-only attempt journal live only in the generated operational bundle,
// built from the TypeScript source
// `factory/src/domain/agent-attempt/agent-step-attempt.ts` and
// `factory/src/adapters/persistence/agent-step-attempt-store.ts`.
export { AGENT_STEP_ATTEMPT_STATUSES, AgentStepAttemptStore } from '../runtime/factory-operational.mjs'
