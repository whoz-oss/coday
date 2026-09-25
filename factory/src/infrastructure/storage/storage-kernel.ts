import { createHash, randomBytes } from 'node:crypto'
import { appendFile, mkdir, open, readFile, rename, rm } from 'node:fs/promises'
import { dirname, join } from 'node:path'

/**
 * Common durable-storage kernel of the Factory persistence layer.
 *
 * This module owns the *physical* guarantees shared by every filesystem
 * repository adapter: atomic publication, durable append, `fsync` on files and
 * parent directories, canonical hashing, in-process keyed serialization,
 * process-level data-root locking, pending/recovery primitives and format
 * versioning metadata.
 *
 * Design constraints (see `factory/ARCHITECTURE.md`):
 *   - it only depends on `node:fs`, `node:path` and `node:crypto`;
 *   - it never imports a repository adapter, so it can be consumed by both the
 *     TypeScript adapters (bundled) and the legacy `.mjs` stores (runtime
 *     authority during the .mjs/.ts coexistence);
 *   - every primitive reproduces byte-for-byte the on-disk behaviour of the
 *     stores it replaces, so migrating a store onto the kernel is a no-op on
 *     file formats, paths and error semantics.
 */

/** Monotonic format marker attached to persisted metadata records. */
export const STORAGE_FORMAT_VERSION = 1

export const STORAGE_KERNEL_ERROR_CODES = Object.freeze({
  INVALID_PATH: 'INVALID_PATH',
  LOCK_HELD: 'LOCK_HELD',
  READ_FAILED: 'READ_FAILED',
  WRITE_FAILED: 'WRITE_FAILED',
  CORRUPT_RECORD: 'CORRUPT_RECORD',
  UNSUPPORTED_FORMAT_VERSION: 'UNSUPPORTED_FORMAT_VERSION',
} as const)

/** Error raised by kernel primitives; never leaks an OS error code as its own. */
export class StorageKernelError extends Error {
  readonly code: string
  readonly details: Record<string, unknown>

  constructor(code: string, details: Record<string, unknown> = {}, cause?: unknown) {
    super(code, cause === undefined ? undefined : { cause: cause as Error })
    this.name = 'StorageKernelError'
    this.code = code
    this.details = details
  }
}

/** Returns the OS-level `code` of an unknown caught value, if any. */
export function storageErrorCode(error: unknown): string | undefined {
  const code = (error as { code?: unknown } | null | undefined)?.code
  return typeof code === 'string' ? code : undefined
}

/** True when an unknown caught value is a "file or directory not found" error. */
export function isNotFoundError(error: unknown): boolean {
  return storageErrorCode(error) === 'ENOENT'
}

/** Wraps an unknown failure into a kernel error unless it already is one. */
export function wrapStorageError(
  code: string,
  details: Record<string, unknown> = {}
): (cause: unknown) => StorageKernelError {
  return (cause: unknown) =>
    cause instanceof StorageKernelError ? cause : new StorageKernelError(code, details, cause)
}

// --------------------------------------------------------------------------
// Durability
// --------------------------------------------------------------------------

/** `fsync`s a directory descriptor so a rename/create inside it is durable. */
export async function syncDirectory(directoryPath: string): Promise<void> {
  const directory = await open(directoryPath, 'r')
  try {
    await directory.sync()
  } finally {
    await directory.close()
  }
}

/** Builds the collision-resistant, same-directory temporary file name used for atomic publication. */
export function atomicTemporaryPath(filePath: string): string {
  return `${filePath}.tmp-${process.pid}-${randomBytes(6).toString('hex')}`
}

/**
 * Atomically publishes `value` as canonical-lines JSON at `filePath`:
 * write to a sibling temp file, `fsync` it, rename over the destination, then
 * `fsync` the parent directory.
 */
export async function atomicWriteJson(filePath: string, value: unknown): Promise<void> {
  await mkdir(dirname(filePath), { recursive: true })
  const temporary = atomicTemporaryPath(filePath)
  const handle = await open(temporary, 'wx', 0o600)
  try {
    await handle.writeFile(`${JSON.stringify(value)}\n`, 'utf8')
    await handle.sync()
  } finally {
    await handle.close()
  }
  await rename(temporary, filePath)
  await syncDirectory(dirname(filePath))
}

export interface AppendDurableOptions {
  /** When true, creates the parent directory first (matches the JSONL journals). */
  ensureDirectory?: boolean
}

/** Appends one JSON line then `fsync`s the journal file handle. */
export async function appendDurableJson(
  filePath: string,
  value: unknown,
  options: AppendDurableOptions = {}
): Promise<void> {
  if (options.ensureDirectory) await mkdir(dirname(filePath), { recursive: true })
  await appendFile(filePath, `${JSON.stringify(value)}\n`, { encoding: 'utf8', mode: 0o600 })
  const handle = await open(filePath, 'r')
  try {
    await handle.sync()
  } finally {
    await handle.close()
  }
}

/**
 * Reads a JSONL file into an array of parsed records.
 *
 * Missing files yield `[]` (the append-only journals are created lazily);
 * malformed lines throw the raw `SyntaxError`, so callers keep mapping it to
 * their own `CORRUPT_*` error code.
 */
export async function readJsonLines<T = unknown>(filePath: string): Promise<T[]> {
  let text: string
  try {
    text = await readFile(filePath, 'utf8')
  } catch (error) {
    if (isNotFoundError(error)) return []
    throw error
  }
  return text
    .split('\n')
    .filter(Boolean)
    .map((line) => JSON.parse(line) as T)
}

// --------------------------------------------------------------------------
// Canonical hashing
// --------------------------------------------------------------------------

/** Recursively sorts object keys so structurally equal values serialize identically. */
export function canonicalize(value: unknown): unknown {
  if (Array.isArray(value)) return value.map((entry) => canonicalize(entry))
  if (value !== null && typeof value === 'object') {
    const record = value as Record<string, unknown>
    return Object.fromEntries(
      Object.keys(record)
        .sort()
        .map((key) => [key, canonicalize(record[key])])
    )
  }
  return value
}

/** Canonical JSON string of `value` (sorted keys, no whitespace surprises). */
export function canonicalJson(value: unknown): string {
  return JSON.stringify(canonicalize(value))
}

/** `sha256` hex digest of the canonical JSON form of `value`. */
export function computeCanonicalHash(value: unknown): string {
  return createHash('sha256').update(canonicalJson(value), 'utf8').digest('hex')
}

// --------------------------------------------------------------------------
// In-process locks
// --------------------------------------------------------------------------

/**
 * Promise-chain lock keyed by an arbitrary string.
 *
 * Mirrors the historical per-store `_locked` helper exactly: the action runs
 * once every prior action for the same key settles, and a failed action never
 * poisons the next one.
 */
export class KeyedLock {
  private readonly locks = new Map<string, Promise<unknown>>()

  run<T>(key: string, action: () => Promise<T> | T): Promise<T> {
    const prior = this.locks.get(key) ?? Promise.resolve()
    const operation = prior.then(() => action())
    const tail = operation.then(
      () => undefined,
      () => undefined
    )
    this.locks.set(key, tail)
    return operation.finally(() => {
      if (this.locks.get(key) === tail) this.locks.delete(key)
    })
  }

  /** Number of keys with an in-flight or queued action (diagnostics only). */
  get size(): number {
    return this.locks.size
  }
}

/** Creates an isolated in-memory keyed lock. */
export function createKeyedLock(): KeyedLock {
  return new KeyedLock()
}

// --------------------------------------------------------------------------
// Process-level data-root lock
// --------------------------------------------------------------------------

export const DEFAULT_PROCESS_LOCK_FILE = '.process.lock'

export interface ProcessDataRootLock {
  readonly path: string
  readonly pid: number
  /** Removes the lock file, releasing the root for a future process. */
  release(): Promise<void>
}

export interface AcquireProcessLockOptions {
  lockFileName?: string
}

/**
 * Acquires an exclusive process-level lock on a data root.
 *
 * Uses `open(path, 'wx')`, which fails atomically with `EEXIST` when another
 * live process already holds it. The returned handle must be released on
 * shutdown; `withProcessLock` does so automatically.
 */
export async function acquireProcessLock(
  dataRoot: string,
  options: AcquireProcessLockOptions = {}
): Promise<ProcessDataRootLock> {
  if (typeof dataRoot !== 'string' || dataRoot.length === 0)
    throw new StorageKernelError(STORAGE_KERNEL_ERROR_CODES.INVALID_PATH, { dataRoot })
  const lockPath = join(dataRoot, options.lockFileName ?? DEFAULT_PROCESS_LOCK_FILE)
  await mkdir(dataRoot, { recursive: true })
  let handle: Awaited<ReturnType<typeof open>>
  try {
    handle = await open(lockPath, 'wx', 0o600)
  } catch (error) {
    if (storageErrorCode(error) === 'EEXIST')
      throw new StorageKernelError(STORAGE_KERNEL_ERROR_CODES.LOCK_HELD, { path: lockPath }, error)
    throw error
  }
  try {
    await handle.writeFile(`${JSON.stringify({ pid: process.pid, acquiredAt: new Date().toISOString() })}\n`, 'utf8')
    await handle.sync()
  } catch (error) {
    await handle.close()
    await rm(lockPath, { force: true })
    throw error
  }
  let released = false
  return {
    path: lockPath,
    pid: process.pid,
    async release(): Promise<void> {
      if (released) return
      released = true
      try {
        await handle.close()
      } finally {
        await rm(lockPath, { force: true })
      }
    },
  }
}

/** Runs `action` while holding the data-root process lock, releasing it afterwards. */
export async function withProcessLock<T>(
  dataRoot: string,
  action: (lock: ProcessDataRootLock) => Promise<T> | T,
  options: AcquireProcessLockOptions = {}
): Promise<T> {
  const lock = await acquireProcessLock(dataRoot, options)
  try {
    return await action(lock)
  } finally {
    await lock.release()
  }
}

// --------------------------------------------------------------------------
// Format versioning metadata
// --------------------------------------------------------------------------

export interface VersionedRecord {
  formatVersion: number
  [key: string]: unknown
}

/** Returns a copy of `value` stamped with a format version. */
export function withFormatVersion<T extends Record<string, unknown>>(
  value: T,
  formatVersion: number = STORAGE_FORMAT_VERSION
): T & { formatVersion: number } {
  return { ...value, formatVersion }
}

/** Reads the `formatVersion` marker of an unknown record, or `null` when absent. */
export function readFormatVersion(value: unknown): number | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const raw = (value as Record<string, unknown>).formatVersion
  return typeof raw === 'number' && Number.isSafeInteger(raw) ? raw : null
}

/** Fails closed when a record carries an unsupported format version. */
export function assertSupportedFormatVersion(
  value: unknown,
  supported: number = STORAGE_FORMAT_VERSION
): number | null {
  const version = readFormatVersion(value)
  if (version !== null && version > supported)
    throw new StorageKernelError(STORAGE_KERNEL_ERROR_CODES.UNSUPPORTED_FORMAT_VERSION, { version, supported })
  return version
}
