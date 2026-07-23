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
  } catch (err) {
    console.error(`  ❌ ${file}: YAML parse error: ${err.message}`)
    totalErrors++
    continue
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
console.log(`Done! Fixed: ${totalFixed}, Skipped (ok): ${totalSkipped}, Empty deleted: ${totalEmpty}, Errors: ${totalErrors}`)
