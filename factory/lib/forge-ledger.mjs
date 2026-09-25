// Stateless compatibility facade. The Forge ledger replay and its filesystem
// store live only in the generated operational bundle, built from the
// TypeScript sources `factory/src/domain/forge-bmad/forge-ledger.ts` and
// `factory/src/adapters/forge/forge-ledger-store.ts`.
export {
  FORGE_LEDGER_SCHEMA_VERSION,
  FORGE_WORKFLOW_VERSION,
  createEpicRun,
  parseForgeLedger,
  projectForgeRun,
  listForgeRunProjections,
} from '../runtime/factory-operational.mjs'
