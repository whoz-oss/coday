// Stateless compatibility facade. The trusted delivery control-plane and its
// HTTP request dispatcher live only in the generated operational bundle,
// built from the TypeScript source
// `factory/src/application/delivery/delivery-controller.ts`.
export {
  DeliveryController,
  handleDeliveryRequest,
} from '../runtime/factory-operational.mjs'
