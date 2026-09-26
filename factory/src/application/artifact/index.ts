/**
 * Artifact application barrel.
 *
 * Re-exports the admin governance use cases (explicit purge, legal-hold
 * management and triggered garbage collection). These are *pure orchestration*
 * functions: the composition root injects the concrete `ArtifactStore` and blob
 * client, and nothing here schedules itself — every command is explicit.
 */

export {
  ARTIFACT_ADMIN_OBJECT_PREFIX,
  ARTIFACT_ADMIN_UPLOAD_PREFIX,
  ArtifactAdminError,
  collectAndAuditGarbage,
  purgeArtifactAdmin,
  setLegalHoldAdmin,
  type ArtifactAdminPurgeResult,
  type ArtifactAdminPurgeStatus,
  type ArtifactGarbageCollectionOptions,
  type ArtifactGarbageCollectionReport,
  type ArtifactGcAnomaly,
  type ArtifactGcAnomalyType,
  type ArtifactGcMetadataRow,
} from './artifact-admin-use-cases.js'
