/**
 * Port selection of the stack. Leaf module (no import of version.ts) so it is unit-testable.
 */

import { findAvailablePort } from '../find-available-port'

/** Conventional ports of the services (application.yml, docs, AGENTOS_URL examples). */
export const AGENTOS_DEFAULT_PORT = 8124
/** Default Bolt port of the embedded Neo4j of AgentOS (application-embedded-neo4j.yml). */
export const AGENTOS_BOLT_DEFAULT_PORT = 7688
export const FACTORY_DEFAULT_PORT = 8141

export interface PickedPorts {
  express: number
  agentos: number
  agentosBolt: number
  factory: number
}

/**
 * Every service prefers its conventional port (AgentOS 8124, its embedded Neo4j Bolt 7688, Factory 8141)
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
