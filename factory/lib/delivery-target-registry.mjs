// Stateless compatibility facade. The trusted delivery-target registry lives
// only in the generated operational bundle, built from the TypeScript source
// `factory/src/adapters/delivery/delivery-target-registry.ts`.
export {
  DeliveryTargetRegistry,
  unavailableDeliveryTargetRegistry,
} from '../runtime/factory-operational.mjs'
