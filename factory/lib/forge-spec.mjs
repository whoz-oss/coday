// Stateless compatibility facade. The Epic spec parser, validation and hashing
// live only in the generated operational bundle, built from the TypeScript
// source `factory/src/domain/forge-bmad/forge-spec.ts` and
// `factory/src/adapters/forge/forge-spec-reader.ts`.
export {
  FORGE_SPEC_SCHEMA_VERSION,
  G2_POLICY_VERSION,
  ORACLE_CATALOG,
  loadForgeSpec,
} from '../runtime/factory-operational.mjs'
