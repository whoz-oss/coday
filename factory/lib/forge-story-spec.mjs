// Stateless compatibility facade. The Story spec parser, inheritance rules and
// hashing live only in the generated operational bundle, built from the
// TypeScript source `factory/src/domain/forge-bmad/forge-story-spec.ts` and
// `factory/src/adapters/forge/forge-spec-reader.ts`.
export {
  FORGE_STORY_SPEC_SCHEMA_VERSION,
  G2_US_POLICY_VERSION,
  validateInheritance,
  readStorySpec,
  hashStorySpec,
} from '../runtime/factory-operational.mjs'
