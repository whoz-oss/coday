/**
 * Static guard for the Phase 5 composition-root migration.
 *
 * This is a source-level contract test: it does not exercise HTTP. It protects
 * the invariants the migration is built on, so a future edit cannot quietly
 * undo them.
 *
 *   1. `server.mjs` is a thin bootstrap (< 50 lines).
 *   2. The composition root exposes the explicit lifecycle steps.
 *   3. Only the composition root instantiates stores/registries/hubs/proxies.
 *   4. `http-utils.mjs` owns correlation, error normalization and TrustContext.
 *   5. `openapi.json` is valid OpenAPI 3.0.3 and tags the legacy routes.
 *
 * Usage : node factory/tests/test-composition-root-source.mjs
 * Code de sortie : 0 = tous les cas passent, 1 = au moins un échec.
 */

import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const DASHBOARD = join(__dirname, '..', 'dashboard')
const read = (name) => readFileSync(join(DASHBOARD, name), 'utf8')

// 1. Thin bootstrap ---------------------------------------------------------
const server = read('server.mjs')
const serverLines = server.split('\n').filter((line) => line.trim().length > 0).length
assert.ok(serverLines < 50, `server.mjs must stay a thin bootstrap, found ${serverLines} non-empty lines`)
assert.match(server, /createCompositionRoot/)
assert.doesNotMatch(server, /createServer\(/, 'server.mjs must not build the HTTP server itself')
assert.doesNotMatch(server, /new\s+[A-Za-z]*Store\(/, 'server.mjs must not instantiate stores')
assert.match(server, /export \{ parseJsonl, reconstructPhases \}/)
assert.match(server, /export \{ isAllowedStoryEditRequestBody \}/)
assert.match(server, /resolveFactoryBindPolicy/)

// 2. Composition root lifecycle --------------------------------------------
const root = read('composition-root.mjs')
for (const fn of ['loadConfig', 'createStores', 'createAdapters', 'createApplication', 'createHttpServer', 'createCompositionRoot', 'resolveFactoryBindPolicy']) {
  assert.match(root, new RegExp(`export (async )?function ${fn}\\b`), `composition-root.mjs must export ${fn}`)
}
assert.match(root, /extractTrustContext\(req, config\.bindPolicy\)/)
assert.match(root, /resolveCorrelationId\(req\)/)

// 3. Single store instantiation in the dashboard ---------------------------
const ALLOWED = new Set(['composition-root.mjs'])
const routeFiles = readdirSync(DASHBOARD).filter((name) => name.endsWith('.mjs') && !ALLOWED.has(name))
for (const file of routeFiles) {
  const source = readFileSync(join(DASHBOARD, file), 'utf8')
  assert.doesNotMatch(
    source,
    /new\s+[A-Za-z0-9_]*(Store|Registry|SseHub|Provisioner|ControlPlane|Adapter|Controller|Service|Proxy)\s*\(/,
    `${file} must not instantiate stores/adapters/controllers outside the composition root`,
  )
}

// 4. Transport utilities own the shared contract ---------------------------
const utils = read('http-utils.mjs')
assert.match(utils, /export const CORRELATION_ID_HEADER = 'x-correlation-id'/)
assert.match(utils, /export function sendError\(/)
assert.match(utils, /export function extractTrustContext\(/)
assert.match(utils, /export function normalizeErrorBody\(/)
assert.match(utils, /details/)

// 5. OpenAPI specification --------------------------------------------------
const spec = JSON.parse(read('openapi.json'))
assert.equal(spec.openapi, '3.0.3')
assert.ok(spec.info && typeof spec.info.version === 'string')
assert.ok(Object.keys(spec.paths).length >= 40, 'openapi.json must document the Factory surface')

const deprecatedPaths = Object.entries(spec.paths)
  .filter(([, item]) => Object.values(item).some((operation) => operation && operation.deprecated === true))
  .map(([path]) => path)
for (const legacy of ['/api/runs', '/api/factory/runs', '/api/factory/runs/{id}/review-gate', '/api/review-gate']) {
  assert.ok(deprecatedPaths.includes(legacy), `${legacy} must be marked deprecated in openapi.json`)
}
assert.ok(spec.paths['/api/runs'].get.description.includes('retirement'), 'legacy routes must document a retirement schedule')

// Every $ref must resolve.
const refs = new Set()
const collect = (node) => {
  if (!node || typeof node !== 'object') return
  if (Array.isArray(node)) return node.forEach(collect)
  for (const [key, value] of Object.entries(node)) {
    if (key === '$ref' && typeof value === 'string') refs.add(value)
    else collect(value)
  }
}
collect(spec)
for (const ref of refs) {
  const resolved = ref.replace(/^#\//, '').split('/').reduce((cursor, part) => cursor?.[part], spec)
  assert.ok(resolved, `unresolved OpenAPI $ref: ${ref}`)
}

console.log(`composition root source guard: ok (server ${serverLines} lines, ${deprecatedPaths.length} deprecated paths)`)
