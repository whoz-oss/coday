// Stateless compatibility facade. The file-backed delivery store (atomic
// snapshots, append-only journal, write-ahead recovery, rollback requests and
// delivery operations) lives only in the generated operational bundle, built
// from the TypeScript source
// `factory/src/adapters/persistence/delivery-store.ts`.
import { DeliveryStore, createFilesystemDeliveryRepository } from '../runtime/factory-operational.mjs'

export {
  DeliveryStore,
  FilesystemDeliveryRepository,
  createFilesystemDeliveryRepository,
} from '../runtime/factory-operational.mjs'

/**
 * Wires the TypeScript filesystem delivery repository adapter around a concrete
 * store. The adapter implements `DeliveryRepository` from
 * `factory/src/ports/persistence`; the store remains the `.mjs` runtime
 * authority during the migration.
 */
export function createDeliveryRepository(dataRoot, options) {
  return createFilesystemDeliveryRepository(new DeliveryStore(dataRoot, options))
}
