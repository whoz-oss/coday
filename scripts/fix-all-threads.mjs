#!/usr/bin/env node
/**
 * Scan and fix ALL thread YAML files in a project's threads directory.
 * Applies the same fixes as fix-thread.mjs to every .yml file found.
 *
 * Prerequisites: run from the project root (needs 'yaml' package from node_modules)
 *
 * Usage:
 *   node scripts/fix-all-threads.mjs <threads-directory> [projectId]
 *
 * Examples:
 *   node scripts/fix-all-threads.mjs ~/.coday/projects/MYPROJECT/threads
 *   node scripts/fix-all-threads.mjs ~/.coday/projects/MYPROJECT/threads MYPROJECT
 */

import { readFileSync, writeFileSync, copyFileSync, readdirSync, statSync, unlinkSync } from 'node:fs'
import { parse, stringify } from 'yaml'
import { join, basename, dirname } from 'node:path'

/**
 * Attempt to salvage a truncated/corrupted YAML thread file by finding
 * the last complete message entry and rebuilding a valid YAML structure.
 *
 * Strategy:
 * 1. Find the "messages:" line to split header from messages
 * 2. Walk backward from the end to find the last complete "- timestamp:" block
 * 3. Truncate there, close the YAML properly
 * 4. Try yaml.parse() on the result
 *
 * @param {string} raw Original file content
 * @returns {string|null} Repaired content, or null if salvage failed
 */
function truncateAndSalvage(raw) {
  const lines = raw.split('\n')

  // Find the "messages:" line (top-level, no indentation or minimal)
  let messagesLineIdx = -1
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].match(/^messages:\s*$/)) {
      messagesLineIdx = i
      break
    }
  }
  if (messagesLineIdx === -1) return null

  // Find all top-level message entries: lines matching "  - timestamp:" (2-space indent)
  const messageStarts = []
  for (let i = messagesLineIdx + 1; i < lines.length; i++) {
    if (lines[i].match(/^  - timestamp:\s/)) {
      messageStarts.push(i)
    }
  }
  if (messageStarts.length === 0) return null

  // Try from the last message start backward until we find one that produces valid YAML
  for (let attempt = messageStarts.length - 1; attempt >= 0; attempt--) {
    const cutAfter = attempt < messageStarts.length - 1
      ? messageStarts[attempt + 1] - 1  // include up to the line before next message
      : undefined  // last message — we'll try including all remaining lines

    // For the last message, we can't know where it ends cleanly,
    // so try cutting at the start of the last message instead
    let endLine
    if (cutAfter !== undefined) {
      endLine = cutAfter
    } else {
      // Cut right before the last (potentially truncated) message
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
      // This cut point didn't work, try further back
      continue
    }
  }

  return null
}

/**
 * Attempt raw text repair of a YAML file that fails yaml.parse().
 * Fixes block scalars (| and |-) by converting them to double-quoted strings.
 *
 * Strategy: scan line by line, detect "key: |" or "key: |-" patterns,
 * collect the indented block that follows, and replace with a JSON-escaped
 * double-quoted string on a single line.
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

/** YAML stringify options that prevent round-trip failures */
const YAML_OPTIONS = { lineWidth: 0, blockQuote: false }

const threadsDir = process.argv[2]
const projectIdOverride = process.argv[3]

if (!threadsDir) {
  console.error('Usage: node scripts/fix-all-threads.mjs <threads-directory> [projectId]')
  console.error('Example: node scripts/fix-all-threads.mjs ~/.coday/projects/MYPROJECT/threads')
  process.exit(1)
}

const inferredProjectId = projectIdOverride || basename(dirname(threadsDir))
console.log(`Scanning: ${threadsDir}`)
console.log(`Project ID: ${inferredProjectId}`)
console.log()

const files = readdirSync(threadsDir).filter(f => f.endsWith('.yml') && !f.endsWith('.bak'))
console.log(`Found ${files.length} thread file(s)\n`)

let totalFixed = 0
let totalErrors = 0
let totalSkipped = 0
let totalEmpty = 0
let totalRawRepaired = 0

for (const file of files) {
  const filePath = join(threadsDir, file)

  // Delete empty files (0 bytes) — created by race conditions, never had content
  const stat = statSync(filePath)
  if (stat.size === 0) {
    unlinkSync(filePath)
    console.log(`  🗑️  ${file}: empty file deleted`)
    totalEmpty++
    continue
  }

  let raw
  try {
    raw = readFileSync(filePath, 'utf-8')
  } catch (err) {
    console.error(`  ❌ ${file}: Cannot read: ${err.message}`)
    totalErrors++
    continue
  }

  let data
  try {
    data = parse(raw)
  } catch (parseErr) {
    // Attempt 1: raw repair of block scalars
    let repaired = false
    try {
      const repairedContent = rawRepairBlockScalars(raw)
      data = parse(repairedContent)
      copyFileSync(filePath, filePath + '.bak')
      console.log(`  🔧 ${file}: raw repair succeeded (block scalars converted to quoted strings)`)
      totalRawRepaired++
      repaired = true
    } catch {
      // Block scalar repair didn't help
    }

    // Attempt 2: truncate & salvage (for truncated/corrupted files)
    if (!repaired) {
      const salvageResult = truncateAndSalvage(raw)
      if (salvageResult) {
        try {
          data = parse(salvageResult.content)
          copyFileSync(filePath, filePath + '.bak')
          writeFileSync(filePath, salvageResult.content, 'utf-8')
          console.log(`  ✂️  ${file}: truncated & salvaged (kept ${salvageResult.totalMessages - salvageResult.lostMessages}/${salvageResult.totalMessages} messages, lost ${salvageResult.lostMessages})`)
          totalRawRepaired++
          repaired = true
        } catch {
          // Salvage produced invalid YAML
        }
      }
    }

    if (!repaired) {
      console.error(`  ❌ ${file}: YAML parse error (all repair attempts failed): ${parseErr.message}`)
      totalErrors++
      continue
    }
  }

  if (!data || !data.id) {
    console.error(`  ❌ ${file}: Invalid thread file (missing id)`)
    totalErrors++
    continue
  }

  let changed = false
  const fixes = []

  // Fix starring
  if (!Array.isArray(data.starring)) {
    fixes.push(`starring: ${JSON.stringify(data.starring)} → []`)
    data.starring = []
    changed = true
  }

  // Fix users
  if (!Array.isArray(data.users) || data.users.length === 0) {
    if (data.username) {
      fixes.push(`users: [] → [{ userId: '${data.username}' }]`)
      data.users = [{ userId: data.username }]
      changed = true
    }
  }

  // Fix projectId
  if (!data.projectId) {
    fixes.push(`projectId: '' → '${inferredProjectId}'`)
    data.projectId = inferredProjectId
    changed = true
  }

  // Fix message content
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
    fixes.push(`content: ${contentFixCount} message(s) repaired`)
    changed = true
  }

  // Fix trailing orphaned invites
  let trimCount = 0
  if (Array.isArray(data.messages)) {
    while (data.messages.length > 0) {
      const last = data.messages[data.messages.length - 1]
      if (last.type === 'invite' || last.type === 'choice') {
        data.messages.pop()
        trimCount++
      } else {
        break
      }
    }
  }
  if (trimCount > 0) {
    fixes.push(`trailing invites: ${trimCount} removed`)
    changed = true
  }

  if (!changed) {
    totalSkipped++
    continue
  }

  // Backup and write
  copyFileSync(filePath, filePath + '.bak')
  writeFileSync(filePath, stringify(data, YAML_OPTIONS), 'utf-8')

  console.log(`  ✅ ${file} (${data.messages?.length ?? 0} msgs) — ${fixes.join(', ')}`)
  totalFixed++
}

console.log()
console.log(`Done! Fixed: ${totalFixed}, Raw repaired: ${totalRawRepaired}, Skipped (ok): ${totalSkipped}, Empty deleted: ${totalEmpty}, Errors: ${totalErrors}`)
