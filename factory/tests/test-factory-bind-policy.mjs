// Local unauthenticated trust-boundary policy. Intentionally not executed here.
import { resolveFactoryBindPolicy } from '../dashboard/server.mjs'
let passed = 0, failed = 0
function expect(name, actual, expected) { const ok = JSON.stringify(actual) === JSON.stringify(expected); console.log(`${ok ? '✓' : '✗'} ${name}`); if (!ok) console.log(` expected=${JSON.stringify(expected)} actual=${JSON.stringify(actual)}`); ok ? passed++ : failed++ }
expect('default bind is explicit loopback', resolveFactoryBindPolicy({}), { host: '127.0.0.1', trustMode: 'loopback-only' })
expect('localhost remains compatible', resolveFactoryBindPolicy({ FACTORY_BIND_HOST: 'localhost' }), { host: 'localhost', trustMode: 'loopback-only' })
let rejected = false; try { resolveFactoryBindPolicy({ FACTORY_BIND_HOST: '0.0.0.0' }) } catch { rejected = true }
expect('remote bind rejected without opt-in', rejected, true)
expect('explicit unsafe remote opt-in', resolveFactoryBindPolicy({ FACTORY_BIND_HOST: '0.0.0.0', FACTORY_UNSAFE_ALLOW_REMOTE_BIND: 'true' }), { host: '0.0.0.0', trustMode: 'unsafe-remote-unauthenticated' })
console.log(`\nResult: ${passed} passed, ${failed} failed`); process.exit(failed ? 1 : 0)
