// Stateless compatibility facade. Definition validation, canonical hashing and
// the registry invariants live only in the generated operational bundle, built
// from the TypeScript source `factory/src/domain/oracle/oracle-definition.ts` and
// the filesystem adapter `factory/src/application/oracle/oracle-definition-registry.ts`.
import {
  OracleDefinitionRegistry,
  createFilesystemOracleExecutionRepository,
} from '../runtime/factory-operational.mjs'

export {
  validateOracleDefinition,
  hashOracleDefinition,
  OracleDefinitionRegistry,
  FilesystemOracleExecutionRepository,
  createFilesystemOracleExecutionRepository,
} from '../runtime/factory-operational.mjs'

/**
 * Wires the TypeScript filesystem oracle-definition repository adapter around a
 * concrete, initialized registry. The adapter implements
 * `OracleExecutionRepository` from `factory/src/ports/persistence`; the registry
 * remains the `.mjs` runtime authority during the migration.
 */
export async function createOracleDefinitionRepository(root) {
  const registry = new OracleDefinitionRegistry(root)
  await registry.initialize()
  return createFilesystemOracleExecutionRepository(registry)
}
