// Stateless compatibility facade. Delivery evidence validation and the
// file-backed evidence journal live only in the generated operational bundle,
// built from the TypeScript source
// `factory/src/adapters/persistence/delivery-evidence-store.ts`.
export {
  validateDeliveryEvidence,
  DeliveryEvidenceStore,
} from '../runtime/factory-operational.mjs'
