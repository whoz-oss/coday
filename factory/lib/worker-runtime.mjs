// Stateless compatibility facade for the local worker runtime (Jalon C2-T2).
//
// The whole worker-runtime surface — the frozen C2-T1 loop (`WorkerRuntime`),
// its vocabulary/types and the C2-T2 local entrypoint (`createLocalWorkerRuntime`
// / `runLocalWorker` / `createDemoWorkExecutor`) — lives only in the generated
// operational bundle, built from
// `factory/src/entrypoints/worker-runtime.ts`. This file holds no state and
// duplicates no logic: it only re-exports the bundle, exactly like the other
// `factory/lib/*.mjs` facades.

export {
  WorkerRuntime,
  createDemoWorkExecutor,
  createLocalWorkerRuntime,
  runLocalWorker,
  createConsoleWorkerRuntimeLogger,
} from '../runtime/factory-operational.mjs'

export * from '../runtime/factory-operational.mjs'
