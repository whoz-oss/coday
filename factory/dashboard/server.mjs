/**
 * Factory dashboard — thin bootstrap.
 *
 * Usage : node factory/dashboard/server.mjs
 * Port  : 3141 (configurable via PORT env)
 *
 * This file contains no wiring: the object graph lives in `composition-root.mjs`.
 * Here we only detect direct execution, build the root, and start listening.
 * The re-exports below keep the historical import surface used by the offline
 * factory tests stable while their owners stay in their own modules.
 */

import { fileURLToPath } from 'node:url'
import { createCompositionRoot } from './composition-root.mjs'

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const root = createCompositionRoot(process.env)
  await root.start()
}

export { parseJsonl, reconstructPhases } from './run-routes.mjs'
export { isAllowedStoryEditRequestBody } from './http-utils.mjs'
export { resolveFactoryBindPolicy } from './composition-root.mjs'
