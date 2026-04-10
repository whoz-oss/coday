#!/usr/bin/env node
/**
 * test-google-config.mjs
 * ---------------------------------------------------------------------------
 * Standalone smoke-test for the Google Calendar credential substitution logic.
 * Runs entirely in-process — no Electron, no app install needed.
 *
 * Usage:
 *   node apps/desktop-twin/macos/scripts/test-google-config.mjs
 * ---------------------------------------------------------------------------
 */

import { readFileSync } from 'fs'
import { join, dirname } from 'path'
import { fileURLToPath } from 'url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const TEMPLATE_PATH = join(__dirname, '..', 'project.template.yaml')

// ---- Replicate the substitution logic from main.ts -------------------------

function applyGoogleCalendarConfig(content, config, previousConfig) {
  let changed = false

  if (config.clientId) {
    const candidates = [...new Set([previousConfig.clientId, 'GOOGLE_OAUTH2_CLIENT_ID'].filter(Boolean))]
    for (const candidate of candidates) {
      if (content.includes(candidate) && candidate !== config.clientId) {
        content = content.replaceAll(candidate, config.clientId)
        changed = true
        console.log(`  ✓ Replaced "${candidate}" with clientId`)
        break
      }
    }
  }

  if (config.clientSecret) {
    const candidates = [...new Set([previousConfig.clientSecret, 'GOOGLE_OAUTH2_CLIENT_SECRET'].filter(Boolean))]
    for (const candidate of candidates) {
      if (content.includes(candidate) && candidate !== config.clientSecret) {
        content = content.replaceAll(candidate, config.clientSecret)
        changed = true
        console.log(`  ✓ Replaced "${candidate}" with clientSecret`)
        break
      }
    }
  }

  return { content, changed }
}

// ---- Tests ------------------------------------------------------------------

let passed = 0
let failed = 0

function assert(label, condition) {
  if (condition) {
    console.log(`  PASS  ${label}`)
    passed++
  } else {
    console.error(`  FAIL  ${label}`)
    failed++
  }
}

// Read the real template
const template = readFileSync(TEMPLATE_PATH, 'utf8')
console.log(`\nTemplate loaded (${template.length} chars)\n`)

assert('template contains GOOGLE_OAUTH2_CLIENT_ID placeholder',
  template.includes('GOOGLE_OAUTH2_CLIENT_ID'))
assert('template contains GOOGLE_OAUTH2_CLIENT_SECRET placeholder',
  template.includes('GOOGLE_OAUTH2_CLIENT_SECRET'))
assert('template contains http: block with endpoints',
  template.includes('endpoints:'))
assert('template contains oauth2: block',
  template.includes('oauth2:'))

console.log('\n--- Test 1: substitute placeholders on fresh file ---')
{
  const { content, changed } = applyGoogleCalendarConfig(
    template,
    { clientId: 'my-client-id.apps.googleusercontent.com', clientSecret: 'GOCSPX-mysecret' },
    { clientId: '', clientSecret: '' }
  )
  assert('changed=true', changed)
  assert('clientId substituted', content.includes('my-client-id.apps.googleusercontent.com'))
  assert('clientSecret substituted', content.includes('GOCSPX-mysecret'))
  assert('placeholder removed (clientId)', !content.includes('GOOGLE_OAUTH2_CLIENT_ID'))
  assert('placeholder removed (clientSecret)', !content.includes('GOOGLE_OAUTH2_CLIENT_SECRET'))
  assert('endpoints block preserved', content.includes('endpoints:'))
  assert('http block preserved', content.includes('baseUrl:'))
  assert('file is not empty', content.length > 100)
  assert('file size close to template size', Math.abs(content.length - template.length) < 200)
}

console.log('\n--- Test 2: update existing credentials ---')
{
  // Simulate a file that already has real credentials
  const existing = template
    .replaceAll('GOOGLE_OAUTH2_CLIENT_ID', 'old-client-id.apps.googleusercontent.com')
    .replaceAll('GOOGLE_OAUTH2_CLIENT_SECRET', 'GOCSPX-oldsecret')

  const { content, changed } = applyGoogleCalendarConfig(
    existing,
    { clientId: 'new-client-id.apps.googleusercontent.com', clientSecret: 'GOCSPX-newsecret' },
    { clientId: 'old-client-id.apps.googleusercontent.com', clientSecret: 'GOCSPX-oldsecret' }
  )
  assert('changed=true', changed)
  assert('new clientId present', content.includes('new-client-id.apps.googleusercontent.com'))
  assert('old clientId gone', !content.includes('old-client-id.apps.googleusercontent.com'))
  assert('new clientSecret present', content.includes('GOCSPX-newsecret'))
  assert('endpoints block preserved', content.includes('endpoints:'))
}

console.log('\n--- Test 3: skip when credentials are empty ---')
{
  const { content, changed } = applyGoogleCalendarConfig(
    template,
    { clientId: '', clientSecret: '' },
    { clientId: '', clientSecret: '' }
  )
  assert('changed=false', !changed)
  assert('template unchanged', content === template)
  assert('placeholders still present', content.includes('GOOGLE_OAUTH2_CLIENT_ID'))
}

console.log('\n--- Test 4: same credentials (idempotent) ---')
{
  const existing = template
    .replaceAll('GOOGLE_OAUTH2_CLIENT_ID', 'same-id.apps.googleusercontent.com')
    .replaceAll('GOOGLE_OAUTH2_CLIENT_SECRET', 'GOCSPX-same')

  const { content, changed } = applyGoogleCalendarConfig(
    existing,
    { clientId: 'same-id.apps.googleusercontent.com', clientSecret: 'GOCSPX-same' },
    { clientId: 'same-id.apps.googleusercontent.com', clientSecret: 'GOCSPX-same' }
  )
  assert('changed=false (no-op)', !changed)
}

console.log(`\n============================`)
console.log(`  ${passed} passed, ${failed} failed`)
console.log(`============================\n`)

if (failed > 0) process.exit(1)
