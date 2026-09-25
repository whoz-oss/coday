// Stateless compatibility facade. The G2 / G2-US gate services live only in the
// generated operational bundle, built from the TypeScript sources
// `factory/src/domain/forge-bmad/forge-spec.ts`,
// `factory/src/domain/forge-bmad/forge-story-spec.ts` and
// `factory/src/application/forge-bmad/forge-g2.ts`.
export { evaluateG2, evaluateG2US } from '../runtime/factory-operational.mjs'
