/**
 * Production environment — substituted by fileReplacements in build-angular:production.
 *
 * Both the cockpit and the AgentOS UI are served behind the same gateway
 * origin, so a path-only URL (/agentos/home?…) resolves correctly.
 * Empty string = no origin prefix.
 */
export const environment = {
  agentOsBaseUrl: '',
}
