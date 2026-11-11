/**
 * THE port scheme of Coday: a BASE per profile and the same OFFSETS for every profile. The numbers live in ONE data
 * file, `port-scheme.json` (block size, offsets, base and step of each profile); this module only derives from it.
 *
 * Roles inside a block (offsets in the JSON):
 *   Server (Express)   the ONLY address a user opens, in every profile
 *   Client, Cockpit    prod/local: served BY Express (`/` and `/factory/`), the ports stay RESERVED, never used by
 *                      another role. dev: the Angular dev servers listen there, INTERNAL, reached through Express
 *   AgentOS, Factory, AgentOS embedded Neo4j (Bolt)   internal everywhere
 *
 * Profiles: prod (the published package, `coday-web`, desktop apps), local (`pnpm nx run web:local`), dev (`pnpm
 * web:serve:dev`, `pnpm client`, `pnpm server`, scripts/web-dev-tmux.sh, bootRun). 5000 and 7000 are avoided on
 * purpose: macOS AirPlay Receiver listens there.
 *
 * A stack owns a whole block [base, base + blockSize - 1]: the first block of its profile (or the one of
 * `CODAY_PORT_BASE`), always the same, because the redirect URIs registered at OAuth providers are tied to the ports.
 * A taken port makes the start fail (see ports.ts), the stack never moves to another block. The other candidate
 * blocks (`candidateBases`) are the ones a user may pick explicitly or that parallel dev stacks use; prod and local
 * interleave inside 3xxx so they never collide, dev has 4xxx for itself.
 *
 * Leaf module (imports only the JSON): used by the server runtime and the launcher scripts. Whatever cannot import
 * it (Nx project.json files, .idea run configurations) repeats the DEV values and is guarded by
 * port-scheme-consistency.spec.ts.
 */

import scheme from './port-scheme.json'

export const PORT_OFFSETS = scheme.offsets

export type PortRole = keyof typeof PORT_OFFSETS

export type PortProfile = keyof typeof scheme.profiles

export const PORT_BASES: Record<PortProfile, number> = {
  prod: scheme.profiles.prod.base,
  local: scheme.profiles.local.base,
  dev: scheme.profiles.dev.base,
}

/** Width of the block a stack owns: [base, base + PORT_BLOCK_SIZE - 1]. */
export const PORT_BLOCK_SIZE: number = scheme.blockSize

export type StackPorts = Record<PortRole, number>

/** The ports of every role for a block base. */
export function portsFor(base: number): StackPorts {
  return {
    server: base + PORT_OFFSETS.server,
    client: base + PORT_OFFSETS.client,
    factoryCockpit: base + PORT_OFFSETS.factoryCockpit,
    agentos: base + PORT_OFFSETS.agentos,
    factory: base + PORT_OFFSETS.factory,
    bolt: base + PORT_OFFSETS.bolt,
  }
}

/** `count` block bases `step` apart (one block of ten by default): 4000, 4010, 4020... */
export function blockBases(base: number, count: number, step: number = PORT_BLOCK_SIZE): number[] {
  return Array.from({ length: Math.max(0, count) }, (_, i) => base + i * step)
}

/**
 * The candidate blocks of a profile: its own base first, then one `step` further, up to the end of the thousand it
 * lives in (a block never straddles two thousands). prod 3000, 3020, ... 3980 and local 3010, 3030, ... 3990
 * interleave, so two stacks of different profiles can never pick the same block. A stack does NOT walk this list on a
 * taken port; it is the set of blocks that can be chosen explicitly (`CODAY_PORT_BASE`) without overlapping. `count`
 * caps the list.
 */
export function candidateBases(profile: PortProfile, count = Number.POSITIVE_INFINITY): number[] {
  const { base, step } = scheme.profiles[profile]
  const lastBase = Math.floor(base / 1000) * 1000 + 1000 - PORT_BLOCK_SIZE
  const available = Math.floor((lastBase - base) / step) + 1
  return blockBases(base, Math.min(count, available), step)
}

const isValidBase = (base: number): boolean =>
  Number.isInteger(base) && base >= 1024 && base + PORT_BLOCK_SIZE - 1 <= 65535

/** `CODAY_PORT_BASE`: a valid block base, undefined otherwise. */
export function parsePortBase(value: string | undefined): number | undefined {
  if (!value) return undefined
  const base = Number(value)
  return isValidBase(base) ? base : undefined
}

/**
 * Profile of a server process: `CODAY_PORT_PROFILE` when it names one (web:local says `local`, which a server cannot
 * derive), else dev with BUILD_ENV=development, prod otherwise. The profile only picks the default block.
 */
export function portProfile(env: NodeJS.ProcessEnv = process.env): PortProfile {
  const named = env['CODAY_PORT_PROFILE']
  if (named === 'prod' || named === 'local' || named === 'dev') return named
  return env['BUILD_ENV'] === 'development' ? 'dev' : 'prod'
}

/** Block base of the running process: `CODAY_PORT_BASE` when valid, else the first block of its profile. */
export function profileBase(env: NodeJS.ProcessEnv = process.env): number {
  return parsePortBase(env['CODAY_PORT_BASE']) ?? PORT_BASES[portProfile(env)]
}
