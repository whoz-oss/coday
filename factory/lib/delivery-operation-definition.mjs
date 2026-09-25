// Stateless compatibility facade. Delivery operation kinds, states, error
// codes, canonical hashing, request normalization, identity derivation,
// transitions and the record contract live only in the generated operational
// bundle, built from the TypeScript source
// `factory/src/domain/delivery/delivery-operation-definition.ts`.
export {
  DELIVERY_OPERATION_KINDS,
  DELIVERY_OPERATION_STATES,
  DELIVERY_OPERATION_ERROR_CODES,
  canonicalDeliveryHash,
  normalizeDeliveryOperationRequest,
  deriveDeliveryOperationIdentity,
  validateDeliveryOperationTransition,
  validateDeliveryOperationRecord,
} from '../runtime/factory-operational.mjs'
