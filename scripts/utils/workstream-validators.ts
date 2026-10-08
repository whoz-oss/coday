/**
 * Pure validation functions for workstream release parameters.
 * Extracted as a separate module so they can be unit-tested without loading the nx API.
 */

/** Validates a workstream slug: lowercase alphanumerics and single hyphens only, no leading/trailing hyphens. */
export function isValidSlug(slug: string): boolean {
  return /^[a-z0-9]+(-[a-z0-9]+)*$/.test(slug)
}

/**
 * Validates that a version string is a valid semver prerelease of the exact form
 * `X.Y.Z-<slug>.<n>` where <slug> matches the provided slug and <n> is a non-negative integer.
 */
export function isValidWorkstreamVersion(version: string, slug: string): boolean {
  // Build a pattern anchored to the provided slug so a version/slug mismatch is caught.
  // Double-escaping is required: '\\.' in a JS string produces '\.' in the regex (literal dot).
  const escapedSlug = slug.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const pattern = new RegExp(`^\\d+\\.\\d+\\.\\d+-${escapedSlug}\\.\\d+$`)
  return pattern.test(version)
}
