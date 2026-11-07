/**
 * What a start prints so that nobody has to guess the addresses (pure, unit-tested). Express is the ONLY address a user
 * opens, in every profile: packaged (prod, local) it serves the client at `/` and the cockpit at `/factory/`; in dev the
 * Angular dev servers listen on their own ports and are INTERNAL, reached through Express. AgentOS, the Factory and the
 * embedded Neo4j Bolt are internal everywhere.
 */

export interface StartupInfo {
  /** Dev: the Angular dev servers are part of the stack (internal ports). */
  dev: boolean
  express: number
  /** Ports of the managed services (state.json / the block). */
  agentos?: number
  factory?: number
  bolt?: number
  /** Dev only: where the Angular dev servers listen. */
  client?: number
  cockpit?: number
  /** Origin of an external service (`AGENTOS_URL`, `FACTORY_URL`): replaces its managed port. */
  externalAgentos?: string
  externalFactory?: string
  /** `CODAY_NO_FACTORY`: no Factory to mention. */
  factoryDisabled?: boolean
}

/** `4005` for a managed service, the port (or the host) of an external one. */
function servicePort(external: string | undefined, managed: number | undefined): string | undefined {
  if (external) {
    try {
      const url = new URL(external)
      return url.port || url.host
    } catch {
      return 'external'
    }
  }
  return managed === undefined ? undefined : String(managed)
}

export function startupLines(info: StartupInfo): string[] {
  const origin = `http://localhost:${info.express}`
  const internal: string[] = []
  const add = (label: string, value: string | undefined): void => {
    if (value !== undefined) internal.push(`${label} ${value}`)
  }
  if (info.dev) {
    add('client', servicePort(undefined, info.client))
    add('cockpit', servicePort(undefined, info.cockpit))
  }
  add('AgentOS', servicePort(info.externalAgentos, info.agentos))
  if (!info.factoryDisabled || info.externalFactory) add('Factory', servicePort(info.externalFactory, info.factory))
  if (info.dev || !info.externalAgentos) add(info.dev ? 'Bolt' : 'Neo4j Bolt', servicePort(undefined, info.bolt))
  return [
    `Coday     ${origin}/`,
    `Cockpit   ${origin}/factory/`,
    ...(internal.length > 0 ? [`Internal  ${internal.join(' · ')}`] : []),
  ]
}
