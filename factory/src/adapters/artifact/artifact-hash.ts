/**
 * Content addressing for artifacts.
 *
 * Uses only the standard Node `node:crypto` module so the operational bundle
 * keeps its `node:*`-only dependency invariant.
 */

import { createHash, randomUUID } from 'node:crypto'

/** Content address prefix used by every artifact hash. */
export const ARTIFACT_HASH_PREFIX = 'sha256'

/** Computes the content address (`sha256:<hex>`) of a payload. */
export function computeArtifactHash(data: Buffer | Uint8Array): string {
  return `${ARTIFACT_HASH_PREFIX}:${createHash('sha256').update(data).digest('hex')}`
}

/** Generates a fresh, opaque artifact identifier. */
export function createArtifactId(): string {
  return randomUUID()
}
