// Stateless compatibility facade. The file-backed delivery store (atomic
// snapshots, append-only journal, write-ahead recovery, rollback requests and
// delivery operations) lives only in the generated operational bundle, built
// from the TypeScript source
// `factory/src/adapters/persistence/delivery-store.ts`.
export { DeliveryStore } from '../runtime/factory-operational.mjs'
