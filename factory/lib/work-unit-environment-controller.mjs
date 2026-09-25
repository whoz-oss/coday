// Stateless compatibility facade. The trusted work-unit environment
// control-plane and its HTTP request dispatcher live only in the generated
// operational bundle, built from the TypeScript source
// `factory/src/application/environment/work-unit-environment-controller.ts`.
export {
  WorkUnitEnvironmentController,
  handleWorkUnitEnvironmentRequest,
} from '../runtime/factory-operational.mjs'
