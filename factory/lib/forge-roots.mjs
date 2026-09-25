// Stateless compatibility facade. The Forge roots resolution and policy live
// only in the generated operational bundle, built from the TypeScript source
// `factory/src/domain/forge-bmad/forge-roots.ts` and
// `factory/src/adapters/forge/forge-roots-resolver.ts`.
export {
  FORGE_ROOTS_SCHEMA_VERSION,
  DEFAULT_RUN_STORE_POLICY,
  EXTERNAL_RUN_STORE_POLICY,
  REPO_RUN_STORE_POLICY,
  resolveForgeRoots,
  defaultRunStoreRoot,
  ensureForgeRunStore,
} from '../runtime/factory-operational.mjs'
