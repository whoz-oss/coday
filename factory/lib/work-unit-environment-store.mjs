// Stateless compatibility facade. The file-backed work-unit environment store
// lives only in the generated operational bundle, built from the TypeScript
// source `factory/src/adapters/persistence/work-unit-environment-store.ts`.
import { WorkUnitEnvironmentStore, createFilesystemWorkEnvironmentRepository } from '../runtime/factory-operational.mjs'

export {
  ENVIRONMENT_STORE_ERROR_CODES,
  WorkUnitEnvironmentStoreError,
  WorkUnitEnvironmentStore,
  FilesystemWorkEnvironmentRepository,
  createFilesystemWorkEnvironmentRepository,
} from '../runtime/factory-operational.mjs'

/**
 * Wires the TypeScript filesystem work-environment repository adapter around a
 * concrete, initialized store. The adapter implements `WorkEnvironmentRepository`
 * from `factory/src/ports/persistence`; the store remains the `.mjs` runtime
 * authority during the migration. Initialization is awaited because the store
 * resolves its canonical `environments/` root lazily (unlike the stateless
 * journal stores).
 */
export async function createWorkEnvironmentRepository(dataRoot, options) {
  const store = new WorkUnitEnvironmentStore(dataRoot, options)
  await store.initialize()
  return createFilesystemWorkEnvironmentRepository(store)
}
