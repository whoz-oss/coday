/**
 * `coday-server doctor`, pure part: turn what the machine says (Node, Java, the config directory, ripgrep, the ports a
 * start would take) into a short report, and say whether the start can succeed at all.
 *
 * Why a command of the bundled server rather than a few lines in the launcher: the checks already exist in TypeScript
 * (java.ts, ports.ts) and are unit-tested. `apps/web/index.js` only spawns this and relays the exit code, so there is
 * ONE implementation and nothing duplicated in plain JS.
 *
 * Why it runs before anything else: the Nx TUI is about to take the terminal, and a problem reported from inside a
 * pane scrolls away unseen (which is exactly how a wrong JAVA_HOME turned into an hour of guessing).
 *
 * No I/O here: `runDoctor` (commands.ts) gathers the facts, this module decides and renders.
 */

import { JavaStatus } from './java'

/** Minimum Node major version, the prerequisite the installation docs state. The server bundle targets it too. */
export const NODE_MIN_VERSION = 22

/**
 * Exit code of `doctor` when a check BLOCKS the start, and the only one the launcher stops on. Any other non-zero
 * code means the preflight itself failed (a crash, a bad option, a signal), which must never prevent a start:
 * apps/web/index.js warns and carries on. Kept distinct from 1, which is what an unhandled throw exits with.
 */
export const DOCTOR_BLOCKED_EXIT = 2

export type CheckState = 'ok' | 'warn' | 'blocked'

export interface Check {
  /** Short label, the left column of the report. */
  name: string
  state: CheckState
  /** What was found. */
  detail: string
  /** What to do about it; only when the state is not `ok`. */
  hint?: string
}

export interface DoctorReport {
  checks: Check[]
  /** At least one check is `blocked`: starting cannot work, the launcher stops here. */
  blocked: boolean
}

/** `<configDir>`: everything the runtime writes goes there, so it must exist and be writable. */
export interface ConfigDirFacts {
  path: string
  writable: boolean
  /** Why it is not writable (ENOENT, EACCES...). */
  error?: string
}

/**
 * The ports a start would take. `notes` are the lines `chooseStackPorts` logged (a moved block, the OS fallback):
 * they are already written for a user, so they are reported as they are rather than rebuilt here.
 */
export interface PortsFacts {
  /** Where the user would open Coday; undefined when the choice failed. */
  express?: number
  /** The block the stack would own; undefined when the ports would come from the OS. */
  base?: number
  notes: string[]
  /**
   * The choice failed and the start would fail the same way. The message comes from `chooseStackPorts` and already
   * says what to do, whatever the cause (an explicit port taken, two services landing on one port, a port the OS
   * refuses); the doctor does not guess a cause on top of it.
   */
  error?: string
}

/**
 * How much a missing Java actually costs, which is NOT the same everywhere (see orchestrator.ts `requireJava`):
 * with an external AgentOS and Factory no JVM is ever spawned, and on a first run the wizard polls every few
 * seconds while the user installs it. Blocking the start in those two cases would refuse what works today.
 */
export interface JavaNeed {
  /** A JVM service is actually managed by this runtime. */
  required: boolean
  /** The first-run wizard is on and will wait for Java, so its absence is a normal state rather than a blocker. */
  awaited: boolean
}

export interface DoctorFacts {
  /** `process.versions.node`. */
  node: string
  java: JavaStatus
  javaNeed: JavaNeed
  configDir: ConfigDirFacts
  /** First line of `rg --version`; undefined when ripgrep is not installed. */
  ripgrep?: string
  ports: PortsFacts
  platform?: NodeJS.Platform
}

/** Major of `22.11.0` / `v24.4.0`; undefined when the string is not a version. */
export function parseNodeMajor(version: string): number | undefined {
  const major = Number(version.replace(/^v/, '').split('.')[0])
  return Number.isInteger(major) && major > 0 ? major : undefined
}

export function nodeInstallHint(platform: NodeJS.Platform = process.platform): string {
  const base = `Coday needs Node ${NODE_MIN_VERSION} or newer.`
  if (platform === 'darwin') return `${base} Install it with: brew install node`
  if (platform === 'win32') return `${base} Install it with: winget install OpenJS.NodeJS.LTS`
  return `${base} Install it with nvm (nvm install ${NODE_MIN_VERSION}) or from https://nodejs.org/`
}

export function ripgrepInstallHint(platform: NodeJS.Platform = process.platform): string {
  if (platform === 'darwin') return 'Agents search files with it. Install it with: brew install ripgrep'
  if (platform === 'win32')
    return 'Agents search files with it. Install it with: winget install BurntSushi.ripgrep.MSVC'
  return 'Agents search files with it. Install it with your package manager (apt install ripgrep).'
}

/**
 * Node is already running us, so this never prevents the process from starting: it names the version in the report,
 * and blocks before the bundles (built for a recent toolchain) fail with a syntax error nobody can read.
 */
export function checkNode(version: string, platform?: NodeJS.Platform): Check {
  const major = parseNodeMajor(version)
  if (major === undefined) return { name: 'node', state: 'warn', detail: `unknown version (${version})` }
  if (major < NODE_MIN_VERSION)
    return { name: 'node', state: 'blocked', detail: `v${version}`, hint: nodeInstallHint(platform) }
  return { name: 'node', state: 'ok', detail: `v${version}` }
}

/**
 * AgentOS and the Factory are JVM services: without a recent enough Java, nothing starts. When a usable JDK exists
 * and only `JAVA_HOME` is wrong, the line says so — `not found` next to a hint naming an installed Java reads as a
 * contradiction, and that confusion is the whole point of the check.
 */
export function checkJava(java: JavaStatus, need: JavaNeed): Check {
  if (java.state === 'ok') return { name: 'java', state: 'ok', detail: java.version }
  if (!need.required)
    return { name: 'java', state: 'ok', detail: 'not needed, the JVM services are external or disabled' }

  const misplaced = java.alternative !== undefined
  const detail =
    java.state === 'too-old'
      ? `${java.version}${misplaced ? ' at JAVA_HOME' : ''}`
      : misplaced
        ? 'not where JAVA_HOME points'
        : 'not found'
  // The wizard is reachable without Java and explains the same thing, so stopping here would hide it.
  if (need.awaited) return { name: 'java', state: 'warn', detail, hint: `${java.hint} Coday starts and waits for it.` }
  return { name: 'java', state: 'blocked', detail, hint: java.hint }
}

export function checkConfigDir(facts: ConfigDirFacts): Check {
  if (facts.writable) return { name: 'config', state: 'ok', detail: facts.path }
  return {
    name: 'config',
    state: 'blocked',
    detail: `${facts.path} is not writable${facts.error ? ` (${facts.error})` : ''}`,
    hint: 'Coday writes its runtime, logs and service data there. Fix the permissions or pass --configDir <dir>.',
  }
}

/** Only a warning: Coday starts fine without ripgrep, the agents just lose their fast file search. */
export function checkRipgrep(version: string | undefined, platform?: NodeJS.Platform): Check {
  if (version) return { name: 'ripgrep', state: 'ok', detail: version }
  return { name: 'ripgrep', state: 'warn', detail: 'not found', hint: ripgrepInstallHint(platform) }
}

/**
 * Not a yes/no check: a packaged stack moves to the next free block on its own, so a taken 3000 is NOT a failure.
 * What matters is saying WHICH address to open — someone who keeps opening 3000 out of habit lands on the instance
 * that was already running and concludes nothing changed.
 */
export function checkPorts(ports: PortsFacts): Check {
  if (ports.error) return { name: 'ports', state: 'blocked', detail: ports.error }
  if (ports.express === undefined) return { name: 'ports', state: 'warn', detail: 'could not be determined' }
  // Every block is taken: the ports come from the OS, and the real start gets its own set. Announcing the one
  // probed here would name an address nothing ends up listening on.
  if (ports.base === undefined)
    return { name: 'ports', state: 'warn', detail: 'every block is taken, the OS assigns the ports at startup' }
  // The note already names the block and the address, so repeating them as a detail says the same thing twice.
  if (ports.notes.length > 0) return { name: 'ports', state: 'warn', detail: ports.notes.join(' ') }
  return { name: 'ports', state: 'ok', detail: `block ${ports.base}, open http://localhost:${ports.express}/` }
}

export function buildReport(facts: DoctorFacts): DoctorReport {
  const checks = [
    checkNode(facts.node, facts.platform),
    checkJava(facts.java, facts.javaNeed),
    checkConfigDir(facts.configDir),
    checkRipgrep(facts.ripgrep, facts.platform),
    checkPorts(facts.ports),
  ]
  return { checks, blocked: checks.some((check) => check.state === 'blocked') }
}

const MARK: Record<CheckState, string> = { ok: 'ok', warn: '!!', blocked: 'XX' }

/**
 * The report, aligned on the longest name. Always printed in full: six lines once per start cost nothing next to a
 * TUI that is about to redraw, and seeing `ok java openjdk 25` is precisely what was missing.
 */
export function renderReport(report: DoctorReport): string[] {
  const width = report.checks.reduce((max, check) => Math.max(max, check.name.length), 0)
  const indent = ' '.repeat(width + 8)
  const lines = report.checks.flatMap((check) => [
    `  ${MARK[check.state]}  ${check.name.padEnd(width)}  ${check.detail}`,
    ...(check.hint ? [`${indent}${check.hint}`] : []),
  ])
  return [
    'Coday preflight',
    ...lines,
    ...(report.blocked ? ['', 'Coday cannot start until the XX above is fixed (--skip-doctor to try anyway).'] : []),
  ]
}
