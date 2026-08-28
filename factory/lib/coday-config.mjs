// coday-config.mjs — Portable discovery of Coday user config and Jira credentials.
//
// Reads ~/.coday/users/<sanitized-dir>/user.yaml to extract Jira credentials.
//
// Resolution strategy:
//   1. If FACTORY_USER is set: sanitize it, read that specific user.yaml.
//      Fails with a clear diagnostic if Jira credentials are missing.
//   2. If FACTORY_USER is absent: scan all user.yaml files under ~/.coday/users/,
//      select automatically only when exactly one has valid Jira credentials.
//      Zero → Jira unavailable (diagnostic). Multiple → ambiguity (diagnostic).
//
// The Jira `username` field (email used for API auth) is distinct from the
// root Coday `username` field (the human identity). Both are parsed separately.
//
// YAML parsing: regex-based, no external dependency.
//
// Exports:
//   sanitizeForPath(raw)          — replaces non-alphanumeric chars with '_'
//   parseJiraFromYaml(text)       — extracts Jira config from user.yaml text, or null
//   discoverJiraCredentials(env)  — main entry point, returns { credentials, diagnostics }

import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { homedir } from 'node:os'

// ---------------------------------------------------------------------------
// Sanitization — mirrors libs/utils/src/lib/username-utils.ts exactly.
// ---------------------------------------------------------------------------

// Replaces every non-alphanumeric character with '_', matching Coday's
// sanitizeUsername() in libs/utils/src/lib/username-utils.ts.
export function sanitizeForPath(raw) {
  return raw.replace(/[^a-zA-Z0-9]/g, '_')
}

// ---------------------------------------------------------------------------
// YAML parsing
// ---------------------------------------------------------------------------

// Parses a user.yaml text and extracts:
//   - codayUsername : root `username` field (the human identity)
//   - apiUrl        : projects.<any>.integration.JIRA.apiUrl
//   - username      : projects.<any>.integration.JIRA.username  (Jira email)
//   - apiKey        : projects.<any>.integration.JIRA.apiKey
//
// Returns null if the JIRA block is absent or any of the three Jira fields
// (apiUrl, username, apiKey) is missing.
//
// Does NOT throw — callers treat null as "no valid Jira config".
export function parseJiraFromYaml(text) {
  // Root username field (may be absent in older configs)
  const rootUsernameMatch = text.match(/^username:\s*(.+)/m)
  const codayUsername = rootUsernameMatch?.[1]?.trim() ?? null

  // Locate the JIRA: block
  const jiraBlockMatch = text.match(/^(\s+)JIRA:\s*$/m)
  if (!jiraBlockMatch) return null

  const jiraStart = text.indexOf(jiraBlockMatch[0])
  const afterJira = text.slice(jiraStart + jiraBlockMatch[0].length)

  const apiUrl   = afterJira.match(/apiUrl:\s*(.+)/)?.[1]?.trim()
  const username = afterJira.match(/username:\s*(.+)/)?.[1]?.trim()
  const apiKey   = afterJira.match(/apiKey:\s*(.+)/)?.[1]?.trim()

  if (!apiUrl || !username || !apiKey) return null
  return { codayUsername, apiUrl, username, apiKey }
}

// ---------------------------------------------------------------------------
// Discovery
// ---------------------------------------------------------------------------

// Main entry point. Reads Jira credentials from Coday user config.
//
// @param {string|undefined} factoryUser  Value of FACTORY_USER env var.
// @returns {{ credentials: object|null, diagnostics: string[] }}
export function discoverJiraCredentials(factoryUser) {
  const usersDir = resolve(homedir(), '.coday', 'users')

  // ── Explicit mode ────────────────────────────────────────────────────────
  if (factoryUser) {
    const safeDir = sanitizeForPath(factoryUser)
    const configPath = join(usersDir, safeDir, 'user.yaml')
    let text
    try {
      text = readFileSync(configPath, 'utf8')
    } catch {
      return {
        credentials: null,
        diagnostics: [
          `FACTORY_USER="${factoryUser}" → sanitized directory "${safeDir}" not found.`,
          `Expected: ${configPath}`,
          `Check that this Coday user directory exists and contains user.yaml.`,
        ],
      }
    }

    const parsed = parseJiraFromYaml(text)
    if (!parsed) {
      return {
        credentials: null,
        diagnostics: [
          `FACTORY_USER="${factoryUser}" → user.yaml found at ${configPath}`,
          `but no complete JIRA integration block (apiUrl + username + apiKey) was found.`,
          `Add a JIRA integration to this user's Coday config, or set JIRA_BASE_URL / JIRA_EMAIL / JIRA_API_TOKEN.`,
        ],
      }
    }

    return {
      credentials: {
        apiUrl: parsed.apiUrl,
        jiraUsername: parsed.username,
        apiKey: parsed.apiKey,
        codayUsername: parsed.codayUsername,
        configPath,
        source: 'explicit',
      },
      diagnostics: [
        `Coday user: ${parsed.codayUsername ?? factoryUser} (${configPath}) [explicit via FACTORY_USER]`,
      ],
    }
  }

  // ── Auto-discovery mode ──────────────────────────────────────────────────
  let dirs
  try {
    dirs = readdirSync(usersDir).filter((d) => {
      try { return statSync(join(usersDir, d)).isDirectory() }
      catch { return false }
    })
  } catch {
    return {
      credentials: null,
      diagnostics: [
        `Coday users directory not found: ${usersDir}`,
        `Jira unavailable. Set FACTORY_USER or configure Jira in your Coday user config.`,
      ],
    }
  }

  const valid = []

  for (const dir of dirs) {
    const configPath = join(usersDir, dir, 'user.yaml')
    let text
    try { text = readFileSync(configPath, 'utf8') }
    catch { continue }

    const parsed = parseJiraFromYaml(text)
    if (parsed) valid.push({ configPath, parsed })
  }

  if (valid.length === 0) {
    return {
      credentials: null,
      diagnostics: [
        `Auto-discovery: no Coday user.yaml with a complete JIRA integration found under ${usersDir}`,
        `Jira unavailable. Set JIRA_BASE_URL / JIRA_EMAIL / JIRA_API_TOKEN, or configure Jira in your Coday user config.`,
      ],
    }
  }

  if (valid.length > 1) {
    const paths = valid.map((v) => v.configPath).join(', ')
    return {
      credentials: null,
      diagnostics: [
        `Auto-discovery: multiple Coday user.yaml files with Jira credentials found (${valid.length}):`,
        `  ${paths}`,
        `Jira unavailable. Set FACTORY_USER=<username> to select one explicitly.`,
      ],
    }
  }

  // Exactly one valid file found
  const { configPath, parsed } = valid[0]
  return {
    credentials: {
      apiUrl: parsed.apiUrl,
      jiraUsername: parsed.username,
      apiKey: parsed.apiKey,
      codayUsername: parsed.codayUsername,
      configPath,
      source: 'auto',
    },
    diagnostics: [
      `Coday user: ${parsed.codayUsername ?? '(unknown)'} (${configPath}) [auto-discovered]`,
    ],
  }
}
