// Stateless compatibility facade. The Story oracle campaign service lives only
// in the generated operational bundle, built from the TypeScript source
// `factory/src/application/forge-bmad/forge-story-oracles.ts`.
export {
  STORY_ORACLE_POLICY_VERSION,
  isAllowedStoryOracleRequestBody,
  executeStoryOracles,
} from '../runtime/factory-operational.mjs'
