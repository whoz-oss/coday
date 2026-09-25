// Stateless compatibility facade. Oracle process execution, classification,
// root validation and artifact generation live only in the generated
// operational bundle, built from the TypeScript source
// `factory/src/application/oracle/oracle-executor.ts`.
export {
  classifyOracleExecution,
  validateOracleRoot,
  oracleRootIdentity,
  executeOracle,
  oracleArtifact,
} from '../runtime/factory-operational.mjs'
