// Stateless compatibility facade. Delivery definition vocabulary, stages,
// evidence kinds, validation, hashing and the default definition live only in
// the generated operational bundle, built from the TypeScript source
// `factory/src/domain/delivery/delivery-definition.ts`.
export {
  DELIVERY_DEFINITION_SCHEMA_VERSION,
  DELIVERY_STAGES,
  DELIVERY_EVIDENCE_KINDS,
  validateDeliveryDefinition,
  hashDeliveryDefinition,
  defaultDeliveryDefinition,
} from '../runtime/factory-operational.mjs'
