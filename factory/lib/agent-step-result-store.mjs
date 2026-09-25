// Stateless compatibility facade. The structured result schema, capability
// ledger and append-only result journal live only in the generated operational
// bundle, built from the TypeScript source
// `factory/src/domain/agent-attempt/agent-step-result.ts` and
// `factory/src/adapters/persistence/agent-step-result-store.ts`.
export { AgentStepResultStore, hashAgentStepResult } from '../runtime/factory-operational.mjs'
