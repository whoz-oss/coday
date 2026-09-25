// Stateless compatibility facade. The delivery git control-plane (worktree
// inspection, checkpoints, verified pushes) lives only in the generated
// operational bundle, built from the TypeScript source
// `factory/src/adapters/delivery/delivery-git-control-plane.ts`.
export { DeliveryGitControlPlane } from '../runtime/factory-operational.mjs'
