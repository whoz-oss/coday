/**
 * Port selection of a stack. Leaf module (no import of version.ts) so it is unit-testable.
 *
 * The scheme itself (bases, offsets, candidate blocks) lives in `port-scheme.ts` / `port-scheme.json`. Here: which block a
 * stack runs on. A packaged stack (prod, local) checks every port it will use in a block (Express, and the managed
 * AgentOS, its Neo4j Bolt and the Factory); when one is taken, the WHOLE stack moves to the next candidate block of its
 * profile (prod 3000 -> 3020 -> 3040..., local 3010 -> 3030 -> 3050...). Only an explicit user choice is strict
 * (`--port` / `PORT` for Express, `CODAY_PORT_BASE` for the block, `CODAY_*_PORT` for a service), and so is the dev
 * profile, whose Angular / Nx configs are static and cannot follow a move. A port the OS hands out is the last resort,
 * when every block is taken.
 */

import { describePortInUse, findEphemeralPort, findPortOwner, isPortFree } from '../find-available-port'
import { candidateBases, parsePortBase, PortProfile, portProfile, portsFor } from './port-scheme'

export interface PickedPorts {
  express: number
  agentos: number
  agentosBolt: number
  factory: number
}

/** Preferred ports overriding the ones of the block (`CODAY_AGENTOS_PORT`...): an explicit choice, so strict. */
export type PreferredPorts = Partial<Omit<PickedPorts, 'express'>>

export const parsePort = (value: string | number | undefined): number | undefined => {
  const port = Number(value)
  return value !== undefined && value !== '' && Number.isInteger(port) && port > 0 && port < 65536 ? port : undefined
}

/**
 * `CODAY_AGENTOS_PORT`, `CODAY_AGENTOS_BOLT_PORT`, `CODAY_FACTORY_PORT`: where each service runs instead of its port of
 * the block. Invalid values are ignored. Express has its own `PORT`.
 */
export function parsePreferredPorts(env: NodeJS.ProcessEnv = process.env): PreferredPorts {
  const agentos = parsePort(env['CODAY_AGENTOS_PORT'])
  const agentosBolt = parsePort(env['CODAY_AGENTOS_BOLT_PORT'])
  const factory = parsePort(env['CODAY_FACTORY_PORT'])
  return {
    ...(agentos ? { agentos } : {}),
    ...(agentosBolt ? { agentosBolt } : {}),
    ...(factory ? { factory } : {}),
  }
}

export interface StackRequest {
  profile: PortProfile
  /** `CODAY_PORT_BASE` set by the user: strict on that block. */
  explicitBase?: number
  /** `--port` / `PORT` set by the user: Express strict on that port. */
  explicitExpress?: number
  /** Block of the last start (state.json): tried first when the profile allows it. */
  remembered?: number
  preferred?: PreferredPorts
  /** Services this process really runs (an external AgentOS / Factory needs no port). Default: both. */
  managed?: { agentos: boolean; factory: boolean }
  isFree?: (port: number) => Promise<boolean>
  /** Last resort when every block is taken. */
  ephemeral?: () => Promise<number>
  /** Who holds a port, for the messages (best effort). */
  findOwner?: (port: number) => Promise<string | undefined>
  platform?: NodeJS.Platform
  log?: (message: string) => void
}

export interface StackChoice {
  /** The block the stack runs on; undefined when the ports come from the OS. */
  base?: number
  ports: PickedPorts
}

/**
 * The request of the running process: profile and strictness from the environment (`BUILD_ENV`, `CODAY_PORT_PROFILE`,
 * `CODAY_PORT_BASE`, `PORT`, `CODAY_*_PORT`) and the `--port` option.
 */
export function stackRequestFromEnv(
  env: NodeJS.ProcessEnv,
  extras: { port?: number; remembered?: number; managed?: StackRequest['managed'] } = {}
): StackRequest {
  return {
    profile: portProfile(env),
    explicitBase: parsePortBase(env['CODAY_PORT_BASE']),
    explicitExpress: parsePort(extras.port) ?? parsePort(env['PORT']),
    remembered: extras.remembered,
    preferred: parsePreferredPorts(env),
    managed: extras.managed,
  }
}

type Problem = { kind: 'busy' | 'duplicate'; port: number }

/**
 * Choose the ports of a stack: Express and the managed services, all in one block. Throws (message built by
 * `describePortInUse`) when an explicit choice is taken.
 */
export async function chooseStackPorts(request: StackRequest): Promise<StackChoice> {
  const {
    profile,
    explicitBase,
    explicitExpress,
    remembered,
    preferred = {},
    managed = { agentos: true, factory: true },
    isFree = isPortFree,
    ephemeral = findEphemeralPort,
    findOwner = findPortOwner,
    platform = process.platform,
    log = () => undefined,
  } = request

  const failBusy = async (port: number): Promise<never> => {
    throw new Error(await describePortInUse(port, findOwner, platform))
  }

  // Explicit choices first: they do not depend on the block, and a taken one is an error, never a fallback.
  const explicit = new Set<number>()
  if (explicitExpress !== undefined) {
    if (!(await isFree(explicitExpress))) await failBusy(explicitExpress)
    explicit.add(explicitExpress)
  }
  const preferredPorts: Array<number | undefined> = [
    ...(managed.agentos ? [preferred.agentos, preferred.agentosBolt] : []),
    ...(managed.factory ? [preferred.factory] : []),
  ]
  for (const port of preferredPorts) {
    if (port === undefined) continue
    if (!(await isFree(port))) await failBusy(port)
    explicit.add(port)
  }

  const portsOf = (base: number): PickedPorts => {
    const block = portsFor(base)
    return {
      express: explicitExpress ?? block.server,
      agentos: preferred.agentos ?? block.agentos,
      agentosBolt: preferred.agentosBolt ?? block.bolt,
      factory: preferred.factory ?? block.factory,
    }
  }
  const usedPorts = (ports: PickedPorts): number[] => [
    ports.express,
    ...(managed.agentos ? [ports.agentos, ports.agentosBolt] : []),
    ...(managed.factory ? [ports.factory] : []),
  ]
  const problemOf = async (ports: PickedPorts): Promise<Problem | undefined> => {
    const used = usedPorts(ports)
    const twice = used.find((port, i) => used.indexOf(port) !== i)
    if (twice !== undefined) return { kind: 'duplicate', port: twice }
    for (const port of used) {
      if (explicit.has(port)) continue
      if (!(await isFree(port))) return { kind: 'busy', port }
    }
    return undefined
  }

  const strict = explicitBase !== undefined || profile === 'dev'
  const profileCandidates = candidateBases(profile)
  const first = explicitBase ?? profileCandidates[0]!
  const candidates = strict
    ? [first]
    : [
        ...(remembered !== undefined && profileCandidates.includes(remembered) ? [remembered] : []),
        ...profileCandidates,
      ].filter((base, i, all) => all.indexOf(base) === i)

  const problems = new Map<number, Problem>()
  for (const base of candidates) {
    const ports = portsOf(base)
    const problem = await problemOf(ports)
    if (problem) {
      problems.set(base, problem)
      continue
    }
    if (base !== first) {
      const firstProblem = problems.get(first)
      const url = `http://localhost:${ports.express}/`
      if (firstProblem?.kind === 'busy') {
        const owner = await findOwner(firstProblem.port).catch(() => undefined)
        log(
          `Port ${firstProblem.port} is in use${owner ? ` (by ${owner})` : ''}, Coday starts on block ${base}: ${url}`
        )
      } else if (firstProblem) {
        log(`Port ${firstProblem.port} is used twice in block ${first}, Coday starts on block ${base}: ${url}`)
      } else {
        log(`Coday starts on block ${base}, the one of its last start: ${url}`)
      }
    }
    return { base, ports }
  }

  const blocked = problems.get(first)
  if (strict) {
    if (blocked?.kind === 'busy') return failBusy(blocked.port)
    throw new Error(
      `Port ${blocked?.port} would be used by two services of the same stack (block ${first}): change PORT, CODAY_PORT_BASE or the CODAY_*_PORT variables.`
    )
  }

  // Every block of the profile is taken: a port the OS hands out per service, the unmanaged ones keep their block port.
  const fallback = portsOf(first)
  const assigned = new Set<number>(explicit)
  const fromOs = async (): Promise<number> => {
    let port = await ephemeral()
    while (assigned.has(port)) port = await ephemeral()
    assigned.add(port)
    return port
  }
  const ports: PickedPorts = {
    express: explicitExpress ?? (await fromOs()),
    agentos: !managed.agentos || preferred.agentos ? fallback.agentos : await fromOs(),
    agentosBolt: !managed.agentos || preferred.agentosBolt ? fallback.agentosBolt : await fromOs(),
    factory: !managed.factory || preferred.factory ? fallback.factory : await fromOs(),
  }
  log(
    `Every ${profile} block (${candidates[0]}...${candidates[candidates.length - 1]}) is taken: Coday uses ports assigned by the OS, http://localhost:${ports.express}/`
  )
  return { ports }
}
