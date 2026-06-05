import { CreateNodesContextV2, CreateNodesResult, CreateNodesV2, createNodesFromFiles } from '@nx/devkit'
import { existsSync } from 'fs'
import { basename, dirname } from 'path'

const GRADLE_INPUTS = [
  'default',
  '{projectRoot}/build.gradle.kts',
  '{workspaceRoot}/agentos/gradle.properties',
  '{workspaceRoot}/agentos/settings.gradle.kts',
]

// For build/test targets: depend on agentos-sdk:build (compile + test) to ensure
// the SDK is fully verified before downstream compilation or testing.
const SDK_BUILD_DEPENDENCY = { target: 'build', projects: ['agentos-sdk'] }

// For assemble/publish targets: depend on agentos-sdk:assemble only.
// SDK tests already ran in validate.yml on every PR — no need to re-run them at publish time.
const SDK_ASSEMBLE_DEPENDENCY = { target: 'assemble', projects: ['agentos-sdk'] }

// Matches all build.gradle.kts files one level deep under agentos/
// e.g. agentos/agentos-service/build.gradle.kts
// Excludes the composite root agentos/build.gradle.kts (no parent subdir)
export const createNodesV2: CreateNodesV2 = [
  'agentos/*/build.gradle.kts',
  async (configFiles, _options, context: CreateNodesContextV2) => {
    return await createNodesFromFiles(
      (configFile) => createNodesInternal(configFile, context),
      configFiles,
      _options,
      context
    )
  },
]

function createNodesInternal(configFilePath: string, context: CreateNodesContextV2): CreateNodesResult {
  const projectDir = dirname(configFilePath)
  const projectName = basename(projectDir)

  // Guard: only infer for projects that already have a project.json
  // (i.e. are already registered Nx projects)
  if (!existsSync(`${context.workspaceRoot}/${projectDir}/project.json`)) {
    return {}
  }

  return buildProjectConfig(projectDir, projectName)
}

export function buildProjectConfig(projectDir: string, projectName: string): CreateNodesResult {
  return {
    projects: {
      [projectDir]: {
        release: {
          version: {
            manifestRootsToUpdate: [],
          },
        },
        targets: {
          build: {
            executor: 'nx:run-commands',
            cache: true,
            inputs: GRADLE_INPUTS,
            outputs: ['{projectRoot}/build'],
            options: {
              command: `./gradlew :${projectName}:build`,
              cwd: 'agentos',
            },
            dependsOn: [SDK_BUILD_DEPENDENCY],
          },
          assemble: {
            executor: 'nx:run-commands',
            cache: true,
            inputs: GRADLE_INPUTS,
            outputs: ['{projectRoot}/build'],
            options: {
              command: `./gradlew :${projectName}:assemble`,
              cwd: 'agentos',
            },
            dependsOn: [SDK_ASSEMBLE_DEPENDENCY],
          },
          test: {
            executor: 'nx:run-commands',
            cache: true,
            inputs: GRADLE_INPUTS,
            outputs: ['{projectRoot}/build/test-results', '{projectRoot}/build/reports'],
            options: {
              command: `./gradlew :${projectName}:test`,
              cwd: 'agentos',
              passWithNoTests: null,
            },
            dependsOn: ['build', SDK_BUILD_DEPENDENCY],
          },
          clean: {
            executor: 'nx:run-commands',
            cache: false,
            options: {
              command: `./gradlew :${projectName}:clean`,
              cwd: 'agentos',
            },
          },
          'nx-release-publish': {
            executor: 'nx:run-commands',
            // No-op: real publishing happens via the `publish` target below, called by publish-agentos-artifacts in validate.yml CI workflow.
            dependsOn: [],
            options: {
              command: `echo '${projectName} published via Gradle in CI'`,
            },
          },
          publish: {
            executor: 'nx:run-commands',
            cache: false,
            options: {
              command: `./gradlew :${projectName}:publish`,
              cwd: 'agentos',
            },
            // `assemble` (no tests) is sufficient — tests already ran in validate.yml on every PR.
            // Gradle's own `publish` task already depends on `assemble` internally.
            dependsOn: ['assemble'],
          },
        },
      },
    },
  }
}
