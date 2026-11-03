/**
 * Port selection of the stack. Leaf module (no import of version.ts) so it is unit-testable.
 */

import { findAvailablePort } from '../find-available-port'

/**
 * Preferred ports of the PACKAGED stack (npx coday-web, web:local, desktop apps).
 *
 * They deliberately differ from the dev ports (AgentOS 8124, Factory 8141, embedded Bolt 7688: the defaults of
 * application.yml and of `pnpm web:serve:dev`), which are reserved for dev mode. A packaged instance and a dev
 * stack can therefore run side by side without colliding, and never pick up each other's services.
 */
export const AGENTOS_DEFAULT_PORT = 8224
/** Bolt port of the embedded Neo4j of AgentOS (the dev default, 7688, is left to the dev stack). */
export const AGENTOS_BOLT_DEFAULT_PORT = 7788
export const FACTORY_DEFAULT_PORT = 8241

export interface PickedPorts {
  express: number
  agentos: number
  agentosBolt: number
  factory: number
}

/**
 * Every service prefers its packaged-mode port (AgentOS 8224, its embedded Neo4j Bolt 7788, Factory 8241)
 * and falls back to the next free one. Express keeps the port it was given and no service ever shares a
 * port with Express or with another service.
 */
export async function pickPorts(
  express: number,
  find: (start: number, attempts: number) => Promise<number> = findAvailablePort
): Promise<PickedPorts> {
  const taken = new Set<number>([express])
  // The sequential probes release their ports: bump past every port already assigned in this run.
  const pick = async (preferred: number): Promise<number> => {
    let port = await find(preferred, 10)
    while (taken.has(port)) port = await find(port + 1, 10)
    taken.add(port)
    return port
  }
  const agentos = await pick(AGENTOS_DEFAULT_PORT)
  const agentosBolt = await pick(AGENTOS_BOLT_DEFAULT_PORT)
  const factory = await pick(FACTORY_DEFAULT_PORT)
  return { express, agentos, agentosBolt, factory }
}
