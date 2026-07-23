#!/usr/bin/env node
/**
 * Fix a corrupted Coday thread YAML file.
 *
 * Repairs:
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

import { readFileSync, writeFileSync, copyFileSync } from 'node:fs'
import { parse, stringify } from 'yaml'
import { basename, dirname } from 'node:path'

/** YAML stringify options that prevent round-trip failures */
const YAML_OPTIONS = { lineWidth: 0, blockQuote: false }

const filePath = process.argv[2]
const projectIdOverride = process.argv[3]

if (!filePath) {
  console.error('Usage: node scripts/fix-thread.mjs <path-to-thread.yml> [projectId]')
  process.exit(1)
}

// 1. Read and parse
console.log(`Reading: ${filePath}`)
const raw = readFileSync(filePath, 'utf-8')

let data
try {
  data = parse(raw)
} catch (err) {
  console.error(`YAML parse error: ${err.message}`)
  process.exit(1)
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

let changed = false

// 2. Fix starring
if (!Array.isArray(data.starring)) {
  console.log(`FIX starring: ${JSON.stringify(data.starring)} → []`)
  data.starring = []
  changed = true
}

// 3. Fix users
if (!Array.isArray(data.users) || data.users.length === 0) {
  if (data.username) {
    const newUsers = [{ userId: data.username }]
    console.log(`FIX users: ${JSON.stringify(data.users)} → ${JSON.stringify(newUsers)}`)
    data.users = newUsers
    changed = true
  }
}

// 4. Fix projectId
if (!data.projectId) {
  const threadsDir = dirname(filePath)
  const projectDir = dirname(threadsDir)
  const inferred = projectIdOverride || basename(projectDir)
  console.log(`FIX projectId: ${JSON.stringify(data.projectId)} → ${JSON.stringify(inferred)}`)
  data.projectId = inferred
  changed = true
}

// 5. Fix message content fields
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

// 6. Fix trailing orphaned invite/choice events
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

// 7. Backup and re-serialize
const backupPath = filePath + '.bak'
copyFileSync(filePath, backupPath)
console.log(`\nBackup: ${backupPath}`)

const output = stringify(data, YAML_OPTIONS)
writeFileSync(filePath, output, 'utf-8')

console.log(`Written: ${filePath} (${(output.length / 1024).toFixed(1)} KB)`)
console.log('Done!')
