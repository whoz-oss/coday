/**
 * Development environment — used by `ng serve` (build-angular:development).
 *
 * The cockpit runs on :4300 and the AgentOS UI on :4200; links to
 * /agentos/home must carry the full origin so the browser resolves them
 * to the correct port instead of the cockpit origin.
 */
export const environment = {
  agentOsBaseUrl: 'http://localhost:4200',
}
