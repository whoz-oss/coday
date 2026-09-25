// Stateless compatibility facade. Task-outcome counting and the pure snapshot
// model live only in the generated operational bundle, built from the TypeScript
// source `factory/src/domain/oracle/oracle.ts`. Command execution and Git-backed
// snapshots live in `factory/src/application/oracle/oracle-executor.ts`.
export { countTaskOutcomes, diffSnapshots, runCommand, snapshotDiff, diffSince } from '../runtime/factory-operational.mjs'
