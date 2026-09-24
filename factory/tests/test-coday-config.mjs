// Tests for factory/lib/coday-config.mjs
//
// Covers:
//   - sanitizeForPath: alphanumeric preserved, specials replaced with '_'
//   - parseJiraFromYaml: full config, missing fields, distinct codayUsername vs jiraUsername
//   - discoverJiraCredentials: explicit FACTORY_USER, single auto-discovered user,
//     zero valid users, multiple valid users, missing directory
//
// No network calls. No real filesystem reads (all paths mocked via temp files).
//
// Usage: node factory/tests/test-coday-config.mjs
// Exit: 0 = all pass, 1 = any failure.

import { writeFileSync, mkdirSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { sanitizeForPath, parseJiraFromYaml, discoverJiraCredentials } from '../lib/coday-config.mjs'

let passed = 0
let failed = 0

function expect(name, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  console.log(`${ok ? '\u2713' : '\u2717'} ${name}`)
  if (!ok) {
    console.log(`  expected : ${JSON.stringify(expected)}`)
    console.log(`  got      : ${JSON.stringify(actual)}`)
  }
  if (ok) passed++
  else failed++
}

function ok(name, value) {
  const pass = !!value
  console.log(`${pass ? '\u2713' : '\u2717'} ${name}`)
  if (pass) passed++
  else { failed++; console.log(`  expected truthy, got: ${JSON.stringify(value)}`) }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const TMP = join(tmpdir(), `coday-config-test-${process.pid}`)

function makeUsersDir() {
  mkdirSync(TMP, { recursive: true })
  return TMP
}

function makeUserYaml(usersDir, dirName, content) {
  const userDir = join(usersDir, dirName)
  mkdirSync(userDir, { recursive: true })
  writeFileSync(join(userDir, 'user.yaml'), content, 'utf8')
  return join(userDir, 'user.yaml')
}

function cleanup() {
  try { rmSync(TMP, { recursive: true, force: true }) } catch { }
}

// A minimal valid user.yaml with Jira config
const VALID_YAML = `username: alice.dupont
projects:
  myproject:
    integration:
      JIRA:
        apiUrl: https://example.atlassian.net
        username: alice@example.com
        apiKey: tok-secret-123
`

// user.yaml without Jira block
const NO_JIRA_YAML = `username: bob.martin
projects:
  myproject:
    integration:
      GITHUB:
        token: ghp_xxx
`

// user.yaml with JIRA block but missing apiKey
const INCOMPLETE_JIRA_YAML = `username: carol.smith
projects:
  myproject:
    integration:
      JIRA:
        apiUrl: https://example.atlassian.net
        username: carol@example.com
`

// user.yaml with JIRA config but no root username field (older config)
const JIRA_NO_ROOT_USERNAME = `projects:
  myproject:
    integration:
      JIRA:
        apiUrl: https://example.atlassian.net
        username: dave@example.com
        apiKey: tok-dave-456
`

// ---------------------------------------------------------------------------
// Section 1: sanitizeForPath
// ---------------------------------------------------------------------------

console.log('\n=== sanitizeForPath ===')

expect('alphanumeric unchanged', sanitizeForPath('JohnDoe123'), 'JohnDoe123')
expect('dot replaced', sanitizeForPath('first.last'), 'first_last')
expect('at-sign replaced', sanitizeForPath('user@example.com'), 'user_example_com')
expect('hyphen replaced', sanitizeForPath('jean-pierre'), 'jean_pierre')
expect('multiple specials', sanitizeForPath('benjamin.valdes'), 'benjamin_valdes')
expect('space replaced', sanitizeForPath('John Doe'), 'John_Doe')
expect('empty string', sanitizeForPath(''), '')
expect('only specials', sanitizeForPath('@#$%'), '____')
expect('already sanitized unchanged', sanitizeForPath('alice_dupont'), 'alice_dupont')

// ---------------------------------------------------------------------------
// Section 2: parseJiraFromYaml
// ---------------------------------------------------------------------------

console.log('\n=== parseJiraFromYaml ===')

{
  const result = parseJiraFromYaml(VALID_YAML)
  ok('valid yaml : returns non-null', result !== null)
  expect('valid yaml : codayUsername (root field)', result?.codayUsername, 'alice.dupont')
  expect('valid yaml : apiUrl', result?.apiUrl, 'https://example.atlassian.net')
  expect('valid yaml : jira username (email, distinct from coday username)', result?.username, 'alice@example.com')
  expect('valid yaml : apiKey', result?.apiKey, 'tok-secret-123')
  // Verify the Jira username is NOT the same as the Coday root username
  ok('codayUsername != jira username (distinct fields)', result?.codayUsername !== result?.username)
}

{
  const result = parseJiraFromYaml(NO_JIRA_YAML)
  expect('no JIRA block : returns null', result, null)
}

{
  const result = parseJiraFromYaml(INCOMPLETE_JIRA_YAML)
  expect('incomplete JIRA block (missing apiKey) : returns null', result, null)
}

{
  const result = parseJiraFromYaml(JIRA_NO_ROOT_USERNAME)
  ok('JIRA config without root username : returns non-null', result !== null)
  expect('no root username : codayUsername is null', result?.codayUsername, null)
  expect('no root username : apiUrl still parsed', result?.apiUrl, 'https://example.atlassian.net')
  expect('no root username : jira username still parsed', result?.username, 'dave@example.com')
}

{
  const result = parseJiraFromYaml('')
  expect('empty string : returns null', result, null)
}

// ---------------------------------------------------------------------------
// Section 3: discoverJiraCredentials — explicit FACTORY_USER
// ---------------------------------------------------------------------------

console.log('\n=== discoverJiraCredentials : explicit FACTORY_USER ===')

{
  // Explicit FACTORY_USER with a valid user.yaml
  const usersDir = makeUsersDir()
  // 'alice.dupont' sanitizes to 'alice_dupont'
  makeUserYaml(usersDir, 'alice_dupont', VALID_YAML)

  // Override the internal usersDir by monkey-patching homedir via env
  // Since discoverJiraCredentials uses homedir() internally, we test it
  // indirectly by using the real tmp path — but we need to redirect homedir.
  // Strategy: pass a custom usersDir via the module's internal path.
  // Since the module is pure and uses homedir(), we test with the REAL path
  // by pointing HOME to our tmp dir.
  const origHome = process.env.HOME
  process.env.HOME = TMP.replace(/\/coday-config-test-\d+$/, '')

  // The module resolves: homedir() + '/.coday/users'
  // We need to place files at: <HOME>/.coday/users/<dir>/user.yaml
  // Reset tmp and use a proper structure
  cleanup()
  const fakeHome = join(tmpdir(), `coday-home-${process.pid}`)
  const fakeUsersDir = join(fakeHome, '.coday', 'users')
  mkdirSync(fakeUsersDir, { recursive: true })
  makeUserYaml(fakeUsersDir, 'alice_dupont', VALID_YAML)

  process.env.HOME = fakeHome

  try {
    const result = discoverJiraCredentials('alice.dupont')
    ok('explicit : credentials not null', result.credentials !== null)
    expect('explicit : source = explicit', result.credentials?.source, 'explicit')
    expect('explicit : apiUrl', result.credentials?.apiUrl, 'https://example.atlassian.net')
    expect('explicit : jiraUsername (email)', result.credentials?.jiraUsername, 'alice@example.com')
    expect('explicit : codayUsername (root field)', result.credentials?.codayUsername, 'alice.dupont')
    ok('explicit : apiKey present (not logged)', typeof result.credentials?.apiKey === 'string')
    ok('explicit : diagnostics non-empty', result.diagnostics.length > 0)
    ok('explicit : diagnostic does not contain apiKey', !result.diagnostics.join(' ').includes('tok-secret-123'))
    ok('explicit : configPath includes sanitized dir', result.credentials?.configPath.includes('alice_dupont'))
  } finally {
    process.env.HOME = origHome
    try { rmSync(fakeHome, { recursive: true, force: true }) } catch { }
  }
}

{
  // Explicit FACTORY_USER but directory does not exist
  const origHome = process.env.HOME
  const fakeHome = join(tmpdir(), `coday-home-nodir-${process.pid}`)
  mkdirSync(join(fakeHome, '.coday', 'users'), { recursive: true })
  process.env.HOME = fakeHome

  try {
    const result = discoverJiraCredentials('nonexistent.user')
    expect('explicit missing dir : credentials null', result.credentials, null)
    ok('explicit missing dir : diagnostics mention sanitized dir', result.diagnostics.some((d) => d.includes('nonexistent_user')))
    ok('explicit missing dir : diagnostics mention FACTORY_USER value', result.diagnostics.some((d) => d.includes('nonexistent.user')))
  } finally {
    process.env.HOME = origHome
    try { rmSync(fakeHome, { recursive: true, force: true }) } catch { }
  }
}

{
  // Explicit FACTORY_USER, directory exists but user.yaml has no Jira config
  const origHome = process.env.HOME
  const fakeHome = join(tmpdir(), `coday-home-nojira-${process.pid}`)
  const fakeUsersDir = join(fakeHome, '.coday', 'users')
  mkdirSync(fakeUsersDir, { recursive: true })
  makeUserYaml(fakeUsersDir, 'bob_martin', NO_JIRA_YAML)
  process.env.HOME = fakeHome

  try {
    const result = discoverJiraCredentials('bob.martin')
    expect('explicit no-jira yaml : credentials null', result.credentials, null)
    ok('explicit no-jira yaml : diagnostics mention missing JIRA block', result.diagnostics.some((d) => d.includes('JIRA')))
  } finally {
    process.env.HOME = origHome
    try { rmSync(fakeHome, { recursive: true, force: true }) } catch { }
  }
}

// ---------------------------------------------------------------------------
// Section 4: discoverJiraCredentials — auto-discovery
// ---------------------------------------------------------------------------

console.log('\n=== discoverJiraCredentials : auto-discovery ===')

{
  // Single valid user — should auto-discover
  const origHome = process.env.HOME
  const fakeHome = join(tmpdir(), `coday-home-single-${process.pid}`)
  const fakeUsersDir = join(fakeHome, '.coday', 'users')
  mkdirSync(fakeUsersDir, { recursive: true })
  makeUserYaml(fakeUsersDir, 'alice_dupont', VALID_YAML)

  process.env.HOME = fakeHome
  try {
    const result = discoverJiraCredentials(undefined)
    ok('auto single : credentials not null', result.credentials !== null)
    expect('auto single : source = auto', result.credentials?.source, 'auto')
    expect('auto single : apiUrl', result.credentials?.apiUrl, 'https://example.atlassian.net')
    expect('auto single : jiraUsername', result.credentials?.jiraUsername, 'alice@example.com')
    expect('auto single : codayUsername', result.credentials?.codayUsername, 'alice.dupont')
    ok('auto single : diagnostics say auto-discovered', result.diagnostics.some((d) => d.includes('auto-discovered')))
    ok('auto single : diagnostics do not contain apiKey', !result.diagnostics.join(' ').includes('tok-secret-123'))
  } finally {
    process.env.HOME = origHome
    try { rmSync(fakeHome, { recursive: true, force: true }) } catch { }
  }
}

{
  // Zero valid users (one dir exists but no Jira config)
  const origHome = process.env.HOME
  const fakeHome = join(tmpdir(), `coday-home-zero-${process.pid}`)
  const fakeUsersDir = join(fakeHome, '.coday', 'users')
  mkdirSync(fakeUsersDir, { recursive: true })
  makeUserYaml(fakeUsersDir, 'bob_martin', NO_JIRA_YAML)

  process.env.HOME = fakeHome
  try {
    const result = discoverJiraCredentials(undefined)
    expect('auto zero : credentials null', result.credentials, null)
    ok('auto zero : diagnostics mention no Jira config found', result.diagnostics.some((d) => d.includes('no Coday user.yaml') || d.includes('Jira unavailable')))
  } finally {
    process.env.HOME = origHome
    try { rmSync(fakeHome, { recursive: true, force: true }) } catch { }
  }
}

{
  // Multiple valid users — ambiguity, must not choose silently
  const origHome = process.env.HOME
  const fakeHome = join(tmpdir(), `coday-home-multi-${process.pid}`)
  const fakeUsersDir = join(fakeHome, '.coday', 'users')
  mkdirSync(fakeUsersDir, { recursive: true })

  const ALICE_YAML = `username: alice.dupont\nprojects:\n  p:\n    integration:\n      JIRA:\n        apiUrl: https://a.atlassian.net\n        username: alice@a.com\n        apiKey: tok-alice\n`
  const BOB_YAML = `username: bob.martin\nprojects:\n  p:\n    integration:\n      JIRA:\n        apiUrl: https://b.atlassian.net\n        username: bob@b.com\n        apiKey: tok-bob\n`

  makeUserYaml(fakeUsersDir, 'alice_dupont', ALICE_YAML)
  makeUserYaml(fakeUsersDir, 'bob_martin', BOB_YAML)

  process.env.HOME = fakeHome
  try {
    const result = discoverJiraCredentials(undefined)
    expect('auto multi : credentials null (ambiguous)', result.credentials, null)
    ok('auto multi : diagnostics mention multiple', result.diagnostics.some((d) => d.includes('multiple') || d.includes('2')))
    ok('auto multi : diagnostics tell operator to set FACTORY_USER', result.diagnostics.some((d) => d.includes('FACTORY_USER')))
    ok('auto multi : diagnostics do not contain tok-alice', !result.diagnostics.join(' ').includes('tok-alice'))
    ok('auto multi : diagnostics do not contain tok-bob', !result.diagnostics.join(' ').includes('tok-bob'))
  } finally {
    process.env.HOME = origHome
    try { rmSync(fakeHome, { recursive: true, force: true }) } catch { }
  }
}

{
  // ~/.coday/users does not exist at all
  const origHome = process.env.HOME
  const fakeHome = join(tmpdir(), `coday-home-absent-${process.pid}`)
  mkdirSync(fakeHome, { recursive: true })
  // No .coday/users directory created

  process.env.HOME = fakeHome
  try {
    const result = discoverJiraCredentials(undefined)
    expect('auto no users dir : credentials null', result.credentials, null)
    ok('auto no users dir : diagnostics mention directory not found', result.diagnostics.some((d) => d.includes('not found') || d.includes('unavailable')))
  } finally {
    process.env.HOME = origHome
    try { rmSync(fakeHome, { recursive: true, force: true }) } catch { }
  }
}

// ---------------------------------------------------------------------------
// Section 5: directory-first discovery (rule 6)
// Verify that the codayUsername inside the YAML is NOT used to locate the file.
// The directory name comes from FACTORY_USER sanitization, not from yaml content.
// ---------------------------------------------------------------------------

console.log('\n=== Rule 6: directory name vs yaml username ===')

{
  // Directory is 'eve_external' (sanitized from 'eve.external'),
  // but the yaml's root username says 'different.identity'.
  // discoverJiraCredentials with FACTORY_USER='eve.external' must find it
  // by directory, not by trying to locate a dir named 'different_identity'.
  const origHome = process.env.HOME
  const fakeHome = join(tmpdir(), `coday-home-rule6-${process.pid}`)
  const fakeUsersDir = join(fakeHome, '.coday', 'users')
  mkdirSync(fakeUsersDir, { recursive: true })

  const MISMATCHED_YAML = `username: different.identity\nprojects:\n  p:\n    integration:\n      JIRA:\n        apiUrl: https://c.atlassian.net\n        username: eve@c.com\n        apiKey: tok-eve\n`
  makeUserYaml(fakeUsersDir, 'eve_external', MISMATCHED_YAML)

  process.env.HOME = fakeHome
  try {
    const result = discoverJiraCredentials('eve.external')
    ok('rule 6 : found by directory name, not yaml username', result.credentials !== null)
    expect('rule 6 : codayUsername from yaml', result.credentials?.codayUsername, 'different.identity')
    expect('rule 6 : jiraUsername from yaml', result.credentials?.jiraUsername, 'eve@c.com')
    ok('rule 6 : configPath uses sanitized dir name', result.credentials?.configPath.includes('eve_external'))
  } finally {
    process.env.HOME = origHome
    try { rmSync(fakeHome, { recursive: true, force: true }) } catch { }
  }
}

// ---------------------------------------------------------------------------
// Result
// ---------------------------------------------------------------------------

console.log('')
console.log(`Result: ${passed} passed, ${failed} failed`)
process.exit(failed > 0 ? 1 : 0)
