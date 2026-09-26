// Stateless compatibility facade. The admin governance use cases for factory
// artifacts (explicit purge, legal-hold management and triggered garbage
// collection with anomaly audit) live only in the generated operational
// bundle, built from the TypeScript source
// `factory/src/application/artifact/artifact-admin-use-cases.ts`.
export {
  ARTIFACT_ADMIN_OBJECT_PREFIX,
  ARTIFACT_ADMIN_UPLOAD_PREFIX,
  ArtifactAdminError,
  collectAndAuditGarbage,
  purgeArtifactAdmin,
  setLegalHoldAdmin,
} from '../runtime/factory-operational.mjs'
