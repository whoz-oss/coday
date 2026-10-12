import { CreateNodesContext, CreateNodesResult, CreateNodes, createNodesFromFiles } from '@nx/devkit'
import { existsSync } from 'fs'
import { basename, dirname } from 'path'

// ---------------------------------------------------------------------------
// Factory (factory/) Gradle plugin
//
// Mirrors agentos-gradle.ts: infers build, assemble, test, clean, publish and
// nx-release-publish targets for every factory JVM project that has a
// project.json. Project-specific targets (bootJar, bootRun, deploy-plugin,
// openapi) stay in each project's project.json.
// ---------------------------------------------------------------------------

const FACTORY_GRADLE_INPUTS = [
  'default',
  '{projectRoot}/build.gradle.kts',
  '{workspaceRoot}/factory/gradle/libs.versions.toml',
  '{workspaceRoot}/factory/settings.gradle.kts',
]

// For build/test targets: depend on factory-sdk:build to ensure the SDK is
// fully verified before downstream compilation or testing.
const SDK_BUILD_DEPENDENCY = { target: 'build', projects: ['factory-sdk'] }

// For assemble/publish targets: depend on factory-sdk:assemble only.
// SDK tests already ran in validate.yml on every PR.
const SDK_ASSEMBLE_DEPENDENCY = { target: 'assemble', projects: ['factory-sdk'] }

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
  // factory-sdk is the root dependency — it has no SDK dependency of its own.
  const isSdk = projectName === 'factory-sdk'
  const buildDependsOn = isSdk ? [] : [SDK_BUILD_DEPENDENCY]
  const assembleDependsOn = isSdk ? [] : [SDK_ASSEMBLE_DEPENDENCY]

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
          build: {
            executor: 'nx:run-commands',
            cache: true,
            inputs: FACTORY_GRADLE_INPUTS,
            outputs: ['{projectRoot}/build'],
            options: {
              command: `./gradlew :${projectName}:build`,
              cwd: 'factory',
            },
            dependsOn: buildDependsOn,
          },
          assemble: {
            executor: 'nx:run-commands',
            cache: true,
            inputs: FACTORY_GRADLE_INPUTS,
            outputs: ['{projectRoot}/build'],
            options: {
              command: `./gradlew :${projectName}:assemble`,
              cwd: 'factory',
            },
            dependsOn: assembleDependsOn,
          },
          test: {
            executor: 'nx:run-commands',
            cache: false,
            inputs: FACTORY_GRADLE_INPUTS,
            outputs: ['{projectRoot}/build/test-results', '{projectRoot}/build/reports'],
            options: {
              command: `./gradlew :${projectName}:test`,
              cwd: 'factory',
            },
            dependsOn: isSdk ? ['build'] : ['build', SDK_BUILD_DEPENDENCY],
          },
          clean: {
            executor: 'nx:run-commands',
            cache: false,
            options: {
              command: `./gradlew :${projectName}:clean`,
              cwd: 'factory',
            },
          },
          'nx-release-publish': {
            executor: 'nx:run-commands',
            // No-op: real publishing happens via the `publish` target below.
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
              cwd: 'factory',
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
