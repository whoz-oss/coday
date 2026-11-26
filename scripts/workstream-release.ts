/**
 * Workstream prerelease publish script.
 *
 * THREE INVIOLABLE RULES (from docs/WORKSTREAM_WORKFLOW.md):
 *   1. Never create a `release/*` tag — it corrupts the next real release's version baseline.
 *   2. Never run releaseChangelog — it commits, tags, and pushes, all wrong on an integration branch.
 *   3. Never publish to the `latest` dist-tag — it would reach every consumer of the package.
 *
 * Flow: stamp versions -> build ALL npm packages -> publish. The build is a separate, all-or-nothing
 * step placed before the first publish: if any build fails the script exits 1 and nothing is published
 * (otherwise releasePublish would publish each package as soon as its own build ends, leaving a
 * partial set on npm).
 *
 * CLI contract (fixed — another agent writes the workflow that calls this):
 *   npx tsx scripts/workstream-release.ts --version <exact-version> --slug <slug> [--dry-run]
 */

// SAFETY GUARANTEE: releaseChangelog is intentionally NOT imported here.
// This omission must NEVER be "fixed" — it is the sole mechanism preventing
// workstream builds from committing version bumps, creating tags, and pushing
// to git, all of which would corrupt the master release baseline.
import { execSync } from 'child_process'
import { stampVersion, publishPackages } from './utils/release-steps'
import { buildNpmPackages } from './utils/build-npm-packages'
import { isValidSlug, isValidWorkstreamVersion } from './utils/workstream-validators'

// Re-export validators so they are testable from the spec without pulling in the nx dependency chain.
export { isValidSlug, isValidWorkstreamVersion }

function parseArgs(): { version: string; slug: string; dryRun: boolean } {
  const args = process.argv.slice(2)
  const get = (flag: string): string | undefined => {
    const idx = args.indexOf(flag)
    return idx !== -1 ? args[idx + 1] : undefined
  }

  const version = get('--version')
  const slug = get('--slug')
  const dryRun = args.includes('--dry-run')

  const errors: string[] = []

  if (!slug) {
    errors.push('--slug is required')
  } else if (!isValidSlug(slug)) {
    errors.push(
      `--slug "${slug}" is invalid. Must match ^[a-z0-9]+(-[a-z0-9]+)*$ ` +
        '(lowercase alphanumerics and single hyphens only, no leading/trailing hyphens)'
    )
  }

  if (!version) {
    errors.push('--version is required')
  } else if (slug && isValidSlug(slug) && !isValidWorkstreamVersion(version, slug)) {
    errors.push(
      `--version "${version}" is invalid. Must be a semver prerelease of the form ` +
        `X.Y.Z-workstream-<slug>.<n> matching slug "${slug}", e.g. 0.0.0-workstream-${slug}.1. ` +
        `The literal "workstream-" prefix is required.`
    )
  }

  if (errors.length > 0) {
    errors.forEach((e) => console.error(`ERROR: ${e}`))
    console.error('\nUsage: npx tsx scripts/workstream-release.ts --version <exact-version> --slug <slug> [--dry-run]')
    process.exit(1)
  }

  return { version: version!, slug: slug!, dryRun }
}

async function main(): Promise<void> {
  const { version, slug, dryRun } = parseArgs()

  if (dryRun) {
    console.log('Dry run mode — no files will be written, no packages published')
  }

  console.log(`Workstream release: version=${version}  slug=${slug}  distTag=workstream-${slug}`)

  // Step 1: Stamp exact version into package.json files and Gradle version catalog.
  // No git operations are performed — nx.json sets commit: false / tag: false, and passing
  // an explicit specifier bypasses conventional-commit resolution entirely.
  const { releaseGraph, projectsVersionData } = await stampVersion(version, dryRun)

  // Step 2: Build every npm package BEFORE publishing any of them. The stamp comes first because each
  // build copies package.json into dist. execSync throws on a non-zero exit, so a failed build lands in
  // the catch below (exit 1) and publishPackages is never reached. Also runs in dry-run: it publishes
  // nothing and validates that the set builds.
  buildNpmPackages((command) => execSync(command, { stdio: 'inherit' }))

  // Step 3: Publish to the workstream-scoped dist-tag. Everything is already built, so skip the
  // per-package `dependsOn: build` of the publish targets.
  // Rule 3: NEVER use 'latest' here — it would reach every consumer of the package.
  await publishPackages(releaseGraph, projectsVersionData, `workstream-${slug}`, dryRun, {
    excludeTaskDependencies: true,
  })

  console.log(`Workstream release complete: ${version} published to dist-tag workstream-${slug}`)
}

try {
  await main()
} catch (err) {
  console.error('Workstream release failed:', err)
  process.exit(1)
}
