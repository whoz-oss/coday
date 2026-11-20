import { existsSync } from 'node:fs'
import { spawnSync } from 'node:child_process'

// Always use the exact target root supplied by Factory; never discover an ancestor.
const root = process.cwd()
const env = { ...process.env, NX_DAEMON: 'false', NX_TUI: 'false' }
function run(command, args, capture = false) {
  const result = spawnSync(command, args, {
    cwd: root,
    env,
    encoding: 'utf8',
    stdio: capture ? 'pipe' : 'inherit',
    timeout: 1_800_000,
  })
  if (result.error || result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')} failed (${result.status}): ${result.error?.message ?? result.stderr ?? ''}`)
  }
  return result.stdout?.trim() ?? ''
}
function requirePath(path) {
  if (!existsSync(path)) throw new Error(`Missing prerequisite in target repository: ${path}`)
}

try {
  for (const path of ['.git', 'nx.json', 'pnpm-lock.yaml', 'node_modules/.bin/nx', 'agentos/gradlew', 'factory/gradlew']) {
    requirePath(path)
  }
  if (Number(process.versions.node.split('.')[0]) < 24) throw new Error('Node >=24 is required (CI toolchain).')
  if (!run('pnpm', ['--version'], true).startsWith('10.')) throw new Error('pnpm 10 is required (CI toolchain).')
  // Match the Gradle wrapper Java selection without downloading/installing a toolchain.
  const java = process.env.JAVA_HOME ? `${process.env.JAVA_HOME}/bin/java` : 'java'
  const version = spawnSync(java, ['-version'], { encoding: 'utf8', timeout: 10_000 })
  if (version.error || version.status !== 0 || !/version "25(?:[.\"])/.test(`${version.stdout}\n${version.stderr}`)) {
    throw new Error('JDK 25 is required; configure JAVA_HOME/PATH before starting the run.')
  }
  // Resolve effective targets through the same Nx plugins as CI, and reject empty plans.
  for (const target of ['build', 'lint', 'test']) {
    const projects = JSON.parse(run('pnpm', ['nx', 'show', 'projects', `--with-target=${target}`, '--json'], true))
    if (!Array.isArray(projects) || projects.length === 0) throw new Error(`No Nx projects expose '${target}'.`)
  }
  // Deliberately verify the whole monorepo: no guessed base ref or model-supplied affected scope.
  // JVM build/test targets are inferred by tools/plugins/gradle-plugins, as in validate.yml.
  // This is costly, not an affected optimization; the manifest budget may need operational tuning.
  run('pnpm', ['nx', 'run-many', '--targets=build,lint,test,check-openapi-spec', '--parallel=2', '--skip-nx-cache'])
} catch (error) {
  console.error(`Factory build: ${error.message}`)
  process.exitCode = 1
}
