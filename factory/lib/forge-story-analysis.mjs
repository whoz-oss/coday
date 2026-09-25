// Stateless compatibility facade. The Story analysis service lives only in the
// generated operational bundle, built from the TypeScript source
// `factory/src/application/forge-bmad/forge-story-analysis.ts`.
export {
  AGENT_EXECUTION_REFERENCE_SCHEMA_VERSION,
  STORY_ANALYSIS_POLICY_VERSION,
  STORY_ANALYSIS_PLAN_SCHEMA_VERSION,
  writeStoryAnalysisArtifact,
  executeStoryAnalysis,
} from '../runtime/factory-operational.mjs'
