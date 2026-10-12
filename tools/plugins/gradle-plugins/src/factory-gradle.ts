import { CreateNodesContext, CreateNodesResult, CreateNodes, createNodesFromFiles } from '@nx/devkit'
import { existsSync } from 'fs'
import { basename, dirname } from 'path'

// ---------------------------------------------------------------------------
// Factory (factory/) Gradle plugin
//
// Sole purpose: set manifestRootsToUpdate: [] for every factory JVM project so
// that the global nx.json "manifestRootsToUpdate": ["apps/{projectName}"] is
// overridden. Factory projects have no package.json — they are published via
// Gradle, not npm. Also infers a no-op nx-release-publish target so nx release
// does not error when it tries to publish these projects.
// ---------------------------------------------------------------------------

// Matches all build.gradle.kts files one level deep under factory/
// e.g. factory/factory-sdk/build.gradle.kts
// Excludes the composite root factory/build.gradle.kts (no parent subdir)
export const createNodesV2: CreateNodes = [
  'factory/*/build.gradle.kts',
  async (configFiles, _options, context: CreateNodesContext) => {
    return await createNodesFromFiles(
      (configFile) => createFactoryNodesInternal(configFile, context),
      configFiles,
      _options,
      context
    )
  },
]

function createFactoryNodesInternal(configFilePath: string, context: CreateNodesContext): CreateNodesResult {
  const projectDir = dirname(configFilePath)
  const projectName = basename(projectDir)

  // Guard: only infer for projects that already have a project.json
  // (i.e. are already registered Nx projects)
  if (!existsSync(`${context.workspaceRoot}/${projectDir}/project.json`)) {
    return {}
  }

  return buildFactoryProjectConfig(projectDir, projectName)
}

export function buildFactoryProjectConfig(projectDir: string, projectName: string): CreateNodesResult {
  return {
    projects: {
      [projectDir]: {
        release: {
          version: {
            // Factory JVM projects have no package.json — suppress the global
            // manifestRootsToUpdate: ["apps/{projectName}"] from nx.json.
            manifestRootsToUpdate: [],
          },
        },
        targets: {
          'nx-release-publish': {
            executor: 'nx:run-commands',
            // No-op: real publishing happens via Gradle in the publish-factory-artifacts CI job.
            dependsOn: [],
            options: {
              command: `echo '${projectName} published via Gradle in CI'`,
            },
          },
        },
        // Note: build, test, clean, assemble targets are declared explicitly in each
        // factory project's project.json (no inference needed — factory deps vary per project).
      },
    },
  }
}
