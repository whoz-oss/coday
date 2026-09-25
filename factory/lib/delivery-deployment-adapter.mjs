// Stateless compatibility facade. Delivery deployment/verification adapter
// contracts, outcome normalization and the unconfigured adapters live only in
// the generated operational bundle, built from the TypeScript source
// `factory/src/adapters/delivery/delivery-deployment-adapter.ts`.
export {
  DELIVERY_ADAPTER_OUTCOMES,
  normalizeDeliveryAdapterOutcome,
  DeliveryDeploymentAdapter,
  DeliveryVerificationAdapter,
  UnconfiguredDeliveryDeploymentAdapter,
  UnconfiguredDeliveryVerificationAdapter,
} from '../runtime/factory-operational.mjs'
