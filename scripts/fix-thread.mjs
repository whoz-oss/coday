#!/usr/bin/env node
/**
 * Fix a corrupted Coday thread YAML file.
 *
 * Repair levels:
 *   1. Normal parse + fix metadata/content
 *   2. Raw repair: block scalars (| / |-) converted to quoted strings
 *   3. Truncate & salvage: cut at last complete message for truncated files
 *
 * Fixes:
 *   - starring: false → []
 *   - users: [] → [{ userId: username }]
 *   - projectId: '' → inferred from directory structure
 *   - message content: string/object → [{ type: 'text', content }]
 *   - trailing orphaned invite/choice events removed
 *   - Re-serializes with blockQuote:false to prevent YAML round-trip failures
 *
 * Prerequisites: run from the project root (needs 'yaml' package from node_modules)
 *
 * Usage:
 *   node scripts/fix-thread.mjs <path-to-thread.yml> [projectId]
 *
 * Examples:
 *   node scripts/fix-thread.mjs ~/.coday/projects/MYPROJECT/threads/abc123.yml
 *   node scripts/fix-thread.mjs ~/.coday/projects/MYPROJECT/threads/abc123.yml MYPROJECT
 */

import { readFileSync, writeFileSync, copyFileSync, statSync } from 'node:fs'
import { parse, stringify } from 'yaml'
import { basename, dirname } from 'node:path'

/** YAML stringify options that prevent round-trip failures */
const YAML_OPTIONS = { lineWidth: 0, blockQuote: false }

/**
 * Attempt to salvage a truncated/corrupted YAML thread file by finding
 * the last complete message entry and rebuilding a valid YAML structure.
 *
 * @param {string} raw Original file content
 * @returns {{content: string, totalMessages: number, lostMessages: number}|null}
 */
function truncateAndSalvage(raw) {
  const lines = raw.split('\n')

  let messagesLineIdx = -1
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].match(/^messages:\s*$/)) {
      messagesLineIdx = i
      break
    }
  }
  if (messagesLineIdx === -1) return null

  const messageStarts = []
  for (let i = messagesLineIdx + 1; i < lines.length; i++) {
    if (lines[i].match(/^  - timestamp:\s/)) {
      messageStarts.push(i)
    }
  }
  if (messageStarts.length === 0) return null

  for (let attempt = messageStarts.length - 1; attempt >= 0; attempt--) {
    const cutAfter = attempt < messageStarts.length - 1
      ? messageStarts[attempt + 1] - 1
      : undefined

    let endLine
    if (cutAfter !== undefined) {
      endLine = cutAfter
    } else {
      endLine = messageStarts[attempt] - 1
    }

    const truncated = lines.slice(0, endLine + 1).join('\n')

    try {
      const data = parse(truncated)
      if (data && data.id) {
        const lostMessages = messageStarts.length - attempt - (cutAfter !== undefined ? 0 : 1)
        return { content: truncated, totalMessages: messageStarts.length, lostMessages }
      }
    } catch {
      continue
    }
  }

  return null
}

/**
 * Attempt raw text repair of block scalars (| and |-) by converting
 * them to double-quoted strings.
 *
 * @param {string} raw Original file content
 * @returns {string} Repaired content
 */
function rawRepairBlockScalars(raw) {
  const lines = raw.split('\n')
  const result = []
  let i = 0

  while (i < lines.length) {
    const line = lines[i]
    const blockMatch = line.match(/^(\s*)(\S+):\s*\|([-+]?)\s*$/)

    if (blockMatch) {
      const [, indent, key, chomp] = blockMatch
      const baseIndent = indent.length
      i++

      let blockIndent = -1
      const blockLines = []

      while (i < lines.length) {
        const blockLine = lines[i]
        if (blockLine.trim() === '') {
          blockLines.push('')
          i++
          continue
        }
        const lineIndent = blockLine.match(/^(\s*)/)[1].length
        if (blockIndent === -1) {
          if (lineIndent <= baseIndent) break
          blockIndent = lineIndent
        }
        if (lineIndent < blockIndent) break
        blockLines.push(blockLine.substring(blockIndent))
        i++
      }

      if (chomp !== '+') {
        while (blockLines.length > 0 && blockLines[blockLines.length - 1] === '') {
          blockLines.pop()
        }
      }

      let value = blockLines.join('\n')
      if (chomp !== '-' && chomp !== '+') {
        value += '\n'
      }

      const escaped = value
        .replace(/\\/g, '\\\\')
        .replace(/"/g, '\\"')
        .replace(/\n/g, '\\n')
        .replace(/\t/g, '\\t')
        .replace(/\r/g, '\\r')

      result.push(`${indent}${key}: "${escaped}"`)
    } else {
      result.push(line)
      i++
    }
  }

  return result.join('\n')
}

// ── Main ──────────────────────────────────────────────────────────────────────

const filePath = process.argv[2]
const projectIdOverride = process.argv[3]

if (!filePath) {
  console.error('Usage: node scripts/fix-thread.mjs <path-to-thread.yml> [projectId]')
  process.exit(1)
}

// Check for empty file
const stat = statSync(filePath)
if (stat.size === 0) {
  console.error(`Empty file (0 bytes): ${filePath}`)
  console.error('This file has no content and cannot be repaired. Delete it manually if not needed.')
  process.exit(1)
}

// 1. Read
console.log(`Reading: ${filePath}`)
const raw = readFileSync(filePath, 'utf-8')

// 2. Parse (with fallback repair attempts)
let data
let repairMethod = 'none'

try {
  data = parse(raw)
} catch (parseErr) {
  console.log(`Normal parse failed: ${parseErr.message}`)

  // Attempt 1: raw repair of block scalars
  try {
    const repairedContent = rawRepairBlockScalars(raw)
    data = parse(repairedContent)
    repairMethod = 'raw-repair'
    console.log('✅ Raw repair succeeded (block scalars converted to quoted strings)')
  } catch {
    // Block scalar repair didn't help
  }

  // Attempt 2: truncate & salvage
  if (!data) {
    const salvageResult = truncateAndSalvage(raw)
    if (salvageResult) {
      try {
        data = parse(salvageResult.content)
        repairMethod = 'truncate'
        // Write truncated content immediately so re-serialize works on clean data
        copyFileSync(filePath, filePath + '.bak')
        writeFileSync(filePath, salvageResult.content, 'utf-8')
        console.log(`✂️  Truncated & salvaged (kept ${salvageResult.totalMessages - salvageResult.lostMessages}/${salvageResult.totalMessages} messages, lost ${salvageResult.lostMessages})`)
      } catch {
        // Salvage produced invalid YAML
      }
    }
  }

  if (!data) {
    console.error(`All repair attempts failed. Original error: ${parseErr.message}`)
    process.exit(1)
  }
}

if (!data || !data.id) {
  console.error('Invalid thread file: missing id')
  process.exit(1)
}

console.log(`Thread: ${data.id}`)
console.log(`  name: ${data.name}`)
console.log(`  username: ${data.username}`)
console.log(`  projectId: ${JSON.stringify(data.projectId)}`)
console.log(`  starring: ${JSON.stringify(data.starring)}`)
console.log(`  users: ${JSON.stringify(data.users)}`)
console.log(`  messages: ${data.messages?.length ?? 0}`)
console.log()

let changed = repairMethod !== 'none'

// 3. Fix starring
if (!Array.isArray(data.starring)) {
  console.log(`FIX starring: ${JSON.stringify(data.starring)} → []`)
  data.starring = []
  changed = true
}

// 4. Fix users
if (!Array.isArray(data.users) || data.users.length === 0) {
  if (data.username) {
    const newUsers = [{ userId: data.username }]
    console.log(`FIX users: ${JSON.stringify(data.users)} → ${JSON.stringify(newUsers)}`)
    data.users = newUsers
    changed = true
  }
}

// 5. Fix projectId
if (!data.projectId) {
  const threadsDir = dirname(filePath)
  const projectDir = dirname(threadsDir)
  const inferred = projectIdOverride || basename(projectDir)
  console.log(`FIX projectId: ${JSON.stringify(data.projectId)} → ${JSON.stringify(inferred)}`)
  data.projectId = inferred
  changed = true
}

// 6. Fix message content fields
let contentFixCount = 0
if (Array.isArray(data.messages)) {
  for (const msg of data.messages) {
    if (msg.type !== 'message') continue

    if (msg.content && !Array.isArray(msg.content)) {
      if (typeof msg.content === 'string') {
        msg.content = [{ type: 'text', content: msg.content }]
        contentFixCount++
      } else if (typeof msg.content === 'object' && msg.content.type) {
        msg.content = [msg.content]
        contentFixCount++
      }
    }

    if (Array.isArray(msg.content)) {
      for (let j = 0; j < msg.content.length; j++) {
        if (typeof msg.content[j] === 'string') {
          msg.content[j] = { type: 'text', content: msg.content[j] }
          contentFixCount++
        }
      }
    }
  }
}

if (contentFixCount > 0) {
  console.log(`FIX content: repaired ${contentFixCount} message(s) with non-array content`)
  changed = true
}

// 7. Fix trailing orphaned invite/choice events
if (Array.isArray(data.messages) && data.messages.length > 0) {
  let trimCount = 0
  while (data.messages.length > 0) {
    const last = data.messages[data.messages.length - 1]
    if (last.type === 'invite' || last.type === 'choice') {
      data.messages.pop()
      trimCount++
    } else {
      break
    }
  }
  if (trimCount > 0) {
    console.log(`FIX trailing invites: removed ${trimCount} orphaned invite/choice event(s)`)
    changed = true
  }
}

if (!changed) {
  console.log('No fixes needed. Re-serializing for clean round-trip...')
}

// 8. Backup and re-serialize
if (repairMethod !== 'truncate') {
  // truncate already created a backup
  const backupPath = filePath + '.bak'
  copyFileSync(filePath, backupPath)
  console.log(`\nBackup: ${backupPath}`)
}

const output = stringify(data, YAML_OPTIONS)
writeFileSync(filePath, output, 'utf-8')

console.log(`Written: ${filePath} (${(output.length / 1024).toFixed(1)} KB)`)
console.log('Done!')
