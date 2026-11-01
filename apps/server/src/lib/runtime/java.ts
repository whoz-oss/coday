/**
 * Java detection for the managed JVM services (AgentOS and Factory need Java >= 25).
 * Returns a structured status (with an install hint) instead of only logging a debug line,
 * so the first-run wizard can tell the user what to do.
 */

import { execFileSync } from 'child_process'
import * as path from 'path'

/** Minimum Java major version required. The AgentOS/Factory toolchain targets Java 25. */
export const JAVA_MIN_VERSION = 25

export type JavaStatus =
  | { state: 'ok'; version: string; major: number }
  | { state: 'too-old'; version: string; major: number; hint: string }
  | { state: 'missing'; hint: string }

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

export function javaInstallHint(platform: NodeJS.Platform = process.platform): string {
  const base = `Coday needs Java ${JAVA_MIN_VERSION} or newer.`
  if (platform === 'darwin') return `${base} Install it with: brew install --cask temurin@${JAVA_MIN_VERSION}`
  if (platform === 'win32')
    return `${base} Install it with: winget install EclipseAdoptium.Temurin.${JAVA_MIN_VERSION}.JDK`
  return `${base} Install it with SDKMAN (sdk install java ${JAVA_MIN_VERSION}-tem) or from https://adoptium.net/temurin/releases/?version=${JAVA_MIN_VERSION}`
}

/** Probe the local JVM. Never throws. */
export function inspectJava(
  run: (bin: string) => string = (bin) =>
    execFileSync(bin, ['--version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }),
  env: NodeJS.ProcessEnv = process.env,
  platform: NodeJS.Platform = process.platform
): JavaStatus {
  const hint = javaInstallHint(platform)
  let stdout: string
  try {
    stdout = run(javaBinary(env))
  } catch {
    return { state: 'missing', hint }
  }
  const parsed = parseJavaVersionOutput(stdout)
  if (!parsed) return { state: 'missing', hint }
  if (parsed.major < JAVA_MIN_VERSION) return { state: 'too-old', version: parsed.line, major: parsed.major, hint }
  return { state: 'ok', version: parsed.line, major: parsed.major }
}
