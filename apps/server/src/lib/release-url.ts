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

/** Git tag of the GitHub Release holding the artifacts of `version`. */
export function releaseTagForVersion(version: string): string {
  const match = WORKSTREAM_VERSION_PATTERN.exec(version)
  return match ? `workstream-${match[1]}-${match[2]}` : `release/${version}`
}

/** Base download URL (no trailing slash) for the assets of `version`. */
export function releaseBaseUrl(version: string): string {
  return `${RELEASES_DOWNLOAD_BASE}/${releaseTagForVersion(version)}`
}
