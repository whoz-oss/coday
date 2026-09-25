/**
 * Artifact port barrel.
 *
 * Pure types: importing this module pulls in no runtime code, only the
 * vocabulary of the artifact bounded context.
 */

export type {
  ArtifactAvailabilityStatus,
  ArtifactMetadata,
  ArtifactRetentionStatus,
  ArtifactStore,
  OpenArtifactResult,
  PutArtifactParams,
} from './artifact-store.js'
