/**
 * File-backed work-unit-environment store.
 *
 * A directory per environment, addressed by a digest of `namespaceId:environmentId`
 * so the raw environment id never appears as a path segment. Each environment
 * holds an atomic `environment.json` snapshot, an append-only `events.jsonl`
 * journal and a `pending.json` write-ahead marker that makes a crash between the
 * journal append and the snapshot rename recoverable.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/work-unit-environment-store.mjs`
 * is a stateless compatibility facade re-exporting from that bundle.
 *
 * Persistence shape (`environment.json`, `events.jsonl`, `pending.json` field
 * names, revision numbering and hashes) is part of the on-disk contract and must
 * not change.
 */

import { createHash, randomBytes } from 'node:crypto'
import { appendFile, lstat, mkdir, open, readFile, readdir, realpath, rename, rm } from 'node:fs/promises'
import { dirname, isAbsolute, join, relative, sep } from 'node:path'
import {
  validateNamespaceId,
  validateWorkUnitEnvironment,
  type ValidationFailure,
  type WorkUnitEnvironment,
  type WorkUnitEnvironmentState,
} from '../../domain/environment/work-unit-environment.js'

/** Machine-readable store error codes. */
export const ENVIRONMENT_STORE_ERROR_CODES = Object.freeze({
  INVALID_DATA_ROOT: 'INVALID_DATA_ROOT',
  INVALID_ENVIRONMENT: 'INVALID_ENVIRONMENT',
  INVALID_NAMESPACE: 'INVALID_NAMESPACE',
  NOT_FOUND: 'NOT_FOUND',
  REVISION_CONFLICT: 'REVISION_CONFLICT',
  INVALID_TRANSITION: 'INVALID_TRANSITION',
  CORRUPT_STORAGE: 'CORRUPT_STORAGE',
} as const)

/** One of the machine-readable store error codes. */
export type EnvironmentStoreErrorCode =
  (typeof ENVIRONMENT_STORE_ERROR_CODES)[keyof typeof ENVIRONMENT_STORE_ERROR_CODES]

/** The three files an environment directory holds, plus the directory itself. */
export interface WorkUnitEnvironmentPaths {
  directory: string
  snapshot: string
  events: string
  pending: string
}

/** A durable snapshot: the revision, its canonical hash and the descriptor. */
export interface EnvironmentSnapshot {
  revision: number
  environmentHash: string
  environment: WorkUnitEnvironment
}

/** A store failure carrying only a machine code. */
export interface StoreFailure {
  ok: false
  error: { code: string }
}

/** Result of a mutation that either wrote a snapshot or reported a failure. */
export type StoreWriteResult = { ok: true; changed: boolean; snapshot: EnvironmentSnapshot } | StoreFailure

/** Injected fault seam, called synchronously between persistence steps. */
export type EnvironmentStoreFault = (seam: string) => Promise<void> | void

/** Options accepted by `WorkUnitEnvironmentStore`. */
export interface WorkUnitEnvironmentStoreOptions {
  fault?: EnvironmentStoreFault
}

/** Failure thrown when a store invariant is violated or storage is corrupt. */
export class WorkUnitEnvironmentStoreError extends Error {
  readonly code: EnvironmentStoreErrorCode
  readonly details: Record<string, unknown>

  constructor(code: EnvironmentStoreErrorCode, details: Record<string, unknown> = {}, cause?: unknown) {
    super(code, cause ? { cause } : undefined)
    this.code = code
    this.details = details
  }
}

const digest = (value: string): string => createHash('sha256').update(value).digest('hex')
const snapshotHash = (e: WorkUnitEnvironment): string => digest(JSON.stringify(e, Object.keys(e).sort()))
const contained = (root: string, path: string): boolean => {
  const r = relative(root, path)
  return r !== '' && !r.startsWith(`..${sep}`) && r !== '..' && !isAbsolute(r)
}
async function syncDir(p: string): Promise<void> {
  const h = await open(p, 'r')
  try {
    await h.sync()
  } finally {
    await h.close()
  }
}
async function atomic(p: string, v: unknown): Promise<void> {
  await mkdir(dirname(p), { recursive: true })
  const t = `${p}.tmp-${process.pid}-${randomBytes(6).toString('hex')}`
  const h = await open(t, 'wx', 0o600)
  try {
    await h.writeFile(`${JSON.stringify(v)}\n`)
    await h.sync()
  } finally {
    await h.close()
  }
  await rename(t, p)
  await syncDir(dirname(p))
}
async function append(p: string, v: unknown): Promise<void> {
  await appendFile(p, `${JSON.stringify(v)}\n`, { encoding: 'utf8', mode: 0o600 })
  const h = await open(p, 'r')
  try {
    await h.sync()
  } finally {
    await h.close()
  }
}

/** A file-backed, lock-serialized store of work-unit environments. */
export class WorkUnitEnvironmentStore {
  readonly dataRoot: string
  readonly fault: EnvironmentStoreFault
  private readonly locks: Map<string, Promise<unknown>>
  private root: string | null

  constructor(dataRoot: string, { fault = async () => {} }: WorkUnitEnvironmentStoreOptions = {}) {
    if (typeof dataRoot !== 'string' || !isAbsolute(dataRoot))
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.INVALID_DATA_ROOT)
    this.dataRoot = dataRoot
    this.fault = fault
    this.locks = new Map()
    this.root = null
  }

  async initialize(): Promise<void> {
    await mkdir(join(this.dataRoot, 'environments'), { recursive: true })
    this.root = await realpath(join(this.dataRoot, 'environments'))
  }

  private async _safeDirectory(path: string, { missing = true }: { missing?: boolean } = {}): Promise<boolean> {
    let stat
    try {
      stat = await lstat(path)
    } catch (error) {
      if ((error as NodeJS.ErrnoException)?.code === 'ENOENT' && missing) return false
      throw new WorkUnitEnvironmentStoreError(
        ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE,
        { artifact: 'path' },
        error
      )
    }
    if (stat.isSymbolicLink() || !stat.isDirectory())
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, {
        artifact: 'unsafe_path',
      })
    const canonical = await realpath(path)
    if (path !== this.root && !contained(this.root as string, canonical))
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, {
        artifact: 'path_escape',
      })
    return true
  }

  private async _guard(
    p: WorkUnitEnvironmentPaths,
    { environmentMayBeMissing = true }: { environmentMayBeMissing?: boolean } = {}
  ): Promise<void> {
    await this._safeDirectory(this.root as string, { missing: false })
    const namespaceDirectory = dirname(p.directory)
    const namespaceExists = await this._safeDirectory(namespaceDirectory, { missing: true })
    if (!namespaceExists) return
    await this._safeDirectory(p.directory, { missing: environmentMayBeMissing })
  }

  private _namespace(ns: string): void {
    if (!validateNamespaceId(ns).ok)
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.INVALID_NAMESPACE)
  }

  paths(ns: string, id: string): WorkUnitEnvironmentPaths {
    this._namespace(ns)
    if (!this.root || typeof id !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(id))
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.INVALID_ENVIRONMENT)
    const directory = join(this.root, ns, digest(`${ns}:${id}`))
    if (!contained(this.root, directory))
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.INVALID_ENVIRONMENT)
    return {
      directory,
      snapshot: join(directory, 'environment.json'),
      events: join(directory, 'events.jsonl'),
      pending: join(directory, 'pending.json'),
    }
  }

  private _locked<T>(ns: string, id: string, fn: () => Promise<T>): Promise<T> {
    this._namespace(ns)
    const k = `${ns}\0${id}`
    const p = this.locks.get(k) ?? Promise.resolve()
    const o = p.then(fn)
    const t = o.catch(() => {})
    this.locks.set(k, t)
    return o.finally(() => {
      if (this.locks.get(k) === t) this.locks.delete(k)
    })
  }

  private async _json<T = unknown>(p: string, missing: T | null = null): Promise<T | null> {
    try {
      return JSON.parse(await readFile(p, 'utf8')) as T
    } catch (e) {
      if ((e as NodeJS.ErrnoException)?.code === 'ENOENT') return missing
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, {}, e)
    }
  }

  private async _recover(p: WorkUnitEnvironmentPaths, ns: string, id: string): Promise<void> {
    const q = await this._json<EnvironmentSnapshot>(p.pending)
    if (!q) return
    const valid =
      q &&
      Number.isSafeInteger(q.revision) &&
      q.revision > 0 &&
      q.environment?.namespaceId === ns &&
      q.environment?.environmentId === id &&
      validateWorkUnitEnvironment(q.environment).ok &&
      q.environmentHash === snapshotHash(q.environment)
    if (!valid)
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, { artifact: 'pending' })
    let facts
    try {
      facts = (await readFile(p.events, 'utf8'))
        .split('\n')
        .filter(Boolean)
        .map((line) => JSON.parse(line))
    } catch (e) {
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, { artifact: 'journal' }, e)
    }
    if (
      !facts.some((f) => f.revision === q.revision && f.environmentId === id && f.environmentHash === q.environmentHash)
    )
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, {
        artifact: 'recovery_binding',
      })
    await atomic(p.snapshot, q)
    await rm(p.pending)
    await syncDir(p.directory)
  }

  async read(ns: string, id: string): Promise<EnvironmentSnapshot | null> {
    this._namespace(ns)
    const p = this.paths(ns, id)
    await this._guard(p)
    await this._recover(p, ns, id)
    const s = await this._json<EnvironmentSnapshot>(p.snapshot)
    if (!s) return null
    if (
      !Number.isSafeInteger(s.revision) ||
      s.environmentHash !== snapshotHash(s.environment) ||
      !validateWorkUnitEnvironment(s.environment).ok
    )
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE)
    return s
  }

  async list(
    ns: string,
    { states }: { states?: readonly WorkUnitEnvironmentState[] } = {}
  ): Promise<EnvironmentSnapshot[]> {
    this._namespace(ns)
    let es
    const root = join(this.root as string, ns)
    const probe = this.paths(ns, 'list-probe')
    await this._guard(probe)
    try {
      es = await readdir(root, { withFileTypes: true })
    } catch (e) {
      if ((e as NodeJS.ErrnoException)?.code === 'ENOENT') return []
      throw e
    }
    const out: EnvironmentSnapshot[] = []
    for (const x of es) {
      const d = join(root, x.name)
      const p: WorkUnitEnvironmentPaths = {
        directory: d,
        snapshot: join(d, 'environment.json'),
        events: join(d, 'events.jsonl'),
        pending: join(d, 'pending.json'),
      }
      await this._guard(p, { environmentMayBeMissing: false })
      const pending = await this._json<EnvironmentSnapshot>(p.pending)
      if (pending) await this._recover(p, ns, pending.environment?.environmentId as string)
      const s = await this._json<EnvironmentSnapshot>(p.snapshot)
      if (s && s.environment.namespaceId === ns && (!states || states.includes(s.environment.lifecycleState)))
        out.push(s)
    }
    return out
  }

  async reserve(e: unknown): Promise<StoreWriteResult | ValidationFailure> {
    const v = validateWorkUnitEnvironment(e)
    if (!v.ok) return v
    this._namespace(v.environment.namespaceId)
    return this._locked(v.environment.namespaceId, v.environment.environmentId, async () => {
      const c = await this.read(v.environment.namespaceId, v.environment.environmentId)
      if (c)
        return JSON.stringify(c.environment) === JSON.stringify(v.environment)
          ? { ok: true, changed: false, snapshot: c }
          : { ok: false, error: { code: ENVIRONMENT_STORE_ERROR_CODES.INVALID_TRANSITION } }
      return this._write(null, v.environment, 'provisioning_reserved')
    })
  }

  async transition(
    ns: string,
    id: string,
    next: WorkUnitEnvironment,
    { expectedRevision, errorCode }: { expectedRevision?: number; errorCode?: string } = {}
  ): Promise<StoreWriteResult | ValidationFailure> {
    this._namespace(ns)
    return this._locked(ns, id, async () => {
      const c = await this.read(ns, id)
      if (!c) return { ok: false as const, error: { code: ENVIRONMENT_STORE_ERROR_CODES.NOT_FOUND } }
      if (expectedRevision !== undefined && expectedRevision !== c.revision)
        return { ok: false as const, error: { code: ENVIRONMENT_STORE_ERROR_CODES.REVISION_CONFLICT } }
      if (JSON.stringify(c.environment) === JSON.stringify(next)) return { ok: true, changed: false, snapshot: c }
      for (const field of [
        'schemaVersion',
        'environmentId',
        'workUnitId',
        'workflowId',
        'namespaceId',
        'repoRoot',
        'integrationBranch',
        'branch',
        'worktreePath',
        'baseCommit',
        'createdAt',
        'createdBy',
      ] as const)
        if (c.environment[field] !== next[field])
          return { ok: false as const, error: { code: ENVIRONMENT_STORE_ERROR_CODES.INVALID_TRANSITION } }
      if (c.environment.parentCaseId && next.parentCaseId !== c.environment.parentCaseId)
        return { ok: false as const, error: { code: ENVIRONMENT_STORE_ERROR_CODES.INVALID_TRANSITION } }
      const f = c.environment.lifecycleState
      const t = next.lifecycleState
      const allowed =
        (f === 'provisioning' && ['provisioning', 'active', 'error'].includes(t)) ||
        (f === 'active' && ['completed', 'abandoned', 'error'].includes(t)) ||
        (['completed', 'abandoned', 'error'].includes(f) && t === 'removed')
      if (!allowed) return { ok: false as const, error: { code: ENVIRONMENT_STORE_ERROR_CODES.INVALID_TRANSITION } }
      const v = validateWorkUnitEnvironment(next)
      if (!v.ok) return v
      return this._write(
        c,
        v.environment,
        t === 'active'
          ? 'parent_case_bound'
          : t === 'removed'
            ? 'environment_removed'
            : f === t
              ? 'environment_provisioned'
              : 'environment_state_changed',
        errorCode
      )
    })
  }

  private async _write(
    c: EnvironmentSnapshot | null,
    e: WorkUnitEnvironment,
    kind: string,
    errorCode?: string
  ): Promise<StoreWriteResult> {
    const p = this.paths(e.namespaceId, e.environmentId)
    await this._guard(p)
    const revision = (c?.revision ?? 0) + 1
    const environmentHash = snapshotHash(e)
    const s: EnvironmentSnapshot = { revision, environmentHash, environment: e }
    await mkdir(p.directory, { recursive: true })
    await this._guard(p, { environmentMayBeMissing: false })
    await atomic(p.pending, s)
    await this.fault('after-pending')
    await append(p.events, {
      kind,
      revision,
      environmentId: e.environmentId,
      environmentHash,
      lifecycleState: e.lifecycleState,
      timestamp: new Date().toISOString(),
      ...(errorCode ? { errorCode } : {}),
    })
    await this.fault('after-journal')
    await atomic(p.snapshot, s)
    await this.fault('after-snapshot')
    await rm(p.pending, { force: true })
    return { ok: true, changed: true, snapshot: s }
  }
}
