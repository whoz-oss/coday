// Stateless compatibility facade. Definition validation, canonical hashing and
// the registry invariants live only in the generated operational bundle, built
// from the TypeScript source `factory/src/domain/oracle/oracle-definition.ts` and
// the filesystem adapter `factory/src/application/oracle/oracle-definition-registry.ts`.
export { validateOracleDefinition, hashOracleDefinition, OracleDefinitionRegistry } from '../runtime/factory-operational.mjs'
