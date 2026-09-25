// Stateless compatibility facade. Baseline execution, diagnostic normalization,
// classification and quarantine record building live only in the generated
// operational bundle, built from the TypeScript source
// `factory/src/application/oracle/oracle-baseline.ts`.
export {
  normalizeDiagnosticLine,
  extractOracleDiagnostics,
  isInfrastructureIdentity,
  runBaselineOracle,
  classifyOracleResult,
  buildQuarantineRecord,
} from '../runtime/factory-operational.mjs'
