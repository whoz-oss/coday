/**
 * Tests for the build oracle host resolution (oracle-command.mjs — resolveBuildHosts)
 * and the updated buildOracleCommand with buildHostArg support.
 *
 * Covers:
 *   B1. FACTORY_FRONT_BUILD_HOST_MAP absent → NoHostResult with reason.
 *   B2. FACTORY_FRONT_BUILD_HOST_MAP invalid JSON → NoHostResult with reason.
 *   B3. No entry for owner project, no fallback "*" → NoHostResult.
 *   B4. Fallback "*" used when owner project not in map.
 *   B5. Explicit owner mapping overrides fallback.
 *   B6. Multiple owner projects, each with their own mapping, deduplicated.
 *   B7. Host without build target in project.json is excluded.
 *   B8. Host with build target → included in valid hosts.
 *   B9. Host with build-angular target → included.
 *   B10. All resolved hosts lack build target → NoHostResult.
 *   B11. buildOracleCommand with buildHostArg: true, valid map → injects --projects.
 *   B12. buildOracleCommand with buildHostArg: true, noHost → returns NoHostResult.
 *   B13. buildOracleCommand with buildHostArg: true, command already has --projects → unchanged.
 *   B14. buildOracleCommand with buildHostArg: true, empty files → NoHostResult.
 *   B15. buildOracleCommand with filesArg: true unchanged (regression guard).
 *   B16. buildOracleCommand without filesArg/buildHostArg → command unchanged (regression guard).
 *   B17. resolveBuildHosts with ownerProjects=[] → NoHostResult (not a crash).
 *
 * No HTTP, no child process, no AgentOS.
 *
 * Usage: node factory/tests/test-build-oracle.mjs
 * Exit: 0 = all pass, 1 = at least one failure.
 */

import { resolveBuildHosts, buildOracleCommand, resolveOwnerProjects } from '../lib/oracle-command.mjs'
import { mkdtempSync, writeFileSync, mkdirSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

// ---------------------------------------------------------------------------
// Runner
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

function ok(name, value) {
  const icon = value ? '\u2713' : '\u2717'
  console.log(`${icon} ${name}`)
  if (value) passed++
  else { failed++; console.log(`  FAILED: expected truthy, got ${JSON.stringify(value)}`) }
}

function eq(name, actual, expected) {
  const match = JSON.stringify(actual) === JSON.stringify(expected)
  const icon = match ? '\u2713' : '\u2717'
  console.log(`${icon} ${name}`)
  if (match) passed++
  else {
    failed++
    console.log(`  expected: ${JSON.stringify(expected)}`)
    console.log(`  got:      ${JSON.stringify(actual)}`)
  }
}

// ---------------------------------------------------------------------------
// Env isolation helper
// ---------------------------------------------------------------------------

/**
 * Run a test function with FACTORY_FRONT_BUILD_HOST_MAP set to a given value.
 * Restores the original env after the call.
 *
 * @param {string|undefined} value
 * @param {() => void} fn
 */
function withHostMap(value, fn) {
  const original = process.env.FACTORY_FRONT_BUILD_HOST_MAP
  if (value === undefined) {
    delete process.env.FACTORY_FRONT_BUILD_HOST_MAP
  } else {
    process.env.FACTORY_FRONT_BUILD_HOST_MAP = value
  }
  try {
    fn()
  } finally {
    if (original === undefined) {
      delete process.env.FACTORY_FRONT_BUILD_HOST_MAP
    } else {
      process.env.FACTORY_FRONT_BUILD_HOST_MAP = original
    }
  }
}

// ---------------------------------------------------------------------------
// Test fixture: minimal Nx workspace
// ---------------------------------------------------------------------------

const tmpDir = mkdtempSync(join(tmpdir(), 'test-build-oracle-'))

try {
  // lib-a: owner project without a build target
  mkdirSync(join(tmpDir, 'libs', 'lib-a', 'src', 'lib'), { recursive: true })
  writeFileSync(
    join(tmpDir, 'libs', 'lib-a', 'project.json'),
    JSON.stringify({ name: 'lib-a', projectType: 'library', targets: { lint: {}, test: {} } })
  )
  writeFileSync(join(tmpDir, 'libs', 'lib-a', 'src', 'lib', 'foo.ts'), '// foo')

  // lib-b: another owner project without a build target
  mkdirSync(join(tmpDir, 'libs', 'lib-b', 'src', 'lib'), { recursive: true })
  writeFileSync(
    join(tmpDir, 'libs', 'lib-b', 'project.json'),
    JSON.stringify({ name: 'lib-b', projectType: 'library', targets: { lint: {} } })
  )
  writeFileSync(join(tmpDir, 'libs', 'lib-b', 'src', 'lib', 'bar.ts'), '// bar')

  // app-alpha: host app with a `build` target
  mkdirSync(join(tmpDir, 'apps', 'app-alpha'), { recursive: true })
  writeFileSync(
    join(tmpDir, 'apps', 'app-alpha', 'project.json'),
    JSON.stringify({ name: 'app-alpha', projectType: 'application', targets: { build: { executor: '@angular/build:application' }, lint: {} } })
  )

  // app-beta: host app with a `build-angular` target (legacy executor name)
  mkdirSync(join(tmpDir, 'apps', 'app-beta'), { recursive: true })
  writeFileSync(
    join(tmpDir, 'apps', 'app-beta', 'project.json'),
    JSON.stringify({ name: 'app-beta', projectType: 'application', targets: { 'build-angular': { executor: '@angular-devkit/build-angular:browser' }, lint: {} } })
  )

  // app-gamma: listed in host map but has NO build target
  mkdirSync(join(tmpDir, 'apps', 'app-gamma'), { recursive: true })
  writeFileSync(
    join(tmpDir, 'apps', 'app-gamma', 'project.json'),
    JSON.stringify({ name: 'app-gamma', projectType: 'application', targets: { lint: {} } })
  )

  // ---------------------------------------------------------------------------
  // B1. FACTORY_FRONT_BUILD_HOST_MAP absent → NoHostResult
  // ---------------------------------------------------------------------------

  console.log('\n=== B1. FACTORY_FRONT_BUILD_HOST_MAP absent ===')

  withHostMap(undefined, () => {
    const result = resolveBuildHosts(['lib-a'], tmpDir)
    ok('B1: returns object (not array)', !Array.isArray(result) && typeof result === 'object')
    ok('B1: noHost=true', result.noHost === true)
    ok('B1: reason mentions FACTORY_FRONT_BUILD_HOST_MAP', result.reason.includes('FACTORY_FRONT_BUILD_HOST_MAP'))
    eq('B1: ownerProjects preserved', result.ownerProjects, ['lib-a'])
  })

  // ---------------------------------------------------------------------------
  // B2. FACTORY_FRONT_BUILD_HOST_MAP invalid JSON → NoHostResult
  // ---------------------------------------------------------------------------

  console.log('\n=== B2. FACTORY_FRONT_BUILD_HOST_MAP invalid JSON ===')

  withHostMap('not valid json {{{', () => {
    const result = resolveBuildHosts(['lib-a'], tmpDir)
    ok('B2: noHost=true', result.noHost === true)
    ok('B2: reason mentions JSON', result.reason.toLowerCase().includes('json'))
  })

  // ---------------------------------------------------------------------------
  // B3. No entry for owner project, no fallback "*" → NoHostResult
  // ---------------------------------------------------------------------------

  console.log('\n=== B3. No entry, no fallback ===')

  withHostMap(JSON.stringify({ 'other-lib': ['app-alpha'] }), () => {
    const result = resolveBuildHosts(['lib-a'], tmpDir)
    ok('B3: noHost=true', result.noHost === true)
    ok('B3: reason mentions no host found', result.reason.toLowerCase().includes('no buildable host') || result.reason.toLowerCase().includes('no host'))
  })

  // ---------------------------------------------------------------------------
  // B4. Fallback "*" used when owner project not in map
  // ---------------------------------------------------------------------------

  console.log('\n=== B4. Fallback "*" ===')

  withHostMap(JSON.stringify({ '*': ['app-alpha'] }), () => {
    const result = resolveBuildHosts(['lib-a'], tmpDir)
    // app-alpha has a build target → should be accepted
    ok('B4: returns array', Array.isArray(result))
    ok('B4: app-alpha in result', Array.isArray(result) && result.includes('app-alpha'))
  })

  // ---------------------------------------------------------------------------
  // B5. Explicit owner mapping overrides fallback
  // ---------------------------------------------------------------------------

  console.log('\n=== B5. Explicit mapping overrides fallback ===')

  withHostMap(JSON.stringify({ 'lib-a': ['app-beta'], '*': ['app-alpha'] }), () => {
    const result = resolveBuildHosts(['lib-a'], tmpDir)
    ok('B5: returns array', Array.isArray(result))
    // lib-a maps to app-beta, not app-alpha (fallback not used)
    ok('B5: app-beta in result', Array.isArray(result) && result.includes('app-beta'))
    ok('B5: app-alpha NOT in result (fallback not used)', Array.isArray(result) && !result.includes('app-alpha'))
  })

  // ---------------------------------------------------------------------------
  // B6. Multiple owner projects, each with their own mapping, deduplicated
  // ---------------------------------------------------------------------------

  console.log('\n=== B6. Multiple owners, deduplication ===')

  withHostMap(JSON.stringify({ 'lib-a': ['app-alpha', 'app-beta'], 'lib-b': ['app-alpha'] }), () => {
    const result = resolveBuildHosts(['lib-a', 'lib-b'], tmpDir)
    ok('B6: returns array', Array.isArray(result))
    // Both lib-a and lib-b map to app-alpha; lib-a also maps to app-beta.
    // app-alpha must appear exactly once.
    ok('B6: app-alpha present', Array.isArray(result) && result.includes('app-alpha'))
    ok('B6: app-beta present', Array.isArray(result) && result.includes('app-beta'))
    const alphaCount = Array.isArray(result) ? result.filter((h) => h === 'app-alpha').length : -1
    eq('B6: app-alpha deduplicated (appears once)', alphaCount, 1)
  })

  // ---------------------------------------------------------------------------
  // B7. Host without build target in project.json is excluded
  // ---------------------------------------------------------------------------

  console.log('\n=== B7. Host without build target excluded ===')

  withHostMap(JSON.stringify({ '*': ['app-gamma'] }), () => {
    const result = resolveBuildHosts(['lib-a'], tmpDir)
    // app-gamma has no build target → excluded → NoHostResult
    ok('B7: noHost=true (all hosts excluded)', !Array.isArray(result) && result.noHost === true)
    ok('B7: reason mentions excluded hosts', result.reason.includes('app-gamma'))
  })

  // ---------------------------------------------------------------------------
  // B8. Host with build target → included
  // ---------------------------------------------------------------------------

  console.log('\n=== B8. Host with build target included ===')

  withHostMap(JSON.stringify({ '*': ['app-alpha'] }), () => {
    const result = resolveBuildHosts(['lib-a'], tmpDir)
    ok('B8: returns array', Array.isArray(result))
    ok('B8: app-alpha included', Array.isArray(result) && result.includes('app-alpha'))
  })

  // ---------------------------------------------------------------------------
  // B9. Host with build-angular target → included
  // ---------------------------------------------------------------------------

  console.log('\n=== B9. Host with build-angular target ===')

  withHostMap(JSON.stringify({ '*': ['app-beta'] }), () => {
    const result = resolveBuildHosts(['lib-a'], tmpDir)
    ok('B9: returns array', Array.isArray(result))
    ok('B9: app-beta included (build-angular target)', Array.isArray(result) && result.includes('app-beta'))
  })

  // ---------------------------------------------------------------------------
  // B10. All resolved hosts lack build target → NoHostResult
  // ---------------------------------------------------------------------------

  console.log('\n=== B10. All hosts lack build target ===')

  withHostMap(JSON.stringify({ '*': ['app-gamma'] }), () => {
    const result = resolveBuildHosts(['lib-b'], tmpDir)
    ok('B10: noHost=true', !Array.isArray(result) && result.noHost === true)
    ok('B10: reason mentions app-gamma', result.reason.includes('app-gamma'))
  })

  // ---------------------------------------------------------------------------
  // B11. buildOracleCommand with buildHostArg: true, valid map → injects --projects
  // ---------------------------------------------------------------------------

  console.log('\n=== B11. buildOracleCommand buildHostArg valid map ===')

  withHostMap(JSON.stringify({ '*': ['app-alpha'] }), () => {
    const oracle = {
      name: 'build',
      command: 'pnpm nx run-many --target=build --configuration=development --skip-nx-cache',
      cwd: tmpDir,
      buildHostArg: true,
    }
    const files = ['libs/lib-a/src/lib/foo.ts']
    const cmd = buildOracleCommand(oracle, files, tmpDir)
    ok('B11: returns string', typeof cmd === 'string')
    ok('B11: contains --projects=app-alpha', typeof cmd === 'string' && cmd.includes('--projects=app-alpha'))
    ok('B11: preserves --configuration=development', typeof cmd === 'string' && cmd.includes('--configuration=development'))
    ok('B11: preserves --skip-nx-cache', typeof cmd === 'string' && cmd.includes('--skip-nx-cache'))
  })

  // ---------------------------------------------------------------------------
  // B12. buildOracleCommand with buildHostArg: true, noHost → returns NoHostResult
  // ---------------------------------------------------------------------------

  console.log('\n=== B12. buildOracleCommand buildHostArg noHost ===')

  withHostMap(undefined, () => {
    const oracle = {
      name: 'build',
      command: 'pnpm nx run-many --target=build --configuration=development --skip-nx-cache',
      cwd: tmpDir,
      buildHostArg: true,
    }
    const files = ['libs/lib-a/src/lib/foo.ts']
    const result = buildOracleCommand(oracle, files, tmpDir)
    ok('B12: returns object (sentinel)', typeof result === 'object' && result !== null)
    ok('B12: noHost=true', result.noHost === true)
    ok('B12: reason is non-empty string', typeof result.reason === 'string' && result.reason.length > 0)
  })

  // ---------------------------------------------------------------------------
  // B13. buildOracleCommand with buildHostArg: true, command already has --projects → unchanged
  // ---------------------------------------------------------------------------

  console.log('\n=== B13. buildOracleCommand buildHostArg command already has --projects ===')

  withHostMap(JSON.stringify({ '*': ['app-alpha'] }), () => {
    const fixedCommand = 'pnpm nx run-many --target=build --projects=aphrodite,admin --configuration=development --skip-nx-cache'
    const oracle = {
      name: 'build',
      command: fixedCommand,
      cwd: tmpDir,
      buildHostArg: true,
    }
    const files = ['libs/lib-a/src/lib/foo.ts']
    const cmd = buildOracleCommand(oracle, files, tmpDir)
    eq('B13: command unchanged when --projects already present', cmd, fixedCommand)
  })

  // ---------------------------------------------------------------------------
  // B14. buildOracleCommand with buildHostArg: true, empty files → NoHostResult
  // ---------------------------------------------------------------------------

  console.log('\n=== B14. buildOracleCommand buildHostArg empty files ===')

  withHostMap(JSON.stringify({ '*': ['app-alpha'] }), () => {
    const oracle = {
      name: 'build',
      command: 'pnpm nx run-many --target=build --configuration=development --skip-nx-cache',
      cwd: tmpDir,
      buildHostArg: true,
    }
    const result = buildOracleCommand(oracle, [], tmpDir)
    ok('B14: returns sentinel (no files)', typeof result === 'object' && result !== null && result.noHost === true)
    ok('B14: reason mentions no files', result.reason.toLowerCase().includes('no files') || result.reason.toLowerCase().includes('cannot resolve'))
  })

  // ---------------------------------------------------------------------------
  // B15. buildOracleCommand with filesArg: true — regression guard
  // ---------------------------------------------------------------------------

  console.log('\n=== B15. buildOracleCommand filesArg regression guard ===')

  {
    const oracle = {
      name: 'tests',
      command: 'pnpm nx affected -t frontend-test',
      cwd: tmpDir,
      filesArg: true,
    }
    const files = ['libs/lib-a/src/lib/foo.ts']
    const cmd = buildOracleCommand(oracle, files, tmpDir)
    ok('B15: returns string', typeof cmd === 'string')
    ok('B15: uses run-many', typeof cmd === 'string' && cmd.startsWith('pnpm nx run-many'))
    ok('B15: contains lib-a', typeof cmd === 'string' && cmd.includes('lib-a'))
    ok('B15: contains --skip-nx-cache', typeof cmd === 'string' && cmd.includes('--skip-nx-cache'))
    ok('B15: does NOT contain --projects=app-alpha (no buildHostArg)', typeof cmd === 'string' && !cmd.includes('app-alpha'))
  }

  // ---------------------------------------------------------------------------
  // B16. buildOracleCommand without filesArg/buildHostArg → command unchanged
  // ---------------------------------------------------------------------------

  console.log('\n=== B16. buildOracleCommand no flags → unchanged ===')

  withHostMap(JSON.stringify({ '*': ['app-alpha'] }), () => {
    const fixedCommand = './gradlew :agentos-service:build --rerun-tasks --console=plain'
    const oracle = {
      name: 'build',
      command: fixedCommand,
      cwd: '/repo/agentos',
    }
    const files = ['libs/lib-a/src/lib/foo.ts']
    const cmd = buildOracleCommand(oracle, files, tmpDir)
    eq('B16: command unchanged (no filesArg, no buildHostArg)', cmd, fixedCommand)
  })

  // ---------------------------------------------------------------------------
  // B17. resolveBuildHosts with ownerProjects=[] → NoHostResult
  // ---------------------------------------------------------------------------

  console.log('\n=== B17. resolveBuildHosts ownerProjects=[] ===')

  withHostMap(JSON.stringify({ '*': ['app-alpha'] }), () => {
    const result = resolveBuildHosts([], tmpDir)
    // No owners → no hosts collected → NoHostResult
    ok('B17: noHost=true when ownerProjects=[]', !Array.isArray(result) && result.noHost === true)
    ok('B17: reason is non-empty', typeof result.reason === 'string' && result.reason.length > 0)
  })

  // ---------------------------------------------------------------------------
  // B18. Multiple owners: one maps to valid host, one maps to invalid host → valid host returned
  // ---------------------------------------------------------------------------

  console.log('\n=== B18. Mixed valid/invalid hosts across owners ===')

  withHostMap(JSON.stringify({ 'lib-a': ['app-alpha'], 'lib-b': ['app-gamma'] }), () => {
    const result = resolveBuildHosts(['lib-a', 'lib-b'], tmpDir)
    // app-alpha is valid, app-gamma is not (no build target)
    // app-gamma is excluded with warning, but app-alpha remains
    ok('B18: returns array (some valid hosts)', Array.isArray(result))
    ok('B18: app-alpha in result', Array.isArray(result) && result.includes('app-alpha'))
    ok('B18: app-gamma NOT in result (excluded)', Array.isArray(result) && !result.includes('app-gamma'))
  })

  // ---------------------------------------------------------------------------
  // B19. buildOracleCommand: multiple hosts in --projects
  // ---------------------------------------------------------------------------

  console.log('\n=== B19. buildOracleCommand multiple hosts ===')

  withHostMap(JSON.stringify({ '*': ['app-alpha', 'app-beta'] }), () => {
    const oracle = {
      name: 'build',
      command: 'pnpm nx run-many --target=build --configuration=development --skip-nx-cache',
      cwd: tmpDir,
      buildHostArg: true,
    }
    const files = ['libs/lib-a/src/lib/foo.ts']
    const cmd = buildOracleCommand(oracle, files, tmpDir)
    ok('B19: returns string', typeof cmd === 'string')
    ok('B19: contains app-alpha', typeof cmd === 'string' && cmd.includes('app-alpha'))
    ok('B19: contains app-beta', typeof cmd === 'string' && cmd.includes('app-beta'))
    // Both should appear in a single --projects=... argument
    ok('B19: single --projects flag', typeof cmd === 'string' && (cmd.match(/--projects=/g) ?? []).length === 1)
  })

  // ---------------------------------------------------------------------------
  // B20. resolveBuildHosts: map is valid JSON but not an object → NoHostResult
  // ---------------------------------------------------------------------------

  console.log('\n=== B20. resolveBuildHosts map is array (not object) ===')

  withHostMap(JSON.stringify(['app-alpha', 'app-beta']), () => {
    const result = resolveBuildHosts(['lib-a'], tmpDir)
    ok('B20: noHost=true when map is array', !Array.isArray(result) && result.noHost === true)
    ok('B20: reason mentions type', result.reason.includes('object'))
  })

} finally {
  rmSync(tmpDir, { recursive: true, force: true })
}

// ---------------------------------------------------------------------------
// Result
// ---------------------------------------------------------------------------

console.log('')
console.log(`Result: ${passed} passed, ${failed} failed`)
process.exit(failed > 0 ? 1 : 0)
