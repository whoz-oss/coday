// Stateless compatibility facade. The front oracle resolution service lives
// only in the generated operational bundle, built from the TypeScript source
// `factory/src/application/forge-bmad/forge-front-oracle-resolution.ts`.
export {
  FRONT_ORACLE_MAP_SCHEMA_VERSION,
  resolveOwnerProjectConfigs,
  inspectNxProject,
  parseFrontBuildHostMap,
  resolveFrontOraclePlan,
} from '../runtime/factory-operational.mjs'
