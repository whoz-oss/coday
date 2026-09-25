// Stateless compatibility facade. The Story edit service lives only in the
// generated operational bundle, built from the TypeScript source
// `factory/src/application/forge-bmad/forge-story-edit.ts`.
export {
  STORY_EDIT_SCHEMA_VERSION,
  STORY_EDIT_POLICY_VERSION,
  executeStoryEdit,
} from '../runtime/factory-operational.mjs'
