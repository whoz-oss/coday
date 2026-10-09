import { releaseChangelog, releaseVersion } from 'nx/release'
import { appendFileSync, readFileSync, writeFileSync } from 'fs'
import { execSync } from 'child_process'
import { join } from 'path'
import { fileURLToPath } from 'url'
import { updateTomlVersions } from './utils/update-toml-version'
import { publishPackages } from './utils/release-steps'

const __dirname = fileURLToPath(new URL('.', import.meta.url))

async function main(): Promise<void> {
  const dryRun = process.argv.includes('--dry-run')
  if (dryRun) console.log('Dry run mode — no files will be written, no git operations performed')

  const specifierIndex = process.argv.indexOf('--specifier')
  const specifier = specifierIndex !== -1 ? process.argv[specifierIndex + 1] : undefined
  if (specifier) {
    console.log(`Forced specifier: ${specifier}`)
  }

  // Step 1: Determine new version and update package.json files
  const { projectsVersionData, releaseGraph, workspaceVersion } = await releaseVersion({
    dryRun,
    verbose: false,
    specifier,
  })

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

  if (!dryRun && process.env.GITHUB_OUTPUT) {
    appendFileSync(process.env.GITHUB_OUTPUT, `new_version=${workspaceVersion}\n`)
  }

  // Step 2: Update Gradle version catalogs to match the new release version.
  // This must happen AFTER releaseVersion (which determines the new version)
  // but BEFORE releaseChangelog (which commits, tags, and pushes).
  //
  // Both the agentos and factory catalogs are stamped in sync with the Nx workspace
  // version. Add new keys here when new versioned Gradle artifacts are introduced.
  const agentosTomlRelativePath = 'agentos/gradle/libs.versions.toml'
  const agentosTomlVersionKeys = ['agentosSdk', 'agentosService']

  const factoryTomlRelativePath = 'factory/gradle/libs.versions.toml'
  const factoryTomlVersionKeys = ['factorySdk', 'factoryService']

  if (dryRun) {
    agentosTomlVersionKeys.forEach((key) =>
      console.log(`[dry-run] Would update agentos/gradle/libs.versions.toml ${key} to ${workspaceVersion}`)
    )
    factoryTomlVersionKeys.forEach((key) =>
      console.log(`[dry-run] Would update factory/gradle/libs.versions.toml ${key} to ${workspaceVersion}`)
    )
  } else {
    const agentosTomlPath = join(__dirname, '..', agentosTomlRelativePath)
    writeFileSync(
      agentosTomlPath,
      updateTomlVersions(readFileSync(agentosTomlPath, 'utf-8'), agentosTomlVersionKeys, workspaceVersion),
      'utf-8'
    )
    console.log(
      `Updated agentos/gradle/libs.versions.toml keys [${agentosTomlVersionKeys.join(', ')}] to ${workspaceVersion}`
    )

    const factoryTomlPath = join(__dirname, '..', factoryTomlRelativePath)
    writeFileSync(
      factoryTomlPath,
      updateTomlVersions(readFileSync(factoryTomlPath, 'utf-8'), factoryTomlVersionKeys, workspaceVersion),
      'utf-8'
    )
    console.log(
      `Updated factory/gradle/libs.versions.toml keys [${factoryTomlVersionKeys.join(', ')}] to ${workspaceVersion}`
    )

    // Explicitly stage both toml files so they're included in the release commit.
    // Nx's releaseChangelog only stages files it knows about (package.json, CHANGELOG.md),
    // so we must stage our additional files manually.
    execSync(`git add ${agentosTomlRelativePath} ${factoryTomlRelativePath}`, { stdio: 'inherit' })
  }

  // Step 3: Generate changelog, commit all staged changes (including toml), tag, and push.
  // releaseChangelog is NEVER shared with the workstream path — it commits, tags, and pushes,
  // all of which are wrong on an integration branch.
  await releaseChangelog({
    dryRun,
    releaseGraph,
    verbose: false,
    version: workspaceVersion,
    versionData: projectsVersionData,
  })

  // Step 4: Publish packages — JVM projects are skipped via no-op nx-release-publish targets
  // (published via Gradle in CI). Publishing to the 'latest' dist-tag is the standard
  // master release behaviour; workstream builds use 'workstream-<slug>' instead.
  await publishPackages(releaseGraph, projectsVersionData, 'latest', dryRun)
}

try {
  await main()
} catch (err) {
  console.error('Release failed:', err)
  process.exit(1)
}
