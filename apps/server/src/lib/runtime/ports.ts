/**
 * Port selection of the stack. Leaf module (no import of version.ts) so it is unit-testable.
 */

import { findAvailablePort } from '../find-available-port'

export const FACTORY_DEFAULT_PORT = 8141

export interface PickedPorts {
  express: number
  agentos: number
  agentosBolt: number
  factory: number
}

/**
 * AgentOS never takes the Express port, the Bolt port follows AgentOS, and the Factory keeps its
 * historical 8141 default (also the AgentOS factory-bridge default).
 */
export async function pickPorts(
  express: number,
  find: (start: number, attempts: number) => Promise<number> = findAvailablePort
): Promise<PickedPorts> {
  const agentos = await find(express + 1, 10)
  const agentosBolt = await find(agentos + 1, 10)
  let factory = await find(FACTORY_DEFAULT_PORT, 10)
  // The sequential probes release their ports: make sure two services never get the same one.
  const taken = new Set([express, agentos, agentosBolt])
  while (taken.has(factory)) factory = await find(factory + 1, 10)
  return { express, agentos, agentosBolt, factory }
}
