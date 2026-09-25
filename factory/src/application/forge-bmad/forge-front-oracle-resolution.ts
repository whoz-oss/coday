/**
 * Application service for the front-domain oracle resolution.
 *
 * Owner-project discovery, build-host mapping and effective Nx inspection are
 * application orchestration over the filesystem and the pnpm/Nx CLI; the pure
 * hashing lives inline. This is the trusted front-oracle resolution used by the
 * Story oracle campaign.
 */

import { existsSync, readFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { dirname, isAbsolute, join, relative, resolve } from 'node:path'
import type { CodedError } from '../../domain/forge-bmad/forge-spec.js'

/** Schema version of the front-oracle resolution map. */
export const FRONT_ORACLE_MAP_SCHEMA_VERSION = 1
const INSPECT_TIMEOUT_MS = 10_000
const INSPECT_MAX_BUFFER = 1024 * 1024

const fail: (code: string, message?: string) => never = (code, message = code) => {
  const error = new Error(message) as CodedError
  error.code = code
  throw error
}

const hash = (value: unknown): string => `sha256:${createHash('sha256').update(JSON.stringify(value)).digest('hex')}`

const validName = (name: unknown): boolean => typeof name === 'string' && /^[A-Za-z0-9._-]+$/.test(name)

const readProject = (path: string, label: string): any => {
  try {
    const config = JSON.parse(readFileSync(path, 'utf8'))
    if (!validName(config.name)) fail('ORACLE_INFRASTRUCTURE', `${label} has an absent or invalid Nx project name.`)
    return config
  } catch (error) {
    if ((error as CodedError).code === 'ORACLE_INFRASTRUCTURE') throw error
    fail('ORACLE_INFRASTRUCTURE', `Cannot read ${label}.`)
  }
}

const hostProject = (root: string, name: string): any => {
  for (const path of [
    join(root, 'apps', name, 'project.json'),
    join(root, 'frontend', 'apps', name, 'project.json'),
    join(root, name, 'project.json'),
  ])
    if (existsSync(path)) return readProject(path, `Build host project.json for ${name}`)
  return null
}

/** Resolve owner names together with the exact project.json encountered for each file. */
export function resolveOwnerProjectConfigs(
  files: readonly string[],
  repoRoot: string
): Array<{ name: string; projectPath: string; config: any }> {
  const root = resolve(repoRoot)
  const byName = new Map<string, { name: string; projectPath: string; config: any }>()
  for (const file of files) {
    if (typeof file !== 'string' || !file || isAbsolute(file))
      fail('ORACLE_INFRASTRUCTURE', `Invalid StoryEdit file path: ${String(file)}.`)
    const absolute = resolve(root, file)
    if (relative(root, absolute).startsWith('..'))
      fail('ORACLE_INFRASTRUCTURE', `StoryEdit file escapes repository root: ${file}.`)
    let dir = dirname(absolute)
    let found = false
    while (dir === root || dir.startsWith(`${root}/`)) {
      const projectPath = join(dir, 'project.json')
      if (existsSync(projectPath)) {
        const config = readProject(projectPath, `Owner project.json for ${file}`)
        const previous = byName.get(config.name)
        if (previous && previous.projectPath !== projectPath)
          fail('ORACLE_INFRASTRUCTURE', `Nx owner ${config.name} resolves to multiple project.json files.`)
        if (!previous) byName.set(config.name, { name: config.name, projectPath, config })
        found = true
        break
      }
      const parent = dirname(dir)
      if (parent === dir) break
      dir = parent
    }
    if (!found) continue
  }
  return [...byName.values()]
}

/** Reads one project's effective Nx configuration without loading workspace-wide project metadata. */
export function inspectNxProject(name: string, repoRoot: string): any {
  if (!validName(name)) fail('ORACLE_INFRASTRUCTURE', `Invalid Nx project name for inspection: ${String(name)}.`)
  let output: string
  try {
    output = execFileSync('pnpm', ['nx', 'show', 'project', name, '--json'], {
      cwd: repoRoot,
      encoding: 'utf8',
      timeout: INSPECT_TIMEOUT_MS,
      maxBuffer: INSPECT_MAX_BUFFER,
      stdio: ['ignore', 'pipe', 'pipe'],
    })
  } catch {
    fail('ORACLE_INFRASTRUCTURE', `Cannot inspect effective Nx configuration for ${name}.`)
  }
  let config: any
  try {
    config = JSON.parse(output!)
  } catch {
    fail('ORACLE_INFRASTRUCTURE', `Effective Nx configuration for ${name} is not valid JSON.`)
  }
  if (config?.name !== name || !config.targets || typeof config.targets !== 'object' || Array.isArray(config.targets))
    fail('ORACLE_INFRASTRUCTURE', `Effective Nx configuration for ${name} is invalid or mismatched.`)
  return config
}

const inspectEffectiveProject = (
  name: string,
  repoRoot: string,
  projectInspector: (name: string, repoRoot: string) => any
): any => {
  let config: any
  try {
    config = projectInspector(name, repoRoot)
  } catch (error) {
    if ((error as CodedError)?.code === 'ORACLE_INFRASTRUCTURE') throw error
    fail('ORACLE_INFRASTRUCTURE', `Cannot inspect effective Nx configuration for ${name}.`)
  }
  if (
    !config ||
    config.name !== name ||
    !config.targets ||
    typeof config.targets !== 'object' ||
    Array.isArray(config.targets)
  )
    fail('ORACLE_INFRASTRUCTURE', `Effective Nx configuration for ${name} is invalid or mismatched.`)
  return config
}

/** Parse and validate the `FACTORY_FRONT_BUILD_HOST_MAP` JSON environment variable. */
export function parseFrontBuildHostMap(raw: unknown): Record<string, string[]> {
  if (typeof raw !== 'string' || !raw) fail('ORACLE_INFRASTRUCTURE', 'FACTORY_FRONT_BUILD_HOST_MAP is required.')
  let map: any
  try {
    map = JSON.parse(raw)
  } catch {
    fail('ORACLE_INFRASTRUCTURE', 'FACTORY_FRONT_BUILD_HOST_MAP must be valid JSON.')
  }
  if (!map || typeof map !== 'object' || Array.isArray(map))
    fail('ORACLE_INFRASTRUCTURE', 'Host map must be an object.')
  for (const [owner, hosts] of Object.entries(map)) {
    if (
      (owner !== '*' && !validName(owner)) ||
      !Array.isArray(hosts) ||
      hosts.length === 0 ||
      hosts.some((host) => !validName(host))
    )
      fail('ORACLE_INFRASTRUCTURE', 'Host map contains an invalid owner or host.')
  }
  return Object.fromEntries(
    Object.entries(map).map(([owner, hosts]) => [owner, [...new Set(hosts as string[])].sort()])
  )
}

/** Resolve the front build/tests command plan for a StoryEdit file set. */
export function resolveFrontOraclePlan({
  repoRoot,
  files,
  hostMapRaw,
  buildTemplate,
  testsTarget = process.env.FACTORY_FRONT_TEST_TARGET ?? 'frontend-test',
  requireBuild = true,
  projectInspector = inspectNxProject,
}: {
  repoRoot: string
  files: readonly string[]
  hostMapRaw?: unknown
  buildTemplate: string
  testsTarget?: string
  requireBuild?: boolean
  projectInspector?: (name: string, repoRoot: string) => any
}): Record<string, any> {
  const ownerProjects = resolveOwnerProjectConfigs(files, repoRoot)
  const owners = ownerProjects.map((owner) => owner.name)
  if (!owners.length) fail('ORACLE_INFRASTRUCTURE', 'No Nx owner project found for StoryEdit files.')
  const inspected = new Map<string, any>()
  const inspect = (name: string): any => {
    if (!inspected.has(name)) inspected.set(name, inspectEffectiveProject(name, repoRoot, projectInspector))
    return inspected.get(name)
  }
  const map = requireBuild ? parseFrontBuildHostMap(hostMapRaw) : null
  const hosts: string[] = []
  const ownersWithTestTarget: string[] = []
  const ownersWithoutTestTarget: string[] = []
  for (const owner of ownerProjects) {
    if (requireBuild) {
      const mapped = map![owner.name] ?? map!['*']
      if (!mapped) fail('ORACLE_INFRASTRUCTURE', `No build host mapping for owner ${owner.name}.`)
      for (const host of mapped) {
        if (!hostProject(repoRoot, host)) fail('ORACLE_INFRASTRUCTURE', `Build host ${host} does not exist.`)
        if (!inspect(host).targets.build && !inspect(host).targets['build-angular'])
          fail('ORACLE_INFRASTRUCTURE', `Build host ${host} has no build target.`)
        if (!hosts.includes(host)) hosts.push(host)
      }
    }
    ;(inspect(owner.name).targets[testsTarget] ? ownersWithTestTarget : ownersWithoutTestTarget).push(owner.name)
  }
  const buildHosts = [...hosts].sort()
  const build = {
    command: requireBuild ? `${buildTemplate} --projects=${buildHosts.join(',')}` : null,
    cwd: repoRoot,
    owners,
    buildHosts,
    target: 'build',
    configuration: 'development',
  }
  const tests = {
    command: ownersWithTestTarget.length
      ? `pnpm nx run-many --target=${testsTarget} --projects=${ownersWithTestTarget.join(',')} --skip-nx-cache`
      : null,
    cwd: repoRoot,
    owners: ownersWithTestTarget,
    ownersWithTestTarget,
    ownersWithoutTestTarget,
    buildHosts: [],
    target: testsTarget,
    configuration: null,
  }
  return {
    schemaVersion: FRONT_ORACLE_MAP_SCHEMA_VERSION,
    owners,
    ownersWithTestTarget,
    ownersWithoutTestTarget,
    build,
    tests,
    commandHash: hash({ build, tests }),
  }
}
