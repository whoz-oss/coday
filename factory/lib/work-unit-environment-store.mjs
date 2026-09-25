// Stateless compatibility facade. The file-backed work-unit environment store
// lives only in the generated operational bundle, built from the TypeScript
// source `factory/src/adapters/persistence/work-unit-environment-store.ts`.
export {
  ENVIRONMENT_STORE_ERROR_CODES,
  WorkUnitEnvironmentStoreError,
  WorkUnitEnvironmentStore,
} from '../runtime/factory-operational.mjs'
