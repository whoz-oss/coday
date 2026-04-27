import { releaseChangelog, releasePublish, releaseVersion } from 'nx/release'
import { readFileSync, writeFileSync } from 'fs'
import { execSync } from 'child_process'
import { join } from 'path'
import { fileURLToPath } from 'url'
import { updateTomlVersion } from './utils/update-toml-version'

const __dirname = fileURLToPath(new URL('.', import.meta.url))

async function main(): Promise<void> {
  const dryRun = process.argv.includes('--dry-run')
  if (dryRun) console.log('Dry run mode — no files will be written, no git operations performed')

  // Step 1: Determine new version and update package.json files
  const { projectsVersionData, releaseGraph } = await releaseVersion({ dryRun, verbose: false })

  // workspaceVersion (from releaseVersion) is undefined when there are multiple release groups — derive from projectsVersionData instead
  const newVersion = Object.values(projectsVersionData).find((v) => v.newVersion)?.newVersion

  if (!newVersion) {
    console.log('No version bump needed')
    process.exit(0)
  }

  console.log(`New version: ${newVersion}`)

  // Step 2: Update Gradle version catalog to match the new release version.
  // This must happen AFTER releaseVersion (which determines the new version)
  // but BEFORE releaseChangelog (which commits, tags, and pushes).
  const tomlRelativePath = 'agentos/gradle/libs.versions.toml'
  if (dryRun) {
    console.log(`[dry-run] Would update libs.versions.toml agentosSdk to ${newVersion}`)
  } else {
    const tomlPath = join(__dirname, '..', tomlRelativePath)
    const tomlContent = readFileSync(tomlPath, 'utf-8')
    const updatedToml = updateTomlVersion(tomlContent, 'agentosSdk', newVersion)
    writeFileSync(tomlPath, updatedToml, 'utf-8')
    console.log(`Updated libs.versions.toml agentosSdk to ${newVersion}`)

    // Explicitly stage the toml file so it's included in the release commit.
    // Nx's releaseChangelog only stages files it knows about (package.json, CHANGELOG.md),
    // so we must stage our additional file manually.
    execSync(`git add ${tomlRelativePath}`, { stdio: 'inherit' })
  }

  // Step 3: Generate changelog, commit all staged changes (including toml), tag, and push
  await releaseChangelog({
    releaseGraph,
    versionData: projectsVersionData,
    version: newVersion,
    dryRun,
    verbose: false,
  })

  // Step 4: Publish packages — JVM projects are skipped via no-op nx-release-publish targets (published via Gradle in CI)
  const publishResults = await releasePublish({
    releaseGraph,
    dryRun,
    verbose: false,
  })

  process.exit(Object.values(publishResults).every((result) => result.code === 0) ? 0 : 1)
}

try {
  await main()
} catch (err) {
  console.error('Release failed:', err)
  process.exit(1)
}
