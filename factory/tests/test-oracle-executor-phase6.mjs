import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { countTaskOutcomes } from '../lib/oracle.mjs'
import { executeOracle, oracleArtifact, validateOracleRoot } from '../lib/oracle-executor.mjs'

const fixture = name => resolve('factory/tests/fixtures/oracle-process', name)
const definition = (script, overrides = {}) => ({
  argv: [process.execPath, fixture(script)], cwd: 'repo-root', timeoutMs: 2000,
  success: { rule: 'exit-code', requireWork: true }, ...overrides,
})
const root = await mkdtemp(join(tmpdir(), 'oracle-executor-'))
const canonicalRoot = await validateOracleRoot(root)
try {
  const sentinel = join(root, 'checkout-sentinel.txt')
  await writeFile(sentinel, 'unchanged')

  const pass = await executeOracle(definition('pass.mjs'), { repoRoot: canonicalRoot, countTaskOutcomes })
  assert.equal(pass.outcome, 'pass')
  assert.equal(pass.counts.executed, 1, 'the fixture emits a recognized Gradle task line')
  assert.match(pass.stdout.excerpt, /> Task :factory:oracle-smoke/)

  const empty = await executeOracle(definition('empty.mjs'), { repoRoot: canonicalRoot, countTaskOutcomes })
  assert.deepEqual([empty.classification, empty.outcome], ['EMPTY_SUCCESS', 'indeterminate'])

  const failed = await executeOracle(definition('fail.mjs'), { repoRoot: canonicalRoot, countTaskOutcomes })
  assert.deepEqual([failed.classification, failed.outcome, failed.exitCode], ['PRODUCT_REGRESSION', 'fail', 7])

  const timeout = await executeOracle(definition('timeout.mjs', { timeoutMs: 50 }), { repoRoot: canonicalRoot, countTaskOutcomes })
  assert.deepEqual([timeout.classification, timeout.outcome, timeout.timedOut], ['ORACLE_INFRASTRUCTURE', 'indeterminate', true])

  const missing = await executeOracle({ ...definition('pass.mjs'), argv: ['/definitely/missing/oracle'] }, { repoRoot: canonicalRoot, countTaskOutcomes })
  assert.deepEqual([missing.classification, missing.outcome, missing.spawnError], ['ORACLE_INFRASTRUCTURE', 'indeterminate', 'ENOENT'])

  const large = await executeOracle(definition('large-output.mjs'), { repoRoot: canonicalRoot, countTaskOutcomes })
  const artifactOne = oracleArtifact(large)
  const artifactTwo = oracleArtifact(large)
  assert.equal(large.stdout.truncated, true)
  assert.equal(large.stderr.truncated, true)
  assert.equal(artifactOne.hash, artifactTwo.hash)
  const evidenceFacts = { outputHash: artifactOne.hash, outputTruncated: true }
  assert.equal(JSON.stringify(evidenceFacts).includes('OOOO'), false)
  assert.equal(JSON.stringify(evidenceFacts).includes('EEEE'), false)

  let spawnOptions
  const fakeSpawn = (_command, _args, options) => {
    spawnOptions = options
    throw Object.assign(new Error('captured'), { code: 'CAPTURED' })
  }
  await assert.rejects(() => executeOracle(definition('pass.mjs'), {
    repoRoot: canonicalRoot, countTaskOutcomes, spawnImpl: fakeSpawn,
    environment: { PATH: '/bin', HOME: '/home/test', SECRET_TOKEN: 'must-not-pass' },
  }), /captured/)
  assert.equal(spawnOptions.shell, false)
  assert.equal(spawnOptions.cwd, canonicalRoot)
  assert.equal(spawnOptions.stdio[0], 'ignore')
  assert.equal(spawnOptions.env.SECRET_TOKEN, undefined)
  assert.equal(spawnOptions.env.PATH, '/bin')

  assert.equal(await readFile(sentinel, 'utf8'), 'unchanged', 'all fixtures are non-mutating')
  assert.equal(await validateOracleRoot(root), canonicalRoot)
  await assert.rejects(() => validateOracleRoot('relative/root'), /INVALID_ORACLE_ROOT/)
  await assert.rejects(() => validateOracleRoot(join(root, 'missing')), /ENOENT/)
} finally {
  await rm(root, { recursive: true, force: true })
}
// Unix timeout above exercises detached process-group termination. A grandchild
// operational assertion remains intentionally omitted because PID reuse makes it flaky.
console.log('oracle executor Phase 6 source tests: OK')
