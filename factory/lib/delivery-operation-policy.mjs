// Stateless compatibility facade. Delivery operation policy evaluation and
// verification-request resolution live only in the generated operational
// bundle, built from the TypeScript source
// `factory/src/domain/delivery/delivery-operation-policy.ts`.
export {
  evaluateDeliveryOperationPolicy,
  resolveDeliveryVerificationRequest,
} from '../runtime/factory-operational.mjs'
