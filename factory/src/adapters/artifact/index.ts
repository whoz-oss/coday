/**
 * Artifact adapters barrel.
 *
 * Two implementations of the `ports/artifact` {@link ArtifactStore} port plus
 * the content-addressing helpers and the low-level S3 / MinIO object client
 * they share. Everything here is autonomous: the only non-relative imports are
 * `node:*`.
 */

export { ARTIFACT_HASH_PREFIX, computeArtifactHash, createArtifactId } from './artifact-hash.js'

export {
  MemoryArtifactStore,
  buildArtifactMetadata,
  computeRetentionStatus,
  computeRetentionUntil,
  createMemoryArtifactStore,
  isArtifactDestroyable,
  isRetentionActive,
  refreshArtifactMetadata,
  toArtifactBytes,
  type BuildArtifactMetadataParams,
  type MemoryArtifactStoreOptions,
} from './memory-artifact-store.js'

export {
  S3ObjectClient,
  createS3ObjectClient,
  type S3ObjectClientConfig,
  type S3ObjectResponse,
} from './s3-object-client.js'

export {
  S3ArtifactStore,
  createS3ArtifactStore,
  type S3ArtifactStoreConfig,
  type S3ArtifactStoreOptions,
} from './s3-artifact-store.js'
