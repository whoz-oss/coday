// Stateless compatibility facade. The Forge workflow sync service lives only in
// the generated operational bundle, built from the TypeScript source
// `factory/src/application/forge-bmad/forge-workflow-sync.ts`.
export {
  SAFE_FORGE_TICKET_ID,
  sanitizeForgeSyncAttribution,
  syncForgeWorkflowProjection,
} from '../runtime/factory-operational.mjs'
