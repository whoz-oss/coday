/**
 * Port selection of a stack. Leaf module (no import of version.ts) so it is unit-testable.
 *
 * The scheme itself (bases, offsets) lives in `port-scheme.ts` / `port-scheme.json`. Here: which block a stack runs on,
 * and the rule is the same for EVERY profile (prod, local, dev): the stack uses exactly one block, the first of its
 * profile (or the one of `CODAY_PORT_BASE`), with `--port` / `PORT` for Express and `CODAY_*_PORT` for a service as
 * explicit overrides. When a port the stack will use is taken, the start FAILS with a message naming the process holding
 * it: Coday never moves to another block nor to a port the OS hands out.
 *
 * Why: the ports must be the same on every start and for every user, because the redirect URIs registered at OAuth
 * providers (allowlists) are tied to them. A taken port is an issue the user must solve, not something to work around.
 */

import { describePortInUse, findPortOwner, isPortFree } from '../find-available-port'
import { parsePortBase, PORT_BASES, PortProfile, portProfile, portsFor } from './port-scheme'

export interface PickedPorts {
  express: number
  agentos: number
  agentosBolt: number
  factory: number
}

/** Preferred ports overriding the ones of the block (`CODAY_AGENTOS_PORT`...): an explicit choice of the user. */
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
  /** `CODAY_PORT_BASE` set by the user: the block to use instead of the first one of the profile. */
  explicitBase?: number
  /** `--port` / `PORT` set by the user: Express on that port. */
  explicitExpress?: number
  preferred?: PreferredPorts
  /** Services this process really runs (an external AgentOS / Factory needs no port). Default: both. */
  managed?: { agentos: boolean; factory: boolean }
  isFree?: (port: number) => Promise<boolean>
  /** Who holds a port, for the messages (best effort). */
  findOwner?: (port: number) => Promise<string | undefined>
  platform?: NodeJS.Platform
}

export interface StackChoice {
  /** The block the stack runs on. */
  base: number
  ports: PickedPorts
}

/**
 * The request of the running process: profile and explicit choices from the environment (`BUILD_ENV`,
 * `CODAY_PORT_PROFILE`, `CODAY_PORT_BASE`, `PORT`, `CODAY_*_PORT`) and the `--port` option.
 */
export function stackRequestFromEnv(
  env: NodeJS.ProcessEnv,
  extras: { port?: number; managed?: StackRequest['managed'] } = {}
): StackRequest {
  return {
    profile: portProfile(env),
    explicitBase: parsePortBase(env['CODAY_PORT_BASE']),
    explicitExpress: parsePort(extras.port) ?? parsePort(env['PORT']),
    preferred: parsePreferredPorts(env),
    managed: extras.managed,
  }
}

/**
 * Choose the ports of a stack: Express and the managed services, all in one block. Throws (message built by
 * `describePortInUse`) when a port the stack will use is taken, or when two services would share a port.
 */
export async function chooseStackPorts(request: StackRequest): Promise<StackChoice> {
  const {
    profile,
    explicitBase,
    explicitExpress,
    preferred = {},
    managed = { agentos: true, factory: true },
    isFree = isPortFree,
    findOwner = findPortOwner,
    platform = process.platform,
  } = request

  const base = explicitBase ?? PORT_BASES[profile]
  const block = portsFor(base)
  const ports: PickedPorts = {
    express: explicitExpress ?? block.server,
    agentos: preferred.agentos ?? block.agentos,
    agentosBolt: preferred.agentosBolt ?? block.bolt,
    factory: preferred.factory ?? block.factory,
  }

  // Only the ports of what this process runs count: an external AgentOS / Factory needs none.
  const used = [
    ports.express,
    ...(managed.agentos ? [ports.agentos, ports.agentosBolt] : []),
    ...(managed.factory ? [ports.factory] : []),
  ]
  const twice = used.find((port, i) => used.indexOf(port) !== i)
  if (twice !== undefined) {
    throw new Error(
      `Port ${twice} would be used by two services of the same stack (block ${base}): change PORT, CODAY_PORT_BASE or the CODAY_*_PORT variables.`
    )
  }
  for (const port of used) {
    if (!(await isFree(port))) throw new Error(await describePortInUse(port, findOwner, platform))
  }
  return { base, ports }
}
