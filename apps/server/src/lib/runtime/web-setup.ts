/**
 * `<configDir>/web-setup.json`: the first-run answers. It NEVER holds API keys (those go straight
 * to AgentOS), only what the launcher needs to start the services.
 *
 *   { "identity": { "email": "me@acme.com" }, "setupDone": true, "setupDoneAt": "..." }
 *
 * The `agentos` task waits for `identity` before starting AgentOS (it reads its bootstrap admin at
 * first boot), and the Express proxies read it at request time: no restart is ever needed.
 */

import * as fs from 'fs'
import { readJsonFile, writeJsonFile } from './json-file'
import { agentosDataDir, webSetupPath } from './paths'

export interface WebSetup {
  identity?: { email: string }
  setupDone?: boolean
  setupDoneAt?: string
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export function isValidEmail(value: unknown): value is string {
  return typeof value === 'string' && value.length <= 254 && EMAIL_PATTERN.test(value.trim())
}

/** The external ID is case-insensitive in practice: normalise once, use the same string everywhere. */
export const normalizeEmail = (value: string): string => value.trim().toLowerCase()

export function readWebSetup(configDir: string): WebSetup {
  return readJsonFile<WebSetup>(webSetupPath(configDir)) ?? {}
}

export function readIdentityEmail(configDir: string): string | undefined {
  const email = readWebSetup(configDir).identity?.email
  return email && isValidEmail(email) ? email : undefined
}

export function saveIdentity(configDir: string, email: string): WebSetup {
  const next: WebSetup = { ...readWebSetup(configDir), identity: { email: normalizeEmail(email) } }
  writeJsonFile(webSetupPath(configDir), next)
  return next
}

export function markSetupDone(configDir: string, now: () => Date = () => new Date()): WebSetup {
  const next: WebSetup = { ...readWebSetup(configDir), setupDone: true, setupDoneAt: now().toISOString() }
  writeJsonFile(webSetupPath(configDir), next)
  return next
}

/** AgentOS creates its database at its first boot, with the identity as its bootstrap admin. */
export function agentosDatabaseExists(configDir: string): boolean {
  try {
    return fs.readdirSync(agentosDataDir(configDir)).length > 0
  } catch {
    return false
  }
}
