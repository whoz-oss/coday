/**
 * Java detection for the managed JVM services (AgentOS and Factory need Java >= 25).
 * Returns a structured status (with an install hint) instead of only logging a debug line,
 * so the first-run wizard and the preflight can tell the user what to do.
 *
 * `JAVA_HOME` is the trap worth handling: it SHADOWS the PATH, so a machine with a perfectly good JDK 25 still fails
 * when the variable points at an old one. Telling that user to install Java is wrong and sends them in circles, so
 * when the configured JVM is not good enough we look for a usable one elsewhere (the PATH, then `/usr/libexec/java_home`
 * on macOS) and the hint becomes "point JAVA_HOME at it" instead of "install it".
 */

import { execFileSync } from 'child_process'
import * as path from 'path'

/** Minimum Java major version required. The AgentOS/Factory toolchain targets Java 25. */
export const JAVA_MIN_VERSION = 25

/** A JDK good enough for Coday, found somewhere other than where `JAVA_HOME` points. */
export interface JavaAlternative {
  /** Its home, when it took a lookup to find; undefined when it is simply the `java` of the PATH. */
  home?: string
  version: string
}

export type JavaStatus =
  | { state: 'ok'; version: string; major: number }
  | { state: 'too-old'; version: string; major: number; hint: string; alternative?: JavaAlternative }
  | { state: 'missing'; hint: string; alternative?: JavaAlternative }

/** Path of the java binary: `$JAVA_HOME/bin/java` when set, `java` from the PATH otherwise. */
export function javaBinary(env: NodeJS.ProcessEnv = process.env): string {
  return env.JAVA_HOME ? path.join(env.JAVA_HOME, 'bin', 'java') : 'java'
}

/** First line of `java --version`: `openjdk 25 2025-09-16` or `openjdk 21.0.3 2024-04-16`. */
export function parseJavaVersionOutput(stdout: string): { major: number; line: string } | null {
  const match = stdout.match(/^\S+\s+(\d+)/)
  if (!match) return null
  return { major: parseInt(match[1] ?? '0', 10), line: stdout.split('\n')[0]?.trim() ?? `Java ${match[1]}` }
}

/** Nothing suitable is installed: the only way out is to install a JDK. */
export function javaInstallHint(platform: NodeJS.Platform = process.platform): string {
  const base = `Coday needs Java ${JAVA_MIN_VERSION} or newer.`
  if (platform === 'darwin') return `${base} Install it with: brew install --cask temurin@${JAVA_MIN_VERSION}`
  if (platform === 'win32')
    return `${base} Install it with: winget install EclipseAdoptium.Temurin.${JAVA_MIN_VERSION}.JDK`
  return `${base} Install it with SDKMAN (sdk install java ${JAVA_MIN_VERSION}-tem) or from https://adoptium.net/temurin/releases/?version=${JAVA_MIN_VERSION}`
}

/**
 * A suitable JDK IS installed, `JAVA_HOME` just points elsewhere. Nothing to install: the fix is one variable, and
 * the hint gives the exact command rather than naming the problem.
 */
export function javaHomeHint(
  alternative: JavaAlternative,
  javaHome: string,
  platform: NodeJS.Platform = process.platform
): string {
  const found = `JAVA_HOME points at ${javaHome}, which is not a Java ${JAVA_MIN_VERSION}, but ${alternative.version} is installed`
  if (alternative.home) {
    if (platform === 'win32') return `${found} at ${alternative.home}. Set JAVA_HOME to that directory.`
    return `${found}. Point JAVA_HOME at it: export JAVA_HOME=$(/usr/libexec/java_home -v${JAVA_MIN_VERSION})`
  }
  if (platform === 'win32') return `${found} on your PATH. Clear JAVA_HOME, or set it to that installation.`
  return `${found} on your PATH. Unset it (unset JAVA_HOME) or point it at that installation.`
}

/**
 * Home of an installed JDK of at least `JAVA_MIN_VERSION`, asked of the system. Only macOS answers this: it keeps
 * every JDK under /Library/Java and `/usr/libexec/java_home` picks the right one. Never throws.
 */
export function findJavaHome(
  platform: NodeJS.Platform = process.platform,
  run: (args: string[]) => string = (args) =>
    execFileSync('/usr/libexec/java_home', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
): string | undefined {
  if (platform !== 'darwin') return undefined
  try {
    return run(['-v', String(JAVA_MIN_VERSION)]).trim() || undefined
  } catch {
    return undefined
  }
}

/** Probe the local JVM. Never throws. */
export function inspectJava(
  run: (bin: string) => string = (bin) =>
    execFileSync(bin, ['--version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }),
  env: NodeJS.ProcessEnv = process.env,
  platform: NodeJS.Platform = process.platform,
  findHome: typeof findJavaHome = findJavaHome
): JavaStatus {
  const probe = (bin: string): { major: number; line: string } | null => {
    try {
      return parseJavaVersionOutput(run(bin))
    } catch {
      return null
    }
  }

  const configured = probe(javaBinary(env))
  if (configured && configured.major >= JAVA_MIN_VERSION)
    return { state: 'ok', version: configured.line, major: configured.major }

  // Only JAVA_HOME can hide a working JDK; without it we already probed the PATH.
  const javaHome = env.JAVA_HOME
  const alternative = javaHome ? findAlternative(probe, platform, findHome) : undefined
  const hint = alternative && javaHome ? javaHomeHint(alternative, javaHome, platform) : javaInstallHint(platform)
  const found = alternative ? { alternative } : {}

  if (!configured) return { state: 'missing', hint, ...found }
  return { state: 'too-old', version: configured.line, major: configured.major, hint, ...found }
}

/** A usable JDK that `JAVA_HOME` is hiding: the `java` of the PATH first, then whatever the system can name. */
function findAlternative(
  probe: (bin: string) => { major: number; line: string } | null,
  platform: NodeJS.Platform,
  findHome: typeof findJavaHome
): JavaAlternative | undefined {
  const onPath = probe('java')
  if (onPath && onPath.major >= JAVA_MIN_VERSION) return { version: onPath.line }

  const home = findHome(platform)
  if (!home) return undefined
  const inHome = probe(path.join(home, 'bin', 'java'))
  if (inHome && inHome.major >= JAVA_MIN_VERSION) return { home, version: inHome.line }
  return undefined
}
