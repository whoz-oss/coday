// Storage kernel unit tests.
//
// Offline, no framework: exits 0 when every case passes, 1 otherwise.
// Usage: node factory/tests/test-storage-kernel.mjs
import assert from 'node:assert/strict'
import { mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import {
  appendDurableJson,
  acquireProcessLock,
  assertSupportedFormatVersion,
  atomicTemporaryPath,
  atomicWriteJson,
  canonicalize,
  computeCanonicalHash,
  createKeyedLock,
  isNotFoundError,
  readFormatVersion,
  readJsonLines,
  STORAGE_FORMAT_VERSION,
  STORAGE_KERNEL_ERROR_CODES,
  StorageKernelError,
  storageErrorCode,
  syncDirectory,
  withFormatVersion,
  withProcessLock,
} from '../runtime/factory-operational.mjs'
import { createHash } from 'node:crypto'

let passed = 0
let failed = 0

async function scenario(name, fn) {
  try {
    await fn()
    passed++
    console.log(`✓ ${name}`)
  } catch (error) {
    failed++
    console.log(`✗ ${name}`)
    console.log(`  ${error?.stack ?? error}`)
  }
}

const root = await mkdtemp(join(tmpdir(), 'factory-kernel-'))

try {
  await scenario('atomic write publishes one canonical JSON line without temp residue', async () => {
    const file = join(root, 'atomic', 'record.json')
    await atomicWriteJson(file, { b: 1, a: 2 })
    const raw = await readFile(file, 'utf8')
    assert.equal(raw, '{"b":1,"a":2}\n')
    const entries = await readdir(join(root, 'atomic'))
    assert.deepEqual(entries, ['record.json'])
  })

  await scenario('atomic write overwrites atomically and is mode-restricted', async () => {
    const file = join(root, 'atomic', 'record.json')
    await atomicWriteJson(file, { value: 'first' })
    await atomicWriteJson(file, { value: 'second' })
    assert.deepEqual(JSON.parse(await readFile(file, 'utf8')), { value: 'second' })
    if (process.platform !== 'win32') {
      const mode = (await stat(file)).mode & 0o777
      assert.equal(mode, 0o600)
    }
  })

  await scenario('atomic temporary path is scoped to the target directory', () => {
    const file = join(root, 'atomic', 'record.json')
    const temporary = atomicTemporaryPath(file)
    assert.match(temporary, /record\.json\.tmp-\d+-[0-9a-f]{12}$/)
    assert.equal(temporary.startsWith(`${file}.tmp-`), true)
  })

  await scenario('durable append preserves JSONL order and creates directories on demand', async () => {
    const file = join(root, 'journal', 'events.jsonl')
    await appendDurableJson(file, { n: 1 }, { ensureDirectory: true })
    await appendDurableJson(file, { n: 2 }, { ensureDirectory: true })
    assert.equal(await readFile(file, 'utf8'), '{"n":1}\n{"n":2}\n')
    assert.deepEqual(await readJsonLines(file), [{ n: 1 }, { n: 2 }])
  })

  await scenario('readJsonLines tolerates a missing file and rejects corruption', async () => {
    assert.deepEqual(await readJsonLines(join(root, 'missing.jsonl')), [])
    const corrupt = join(root, 'corrupt.jsonl')
    await writeFile(corrupt, '{not json}\n')
    await assert.rejects(() => readJsonLines(corrupt))
  })

  await scenario('syncDirectory succeeds on an existing directory', async () => {
    await syncDirectory(root)
  })

  await scenario('canonical hashing ignores key order and matches sha256 of canonical JSON', () => {
    const left = { b: 2, a: { d: 4, c: 3 } }
    const right = { a: { c: 3, d: 4 }, b: 2 }
    assert.deepEqual(canonicalize(left), right)
    assert.equal(computeCanonicalHash(left), computeCanonicalHash(right))
    const expected = createHash('sha256')
      .update(JSON.stringify({ a: { c: 3, d: 4 }, b: 2 }), 'utf8')
      .digest('hex')
    assert.equal(computeCanonicalHash(left), expected)
    assert.notEqual(computeCanonicalHash({ a: 1 }), computeCanonicalHash({ a: 2 }))
  })

  await scenario('keyed lock serializes same-key actions and parallelizes distinct keys', async () => {
    const lock = createKeyedLock()
    const order = []
    let releaseA
    const gateA = new Promise((resolve) => {
      releaseA = resolve
    })
    const first = lock.run('k', async () => {
      order.push('first-start')
      await gateA
      order.push('first-end')
    })
    const second = lock.run('k', async () => {
      order.push('second')
    })
    await new Promise((resolve) => setTimeout(resolve, 5))
    assert.deepEqual(order, ['first-start'])
    releaseA()
    await Promise.all([first, second])
    assert.deepEqual(order, ['first-start', 'first-end', 'second'])

    let other = false
    await Promise.all([lock.run('a', async () => {}), lock.run('b', async () => (other = true))])
    assert.equal(other, true)
    assert.equal(lock.size, 0)
  })

  await scenario('keyed lock does not poison subsequent actions after a failure', async () => {
    const lock = createKeyedLock()
    await assert.rejects(() => lock.run('k', async () => Promise.reject(new Error('boom'))))
    assert.equal(await lock.run('k', async () => 'ok'), 'ok')
  })

  await scenario('process data-root lock is exclusive then releasable', async () => {
    const dataRoot = join(root, 'data-root')
    const lock = await acquireProcessLock(dataRoot)
    assert.equal(lock.pid, process.pid)
    await assert.rejects(
      () => acquireProcessLock(dataRoot),
      (error) => error instanceof StorageKernelError && error.code === STORAGE_KERNEL_ERROR_CODES.LOCK_HELD
    )
    await lock.release()
    const reacquired = await acquireProcessLock(dataRoot)
    await reacquired.release()
    assert.deepEqual(await readdir(dataRoot).then((entries) => entries.filter((e) => e.endsWith('.lock'))), [])
  })

  await scenario('withProcessLock releases the lock even when the action throws', async () => {
    const dataRoot = join(root, 'with-lock')
    await assert.rejects(() =>
      withProcessLock(dataRoot, async () => {
        throw new Error('inside')
      })
    )
    const lock = await acquireProcessLock(dataRoot)
    await lock.release()
  })

  await scenario('format versioning metadata round-trips and fails closed', () => {
    const record = withFormatVersion({ payload: 'x' })
    assert.equal(record.formatVersion, STORAGE_FORMAT_VERSION)
    assert.equal(readFormatVersion(record), STORAGE_FORMAT_VERSION)
    assert.equal(readFormatVersion({}), null)
    assert.equal(assertSupportedFormatVersion(record), STORAGE_FORMAT_VERSION)
    assert.throws(
      () => assertSupportedFormatVersion({ formatVersion: STORAGE_FORMAT_VERSION + 1 }),
      (error) =>
        error instanceof StorageKernelError && error.code === STORAGE_KERNEL_ERROR_CODES.UNSUPPORTED_FORMAT_VERSION
    )
  })

  await scenario('error helpers classify not-found without leaking OS codes', async () => {
    const notFound = Object.assign(new Error('nope'), { code: 'ENOENT' })
    assert.equal(storageErrorCode(notFound), 'ENOENT')
    assert.equal(isNotFoundError(notFound), true)
    assert.equal(isNotFoundError(new Error('other')), false)
    assert.equal(storageErrorCode(new StorageKernelError('X')), 'X')
  })
} finally {
  await rm(root, { recursive: true, force: true })
}

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
