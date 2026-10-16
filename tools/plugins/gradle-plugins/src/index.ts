// AgentOS plugin — registered in nx.json as the primary plugin entry point
export { createNodesV2, buildProjectConfig } from './agentos-gradle'

// Factory plugin exports — available for testing; the factory plugin is registered
// separately in nx.json via its own file path (./tools/plugins/gradle-plugins/src/factory-gradle.ts)
export { buildFactoryProjectConfig } from './factory-gradle'
