/**
 * Oracle process execution, bounded output capture and classification.
 *
 * Le verdict est `exitCode === 0`, rien d'autre. La classification distingue un
 * échec de produit (code non nul) d'un succès vide (cache, rien exécuté) et d'une
 * panne d'infrastructure (timeout, spawn error) — mais ne décide jamais à partir
 * du contenu de la sortie.
 *
 * Application layer: this module owns process execution (`node:child_process`),
 * filesystem reads (`node:fs`) and content hashing (`node:crypto`). The pure
 * counting and snapshot diff live in `domain/oracle/oracle.ts`.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/oracle.mjs` and
 * `factory/lib/oracle-executor.mjs` are stateless compatibility facades
 * re-exporting from that bundle.
 */

import { spawn, spawnSync, type ChildProcess, type SpawnOptions } from 'node:child_process'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { realpath } from 'node:fs/promises'
import { isAbsolute, join } from 'node:path'
import {
  countTaskOutcomes as defaultCountTaskOutcomes,
  diffSnapshots,
  type OracleSnapshot,
  type OracleSnapshotDelta,
  type TaskOutcomes,
} from '../../domain/oracle/oracle.js'

const MAX_OUTPUT_CHARS = 100_000
const LIMIT = 16_384

/** Result of a shell command run to completion (or timeout). */
export interface RunCommandResult {
  exitCode: number
  stdout: string
  stderr: string
  durationMs: number
  timedOut: boolean
}

/** Options accepted by `runCommand`. */
export interface RunCommandOptions {
  cwd?: string
  timeoutMs?: number
}

/** A bounded excerpt of captured process output. */
export interface BoundedOutput {
  excerpt: string
  truncated: boolean
}

/** A coarse classification of an oracle execution. Never a content verdict. */
export interface OracleExecutionClassification {
  classification: 'CLEAN' | 'PRODUCT_REGRESSION' | 'EMPTY_SUCCESS' | 'ORACLE_INFRASTRUCTURE'
  outcome: 'pass' | 'fail' | 'indeterminate'
}

/** The observation `classifyOracleExecution` needs. */
export interface OracleExecutionObservation {
  exitCode: number | null
  signal: NodeJS.Signals | null
  timedOut: boolean
  spawnError: string | null
  counts: { executed: number }
}

/** Full oracle execution result, including classification. */
export interface OracleExecutionResult extends OracleExecutionClassification {
  exitCode: number | null
  signal: NodeJS.Signals | null
  timedOut: boolean
  durationMs: number
  spawnError: string | null
  counts: TaskOutcomes
  stdout: BoundedOutput
  stderr: BoundedOutput
}

/** Signature of the injected process launcher (defaults to `child_process.spawn`). */
export type OracleSpawnImpl = (command: string, args: readonly string[], options: SpawnOptions) => ChildProcess

/** Oracle definition subset used by execution. */
export interface OracleExecutionDefinition {
  argv: readonly string[]
  timeoutMs: number
  success: { requireWork: boolean }
}

/** Options accepted by `executeOracle`. */
export interface ExecuteOracleOptions {
  repoRoot: string
  countTaskOutcomes: (output: string) => TaskOutcomes
  spawnImpl?: OracleSpawnImpl
  environment?: NodeJS.ProcessEnv
}

/** An oracle artifact: the bounded raw output and its content hash. */
export interface OracleArtifact {
  raw: string
  hash: string
}

/** Tronque une chaîne à `MAX_OUTPUT_CHARS` caractères. */
function truncate(s: string): string {
  if (s.length <= MAX_OUTPUT_CHARS) return s
  return s.slice(0, MAX_OUTPUT_CHARS) + `\n[... tronqué à ${MAX_OUTPUT_CHARS} caractères]`
}

/**
 * Exécute une commande shell et retourne son résultat. Le verdict est
 * `exitCode === 0`. Aucune interprétation du contenu de stdout/stderr.
 */
export function runCommand(command: string, { cwd, timeoutMs }: RunCommandOptions = {}): RunCommandResult {
  const start = Date.now()

  const result = spawnSync(command, {
    shell: true,
    ...(cwd === undefined ? {} : { cwd }),
    ...(timeoutMs === undefined ? {} : { timeout: timeoutMs }),
    encoding: 'utf8',
    maxBuffer: 200 * 1024 * 1024, // 200 MB pour éviter les troncatures internes
  })

  const durationMs = Date.now() - start
  const errorCode = (result.error as NodeJS.ErrnoException | undefined)?.code
  const timedOut = result.signal === 'SIGTERM' || errorCode === 'ETIMEDOUT'

  return {
    exitCode: timedOut ? -1 : (result.status ?? -1),
    stdout: truncate(result.stdout ?? ''),
    stderr: truncate(result.stderr ?? ''),
    durationMs,
    timedOut,
  }
}

/**
 * Empreinte d'un fichier : condensat SHA-256 de son contenu. Employée pour les
 * fichiers trackés comme non trackés — une seule mesure, aucune hypothèse sur la
 * forme du changement.
 */
function contentFingerprint(cwd: string, relPath: string): string {
  try {
    return createHash('sha256')
      .update(readFileSync(join(cwd, relPath)))
      .digest('hex')
  } catch {
    // Fichier disparu ou illisible entre le listing et la lecture.
    // Sentinelle distincte de tout condensat : une disparition est un changement.
    return 'unreadable'
  }
}

/**
 * Prend un snapshot de l'état Git courant. Git ne sert qu'à établir la LISTE des
 * fichiers à surveiller ; la détection du changement vient du contenu.
 */
export function snapshotDiff(cwd: string): OracleSnapshot {
  const diffResult = runCommand('git diff HEAD --name-only', { cwd })
  const untrackedResult = runCommand('git ls-files --others --exclude-standard', { cwd })

  const modified = new Map<string, string>()
  for (const path of diffResult.stdout.split('\n').filter(Boolean)) {
    modified.set(path, contentFingerprint(cwd, path))
  }

  const untracked = new Map<string, string>()
  for (const path of untrackedResult.stdout.split('\n').filter(Boolean)) {
    untracked.set(path, contentFingerprint(cwd, path))
  }

  return { modified, untracked }
}

/**
 * Retourne les chemins dont le CONTENU a changé depuis un snapshot précédent.
 * La comparaison pure vit dans `domain/oracle/oracle.ts` (`diffSnapshots`).
 */
export function diffSince(before: OracleSnapshot, cwd: string): OracleSnapshotDelta {
  return diffSnapshots(before, snapshotDiff(cwd))
}

/**
 * Classifie une exécution oracle. L'ordre des tests compte : une panne
 * d'infrastructure prime sur un code de sortie non nul, qui prime sur un succès
 * vide.
 */
export function classifyOracleExecution(
  definition: OracleExecutionDefinition,
  result: OracleExecutionObservation
): OracleExecutionClassification {
  if (result.spawnError || result.timedOut || result.signal)
    return { classification: 'ORACLE_INFRASTRUCTURE', outcome: 'indeterminate' }
  if (result.exitCode !== 0) return { classification: 'PRODUCT_REGRESSION', outcome: 'fail' }
  if (definition.success.requireWork && result.counts.executed === 0)
    return { classification: 'EMPTY_SUCCESS', outcome: 'indeterminate' }
  return { classification: 'CLEAN', outcome: 'pass' }
}

/** Résout la racine réelle du dépôt, en refusant tout chemin non absolu. */
export async function validateOracleRoot(repoRoot: unknown): Promise<string> {
  if (typeof repoRoot !== 'string' || !isAbsolute(repoRoot))
    throw Object.assign(new Error('INVALID_ORACLE_ROOT'), { code: 'INVALID_ORACLE_ROOT' })
  return realpath(repoRoot)
}

/** Identité stable d'une racine de dépôt (condensat de son chemin). */
export function oracleRootIdentity(repoRoot: string): string {
  return `sha256:${createHash('sha256').update(repoRoot).digest('hex')}`
}

/** Environnement minimal transmis à un process oracle (aucun secret propagé). */
function processEnvironment(source: NodeJS.ProcessEnv = process.env): Record<string, string> {
  const env: Record<string, string> = {}
  for (const key of ['PATH', 'HOME', 'TMPDIR', 'TMP', 'TEMP', 'SystemRoot', 'WINDIR', 'PATHEXT'])
    if (typeof source[key] === 'string') env[key] = source[key] as string
  return env
}

function bounded(chunks: Buffer[]): BoundedOutput {
  const value = Buffer.concat(chunks).toString('utf8')
  return { excerpt: value.slice(0, LIMIT), truncated: value.length > LIMIT }
}

/**
 * Exécute un oracle hors shell, capture une sortie bornée et classifie le
 * résultat. Le process est détaché sur les plateformes non Windows afin de
 * pouvoir tuer tout le groupe de process en cas de timeout.
 */
export function executeOracle(
  definition: OracleExecutionDefinition,
  {
    repoRoot,
    countTaskOutcomes = defaultCountTaskOutcomes,
    spawnImpl = spawn as OracleSpawnImpl,
    environment = process.env,
  }: ExecuteOracleOptions
): Promise<OracleExecutionResult> {
  return new Promise<OracleExecutionResult>((resolve) => {
    const started = Date.now()
    const stdout: Buffer[] = []
    const stderr: Buffer[] = []
    let timedOut = false
    let spawnError: string | null = null
    let settled = false

    const child = spawnImpl(definition.argv[0] ?? '', definition.argv.slice(1), {
      cwd: repoRoot,
      env: processEnvironment(environment),
      stdio: ['ignore', 'pipe', 'pipe'],
      shell: false,
      detached: process.platform !== 'win32',
    })

    child.stdout?.on('data', (x: Buffer) => stdout.push(Buffer.from(x)))
    child.stderr?.on('data', (x: Buffer) => stderr.push(Buffer.from(x)))
    child.on('error', (e: Error) => {
      spawnError = (e as NodeJS.ErrnoException).code ?? 'SPAWN_FAILURE'
    })

    const timer = setTimeout(() => {
      timedOut = true
      try {
        if (process.platform === 'win32' || child.pid === undefined) child.kill('SIGKILL')
        else process.kill(-child.pid, 'SIGKILL')
      } catch {
        try {
          child.kill('SIGKILL')
        } catch {
          // Le process est déjà terminé.
        }
      }
    }, definition.timeoutMs)

    child.on('close', (code: number | null, signal: NodeJS.Signals | null) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      const out = bounded(stdout)
      const err = bounded(stderr)
      const counts = countTaskOutcomes(`${out.excerpt}\n${err.excerpt}`)
      const base: {
        exitCode: number | null
        signal: NodeJS.Signals | null
        timedOut: boolean
        durationMs: number
        spawnError: string | null
        counts: TaskOutcomes
        stdout: BoundedOutput
        stderr: BoundedOutput
      } = {
        exitCode: code,
        signal,
        timedOut,
        durationMs: Date.now() - started,
        spawnError,
        counts,
        stdout: out,
        stderr: err,
      }
      resolve({ ...base, ...classifyOracleExecution(definition, base) })
    })
  })
}

/** Construit l'artefact diagnostic borné d'un résultat d'oracle. */
export function oracleArtifact(result: { stdout: BoundedOutput; stderr: BoundedOutput }): OracleArtifact {
  const raw = JSON.stringify({ stdout: result.stdout.excerpt, stderr: result.stderr.excerpt })
  return { raw, hash: `sha256:${createHash('sha256').update(raw).digest('hex')}` }
}
