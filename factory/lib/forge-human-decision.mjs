// Stateless compatibility facade. The G1 human-decision policy, evidence hash
// and recording service live only in the generated operational bundle, built
// from the TypeScript sources
// `factory/src/domain/forge-bmad/forge-human-decision.ts` and
// `factory/src/application/forge-bmad/forge-human-decision.ts`.
export {
  G1_POLICY_VERSION,
  computeG1EvidenceSetHash,
  recordHumanDecision,
} from '../runtime/factory-operational.mjs'
