/**
 * Pure oracle-definition domain: schema validation, canonical serialization,
 * content hashing and the definition registry logic.
 *
 * A definition identifies a fixed executable contract — never a second command
 * language: `argv` is executed without a shell, so shell interpreters and
 * shell-evaluation flags are rejected.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/oracle-definition.mjs`
 * is a stateless compatibility facade re-exporting from that bundle.
 *
 * Domain purity: only `node:crypto` is allowed; no `node:fs`, HTTP client,
 * AgentOS or Git CLI. The registry takes a `OracleDefinitionSource` port so the
 * filesystem adapter can live in `application/oracle/`.
 */

import { createHash } from 'node:crypto'

/** A fixed executable contract with a single success rule: the exit code. */
export interface OracleSuccessCondition {
  rule: 'exit-code'
  requireWork: boolean
}

/** Where the oracle applies: workflow types and step ids. */
export interface OracleApplicableCondition {
  workflowTypes: readonly string[]
  stepIds: readonly string[]
}

/** A validated, frozen oracle definition. */
export interface OracleDefinition {
  schemaVersion: '1'
  id: string
  version: string
  domain: string
  argv: readonly string[]
  cwd: 'repo-root'
  timeoutMs: number
  success: OracleSuccessCondition
  applicable: OracleApplicableCondition
}

/**
 * Filesystem-shaped port consumed by the pure registry. The concrete adapter
 * (Node `fs`) lives in `application/oracle/oracle-definition-registry.ts`.
 */
export interface OracleDefinitionSource {
  /** Returns the file names living in the definitions directory. */
  listFiles(): Promise<readonly string[]>
  /** Reads one file (by name) and returns its UTF-8 content. */
  readFile(fileName: string): Promise<string>
}

const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const VERSION = /^\d+\.\d+\.\d+$/
const FIELDS = new Set([
  'schemaVersion',
  'id',
  'version',
  'domain',
  'argv',
  'cwd',
  'timeoutMs',
  'success',
  'applicable',
])
const SUCCESS_FIELDS = new Set(['rule', 'requireWork'])
const APPLICABLE_FIELDS = new Set(['workflowTypes', 'stepIds'])
const SHELL_EXECUTABLES = new Set([
  'sh',
  'bash',
  'zsh',
  'dash',
  'ksh',
  'cmd',
  'cmd.exe',
  'powershell',
  'powershell.exe',
  'pwsh',
  'pwsh.exe',
])
const SHELL_FLAGS = new Set(['-c', '--command', '/c', '-command', '-encodedcommand'])

function invalid(): never {
  throw new Error('INVALID_ORACLE_DEFINITION')
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * Canonical JSON shape: object keys sorted recursively, arrays preserved in
 * order. Two definitions that differ only by key order hash identically.
 */
export function canonicalOracleDefinition(value: unknown): unknown {
  if (Array.isArray(value)) return value.map((entry) => canonicalOracleDefinition(entry))
  if (isRecord(value)) {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((key) => [key, canonicalOracleDefinition(value[key])])
    )
  }
  return value
}

/**
 * Validates a raw oracle definition, throwing `INVALID_ORACLE_DEFINITION` on any
 * violation, and returns a deeply frozen `OracleDefinition` on success.
 */
export function validateOracleDefinition(value: unknown): OracleDefinition {
  if (!isRecord(value)) invalid()
  const candidate = value as Record<string, unknown>
  if (Object.keys(candidate).some((key) => !FIELDS.has(key))) invalid()
  if (
    candidate.schemaVersion !== '1' ||
    !SAFE.test(String(candidate.id ?? '')) ||
    !VERSION.test(String(candidate.version ?? '')) ||
    !SAFE.test(String(candidate.domain ?? ''))
  )
    invalid()

  const argv = candidate.argv
  if (
    !Array.isArray(argv) ||
    argv.length === 0 ||
    argv.length > 32 ||
    argv.some((entry) => typeof entry !== 'string' || !entry || entry.length > 512 || /[\r\n\0]/.test(entry))
  )
    invalid()

  // argv is executed directly without a shell. Reject shell interpreters and
  // shell-evaluation flags anyway: deterministic oracles must identify a fixed
  // executable contract, not embed a second command language in their definition.
  const args = argv as string[]
  const executableRaw = args[0]
  if (executableRaw === undefined) invalid()
  const executable = executableRaw.replace(/\\/g, '/').split('/').at(-1)?.toLowerCase()
  if (
    executable === undefined ||
    SHELL_EXECUTABLES.has(executable) ||
    args.some((arg, index) => index > 0 && SHELL_FLAGS.has(arg.toLowerCase()))
  )
    invalid()

  if (
    candidate.cwd !== 'repo-root' ||
    !Number.isSafeInteger(candidate.timeoutMs) ||
    (candidate.timeoutMs as number) < 1 ||
    (candidate.timeoutMs as number) > 3_600_000
  )
    invalid()

  const success = candidate.success
  if (
    !isRecord(success) ||
    Object.keys(success).some((key) => !SUCCESS_FIELDS.has(key)) ||
    success.rule !== 'exit-code' ||
    typeof success.requireWork !== 'boolean'
  )
    invalid()

  const applicable = candidate.applicable
  if (
    !isRecord(applicable) ||
    Object.keys(applicable).some((key) => !APPLICABLE_FIELDS.has(key)) ||
    !Array.isArray(applicable.workflowTypes) ||
    applicable.workflowTypes.length === 0 ||
    applicable.workflowTypes.some((entry) => !SAFE.test(String(entry))) ||
    !Array.isArray(applicable.stepIds) ||
    applicable.stepIds.length === 0 ||
    applicable.stepIds.some((entry) => !SAFE.test(String(entry)))
  )
    invalid()

  return Object.freeze({
    schemaVersion: '1',
    id: candidate.id as string,
    version: candidate.version as string,
    domain: candidate.domain as string,
    argv: Object.freeze([...args]),
    cwd: 'repo-root',
    timeoutMs: candidate.timeoutMs as number,
    success: Object.freeze({ rule: 'exit-code', requireWork: success.requireWork as boolean }),
    applicable: Object.freeze({
      workflowTypes: Object.freeze([...(applicable.workflowTypes as string[])]),
      stepIds: Object.freeze([...(applicable.stepIds as string[])]),
    }),
  })
}

/** A stable content hash of a definition, insensitive to key order. */
export function hashOracleDefinition(definition: unknown): string {
  return `sha256:${createHash('sha256')
    .update(JSON.stringify(canonicalOracleDefinition(definition)))
    .digest('hex')}`
}

/** The `id@version` identity encoded in a definition file name. */
function definitionIdentityFromFileName(fileName: string): string {
  const base = fileName.split(/[\\/]/).at(-1) ?? fileName
  return base.endsWith('.json') ? base.slice(0, -'.json'.length) : base
}

/**
 * Pure registry core over an injected `OracleDefinitionSource`. Validates every
 * file, enforces path/identity agreement and rejects duplicate ids.
 */
export class OracleDefinitionRegistryCore {
  protected items: Map<string, OracleDefinition>

  constructor(private readonly source: OracleDefinitionSource) {
    this.items = new Map()
  }

  async initialize(): Promise<this> {
    const files = [...(await this.source.listFiles())].filter((name) => name.endsWith('.json')).sort()
    const next = new Map<string, OracleDefinition>()
    for (const file of files) {
      const definition = validateOracleDefinition(JSON.parse(await this.source.readFile(file)))
      if (definitionIdentityFromFileName(file) !== `${definition.id}@${definition.version}`)
        throw new Error('ORACLE_PATH_IDENTITY_MISMATCH')
      if (next.has(definition.id)) throw new Error('DUPLICATE_ORACLE_ID')
      next.set(definition.id, definition)
    }
    this.items = next
    return this
  }

  get(id: string): OracleDefinition | null {
    return this.items.get(id) ?? null
  }
}
