// Stateless compatibility facade. Owner-project and build-host resolution plus
// command building live only in the generated operational bundle, built from the
// TypeScript source `factory/src/application/oracle/oracle-command.ts`.
export { resolveBuildHosts, resolveOwnerProjects, buildOracleCommand } from '../runtime/factory-operational.mjs'
