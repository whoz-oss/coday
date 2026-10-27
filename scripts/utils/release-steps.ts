import { releasePublish, releaseVersion } from 'nx/release'
import { readFileSync, writeFileSync } from 'fs'
import { join } from 'path'
import { fileURLToPath } from 'url'
import { updateTomlVersions } from './update-toml-version'
import type { ReleaseGraph } from 'nx/dist/src/command-line/release/utils/release-graph'
import type { VersionData } from 'nx/dist/src/command-line/release/utils/shared'

const __dirname = fileURLToPath(new URL('.', import.meta.url))

const agentosTomlRelativePath = 'agentos/gradle/libs.versions.toml'
const agentosTomlVersionKeys = ['agentosSdk', 'agentosService']

const factoryTomlRelativePath = 'factory/gradle/libs.versions.toml'
const factoryTomlVersionKeys = ['factorySdk', 'factoryService']

/**
 * Stamps an exact version into every package.json in the release group, then updates
 * the Gradle version catalog to match. No git operations are performed — nx.json already
 * sets commit: false / tag: false, and passing an explicit specifier bypasses
 * conventional-commit resolution entirely.
 *
 * NOTE: The caller is responsible for git-staging the toml file when needed (master path
 * only — workstream builds never commit anything).
 */
export async function stampVersion(
  exactVersion: string,
  dryRun: boolean
): Promise<{
  workspaceVersion: string
  projectsVersionData: VersionData
  releaseGraph: ReleaseGraph
}> {
  const { projectsVersionData, releaseGraph, workspaceVersion } = await releaseVersion({
    dryRun,
    verbose: false,
    specifier: exactVersion,
  })

  if (workspaceVersion === undefined) {
    console.error('workspaceVersion is undefined — expected a single fixed release group')
    process.exit(1)
  }

  if (workspaceVersion === null) {
    // Should not happen when an exact specifier is provided, but handle defensively
    console.error('workspaceVersion is null — unexpected when an explicit specifier is given')
    process.exit(1)
  }

  if (dryRun) {
    agentosTomlVersionKeys.forEach((key) =>
      console.log(`[dry-run] Would update agentos/gradle/libs.versions.toml ${key} to ${workspaceVersion}`)
    )
    factoryTomlVersionKeys.forEach((key) =>
      console.log(`[dry-run] Would update factory/gradle/libs.versions.toml ${key} to ${workspaceVersion}`)
    )
  } else {
    const agentosTomlPath = join(__dirname, '..', '..', agentosTomlRelativePath)
    writeFileSync(
      agentosTomlPath,
      updateTomlVersions(readFileSync(agentosTomlPath, 'utf-8'), agentosTomlVersionKeys, workspaceVersion),
      'utf-8'
    )
    console.log(
      `Updated agentos/gradle/libs.versions.toml keys [${agentosTomlVersionKeys.join(', ')}] to ${workspaceVersion}`
    )

    const factoryTomlPath = join(__dirname, '..', '..', factoryTomlRelativePath)
    writeFileSync(
      factoryTomlPath,
      updateTomlVersions(readFileSync(factoryTomlPath, 'utf-8'), factoryTomlVersionKeys, workspaceVersion),
      'utf-8'
    )
    console.log(
      `Updated factory/gradle/libs.versions.toml keys [${factoryTomlVersionKeys.join(', ')}] to ${workspaceVersion}`
    )
  }

  return { workspaceVersion, projectsVersionData, releaseGraph }
}

/**
 * Publishes all packages in the release group under the given dist-tag.
 * JVM projects have a no-op nx-release-publish target and exit 0 without publishing;
 * they are published separately via Gradle.
 *
 * Exits with code 1 if any project's publish step fails.
 */
export async function publishPackages(
  releaseGraph: ReleaseGraph,
  projectsVersionData: VersionData,
  distTag: string,
  dryRun: boolean
): Promise<void> {
  const publishResults = await releasePublish({
    dryRun,
    releaseGraph,
    versionData: projectsVersionData,
    tag: distTag,
    verbose: false,
  })

  if (!Object.values(publishResults).every((result) => result.code === 0)) {
    console.error('One or more publish steps failed')
    process.exit(1)
  }
}
