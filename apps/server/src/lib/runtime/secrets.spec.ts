import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import { agentosDataDir, factoryDataDir, secretsPath } from './paths'
import { ensureSecrets, NO_ENCRYPTION } from './secrets'

let configDir: string
beforeEach(() => {
  configDir = fs.mkdtempSync(path.join(os.tmpdir(), 'secrets-'))
})
afterEach(() => fs.rmSync(configDir, { recursive: true, force: true }))

describe('ensureSecrets on a fresh install', () => {
  it('generates strong, distinct secrets and a hex salt', () => {
    const { secrets, created, plaintextServices } = ensureSecrets(configDir)
    expect(created).toBe(true)
    expect(plaintextServices).toEqual([])
    expect(secrets.agentosEncryptionKey.length).toBeGreaterThanOrEqual(32)
    expect(secrets.agentosEncryptionSalt).toMatch(/^[0-9a-f]{32}$/)
    expect(secrets.factoryEncryptionKey.length).toBeGreaterThanOrEqual(32)
    expect(secrets.factoryEncryptionSalt).toMatch(/^[0-9a-f]{32}$/)
    expect(secrets.factoryFakeIdpSecret.length).toBeGreaterThanOrEqual(32)
    expect(secrets.factoryFakeIdpSecret).not.toBe('dev-fake-idp-secret-key-change-in-prod-32bytes')
    expect(new Set(Object.values(secrets)).size).toBe(5)
  })

  it('stores the file with 0600 permissions', () => {
    ensureSecrets(configDir)
    if (process.platform !== 'win32') {
      expect(fs.statSync(secretsPath(configDir)).mode & 0o777).toBe(0o600)
    }
  })

  it('is stable: a second call returns the same secrets without rewriting', () => {
    const first = ensureSecrets(configDir)
    const second = ensureSecrets(configDir)
    expect(second.created).toBe(false)
    expect(second.secrets).toEqual(first.secrets)
  })

  it('repairs loose permissions on an existing file', () => {
    ensureSecrets(configDir)
    if (process.platform === 'win32') return
    fs.chmodSync(secretsPath(configDir), 0o644)
    ensureSecrets(configDir)
    expect(fs.statSync(secretsPath(configDir)).mode & 0o777).toBe(0o600)
  })
})

describe('ensureSecrets migration rule', () => {
  it('keeps AgentOS in plaintext (NONE) when its data already exists, and warns', () => {
    fs.mkdirSync(agentosDataDir(configDir), { recursive: true })
    const warnings: string[] = []
    const { secrets, plaintextServices } = ensureSecrets(configDir, (m) => warnings.push(m))

    expect(secrets.agentosEncryptionKey).toBe(NO_ENCRYPTION)
    expect(secrets.agentosEncryptionSalt).toBe(NO_ENCRYPTION)
    expect(plaintextServices).toEqual(['agentos'])
    expect(warnings.join('\n')).toMatch(/agentos already has data/)
    // The other service is unaffected: its data does not exist yet.
    expect(secrets.factoryEncryptionKey).not.toBe(NO_ENCRYPTION)
  })

  it('handles Factory independently', () => {
    fs.mkdirSync(factoryDataDir(configDir), { recursive: true })
    const { secrets, plaintextServices } = ensureSecrets(configDir)
    expect(secrets.factoryEncryptionKey).toBe(NO_ENCRYPTION)
    expect(plaintextServices).toEqual(['factory'])
    expect(secrets.agentosEncryptionKey).not.toBe(NO_ENCRYPTION)
  })

  it('still replaces the public FACTORY_FAKE_IDP_SECRET literal on a legacy install', () => {
    fs.mkdirSync(agentosDataDir(configDir), { recursive: true })
    fs.mkdirSync(factoryDataDir(configDir), { recursive: true })
    const { secrets } = ensureSecrets(configDir)
    expect(secrets.factoryFakeIdpSecret).not.toBe(NO_ENCRYPTION)
    expect(secrets.factoryFakeIdpSecret.length).toBeGreaterThanOrEqual(32)
  })

  it('never flips the decision later: data appearing after the file exists changes nothing', () => {
    const first = ensureSecrets(configDir)
    fs.mkdirSync(agentosDataDir(configDir), { recursive: true }) // AgentOS created its data with our key
    const second = ensureSecrets(configDir)
    expect(second.secrets.agentosEncryptionKey).toBe(first.secrets.agentosEncryptionKey)
    expect(second.secrets.agentosEncryptionKey).not.toBe(NO_ENCRYPTION)
  })

  it('regenerates a corrupted file as if fresh', () => {
    fs.mkdirSync(path.dirname(secretsPath(configDir)), { recursive: true })
    fs.writeFileSync(secretsPath(configDir), '{ not json')
    expect(ensureSecrets(configDir).created).toBe(true)
  })
})
