// Stateless compatibility facade. Delivery promotion request validation,
// idempotency hashing and the ordered, evidence-gated promotion policy live
// only in the generated operational bundle, built from the TypeScript source
// `factory/src/domain/delivery/delivery-policy.ts`.
export {
  DELIVERY_INITIAL_STAGE,
  validateDeliveryPromotionRequest,
  deliveryScopeHash,
  deliverySemanticHash,
  evaluateDeliveryPromotion,
  applyDeliveryPromotion,
} from '../runtime/factory-operational.mjs'
