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
 * `X.Y.Z-workstream-<slug>.<n>` where <slug> matches the provided slug and <n> is a
 * non-negative integer.
 *
 * The literal `workstream-` prefix inside the prerelease identifier is REQUIRED, and is
 * the point of this format: every workstream artifact is self-identifying as such from
 * the version string alone, without needing to know the set of valid slugs. It also makes
 * the version consistent with the two other places the same name appears:
 *   - npm dist-tag:  workstream-<slug>
 *   - git tag:       workstream-<slug>-<n>
 *
 * The X.Y.Z base is deliberately NOT pinned here beyond being numeric. The build uses a
 * fixed `0.0.0` base (workstream artifacts are decoupled from the repo's release line),
 * but this function validates SHAPE, not policy — hardcoding `0.0.0` would make that
 * decision impossible to revisit without editing the validator.
 *
 * Note on ambiguity: because slugs may contain hyphens, `0.0.0-workstream-a-b.1` is
 * consistent with slug `a-b`. The pattern is anchored to the slug supplied by the caller,
 * so this is resolved by construction — the workflow derives the slug from the branch
 * name and passes it explicitly, never by parsing it back out of the version.
 */
export function isValidWorkstreamVersion(version: string, slug: string): boolean {
  // Build a pattern anchored to the provided slug so a version/slug mismatch is caught.
  // Double-escaping is required: '\\.' in a JS string produces '\.' in the regex (literal dot).
  const escapedSlug = slug.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const pattern = new RegExp(`^\\d+\\.\\d+\\.\\d+-workstream-${escapedSlug}\\.\\d+$`)
  return pattern.test(version)
}
