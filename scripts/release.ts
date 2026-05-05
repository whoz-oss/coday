import { releaseChangelog, releasePublish, releaseVersion } from 'nx/release'
import { readFileSync, writeFileSync } from 'fs'
import { execSync } from 'child_process'
import { join } from 'path'
import { fileURLToPath } from 'url'
import { updateTomlVersions } from './utils/update-toml-version'

const __dirname = fileURLToPath(new URL('.', import.meta.url))

async function main(): Promise<void> {
  const dryRun = process.argv.includes('--dry-run')
  if (dryRun) console.log('Dry run mode — no files will be written, no git operations performed')

  // Step 1: Determine new version and update package.json files
  const { projectsVersionData, releaseGraph, workspaceVersion } = await releaseVersion({ dryRun, verbose: false })

  // workspaceVersion is null when conventional commits detected no changes, undefined would indicate a misconfiguration
  if (workspaceVersion === undefined) {
    console.error('workspaceVersion is undefined — expected a single fixed release group')
    process.exit(1)
  }

  if (workspaceVersion === null) {
    console.log('No version bump needed')
    process.exit(0)
  }

  console.log(`New version: ${workspaceVersion}`)

  // Step 2: Update Gradle version catalog to match the new release version.
  // This must happen AFTER releaseVersion (which determines the new version)
  // but BEFORE releaseChangelog (which commits, tags, and pushes).
  //
  // Both agentosSdk (SDK library) and agentosService (service + all plugins) are
  // kept in sync with the Nx workspace version. Add new keys here when new
  // versioned Gradle artifacts are introduced.
  const tomlRelativePath = 'agentos/gradle/libs.versions.toml'
  const tomlVersionKeys = ['agentosSdk', 'agentosService']

  if (dryRun) {
    tomlVersionKeys.forEach((key) =>
      console.log(`[dry-run] Would update libs.versions.toml ${key} to ${workspaceVersion}`)
    )
  } else {
    const tomlPath = join(__dirname, '..', tomlRelativePath)
    writeFileSync(
      tomlPath,
      updateTomlVersions(readFileSync(tomlPath, 'utf-8'), tomlVersionKeys, workspaceVersion),
      'utf-8'
    )
    console.log(`Updated libs.versions.toml keys [${tomlVersionKeys.join(', ')}] to ${workspaceVersion}`)

    // Explicitly stage the toml file so it's included in the release commit.
    // Nx's releaseChangelog only stages files it knows about (package.json, CHANGELOG.md),
    // so we must stage our additional file manually.
    execSync(`git add ${tomlRelativePath}`, { stdio: 'inherit' })
  }

  // Step 3: Generate changelog, commit all staged changes (including toml), tag, and push
  await releaseChangelog({
    dryRun,
    releaseGraph,
    verbose: false,
    version: workspaceVersion,
    versionData: projectsVersionData,
  })

  // Step 4: Publish packages — JVM projects are skipped via no-op nx-release-publish targets (published via Gradle in CI)
  const publishResults = await releasePublish({
    dryRun,
    releaseGraph,
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
