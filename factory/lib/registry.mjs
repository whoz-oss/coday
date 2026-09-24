// Stateless compatibility facade. Registry state lives only in the generated operational bundle.
export {
  createRun,
  startPhase,
  passPhase,
  failPhase,
  endRun,
  endCurrentRunOnce,
  getCurrentRun,
} from '../runtime/factory-operational.mjs'
