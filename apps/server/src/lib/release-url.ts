/**
 * release-url.ts
 *
 * Maps an artifact version to the GitHub Release hosting its JARs.
 *
 *   regular release     1.2.3                          -> tag `release/1.2.3`
 *   workstream build    0.0.0-workstream-forge.32      -> tag `workstream-forge-32`
 *
 * Workstream prereleases are published under a flat, dash-only tag
 * (see docs/WORKSTREAM_WORKFLOW.md and .github/workflows/workstream-build.yml),
 * never under `release/*`.
 */

const RELEASES_DOWNLOAD_BASE = 'https://github.com/whoz-oss/coday/releases/download'

/** `X.Y.Z-workstream-<slug>.<n>` — slug is lowercase alphanumerics and single hyphens. */
const WORKSTREAM_VERSION_PATTERN = /^\d+\.\d+\.\d+-workstream-([a-z0-9]+(?:-[a-z0-9]+)*)\.(\d+)$/

export type RuntimeVersionSource = 'env' | 'package'

/**
 * Decide which version of a companion service (AgentOS, Factory) to provision.
 *
 * Precedence:
 *   1. explicit env override (local dev / testing)
 *   2. the version of this package. Every release (regular or workstream) builds the npm
 *      packages and the JARs in lockstep under ONE version (single fixed nx release group),
 *      so the package itself identifies the JARs it must run with.
 */
export function resolveRuntimeVersion(
  envOverride: string | undefined,
  packageVersion: string
): { version: string; source: RuntimeVersionSource } {
  if (envOverride) return { version: envOverride, source: 'env' }
  return { version: packageVersion, source: 'package' }
}

/** Git tag of the GitHub Release holding the artifacts of `version`. */
export function releaseTagForVersion(version: string): string {
  const match = WORKSTREAM_VERSION_PATTERN.exec(version)
  return match ? `workstream-${match[1]}-${match[2]}` : `release/${version}`
}

/** Base download URL (no trailing slash) for the assets of `version`. */
export function releaseBaseUrl(version: string): string {
  return `${RELEASES_DOWNLOAD_BASE}/${releaseTagForVersion(version)}`
}
