import { buildAgentosSpec, buildFactorySpec } from './services'
import { NO_ENCRYPTION, RuntimeSecrets } from './secrets'

const secrets: RuntimeSecrets = {
  factoryFakeIdpSecret: 'idp-secret',
  agentosEncryptionKey: 'ak',
  agentosEncryptionSalt: 'as',
  factoryEncryptionKey: 'fk',
  factoryEncryptionSalt: 'fs',
}
const base = { configDir: '/c', version: '1.0.0', baseEnv: { PATH: '/bin' } as NodeJS.ProcessEnv }
const agentos = (over = {}) =>
  buildAgentosSpec({ ...base, profile: 'laptop', port: 3001, boltPort: 3002, expressPort: 3000, ...over })

describe('buildAgentosSpec (laptop profile)', () => {
  const spec = agentos({ identityEmail: 'me@acme.com', secrets })

  it('binds to loopback and runs in auth mode with the typed email as bootstrap admin', () => {
    expect(spec.args).toContain('--server.address=127.0.0.1')
    expect(spec.args).toContain('--server.port=3001')
    expect(spec.env['AGENTOS_SECURITY_MODE']).toBe('auth')
    expect(spec.env['AGENTOS_BOOTSTRAP_ADMIN_EXTERNAL_ID']).toBe('me@acme.com')
  })

  it('passes the generated encryption key and salt', () => {
    expect(spec.env['AGENTOS_ENCRYPTION_KEY']).toBe('ak')
    expect(spec.env['AGENTOS_ENCRYPTION_SALT']).toBe('as')
  })

  it('pins the data under configDir and the Bolt port', () => {
    expect(spec.env['AGENTOS_PERSISTENCE_DATA_DIR']).toBe('/c/agentos/data/database')
    expect(spec.env['AGENTOS_PERSISTENCE_EMBEDDED_BOLT_PORT']).toBe('3002')
    expect(spec.env['SPRING_NEO4J_URI']).toBe('bolt://localhost:3002')
    expect(spec.cwd).toBe('/c/agentos')
    expect(spec.args).toContain('/c/agentos/agentos-service-1.0.0.jar')
  })

  it('derives the OAuth redirect from the Express port unless one is set', () => {
    expect(spec.env['AGENTOS_OAUTH_REDIRECT_URI']).toBe('http://localhost:3000/agentos/oauth/callback')
    const custom = agentos({ baseEnv: { AGENTOS_OAUTH_REDIRECT_URI: 'https://x/cb' } })
    expect(custom.env['AGENTOS_OAUTH_REDIRECT_URI']).toBe('https://x/cb')
  })

  it('keeps the plaintext sentinel when the secrets say so (migration)', () => {
    const plain = agentos({
      secrets: { ...secrets, agentosEncryptionKey: NO_ENCRYPTION, agentosEncryptionSalt: NO_ENCRYPTION },
    })
    expect(plain.env['AGENTOS_ENCRYPTION_KEY']).toBe('NONE')
    expect(plain.env['AGENTOS_ENCRYPTION_SALT']).toBe('NONE')
  })

  it('probes health on loopback', () => {
    expect(spec.healthUrl).toBe('http://127.0.0.1:3001/management/health')
  })
})

describe('buildAgentosSpec (managed profile)', () => {
  it('keeps the pre-wizard behaviour: no loopback flag, no auth mode, no forced encryption', () => {
    const spec = agentos({ profile: 'managed' })
    expect(spec.args.join(' ')).not.toContain('--server.address')
    expect(spec.env['AGENTOS_SECURITY_MODE']).toBeUndefined()
    expect(spec.env['AGENTOS_BOOTSTRAP_ADMIN_EXTERNAL_ID']).toBeUndefined()
    expect(spec.env['AGENTOS_ENCRYPTION_KEY']).toBeUndefined()
  })
})

describe('buildFactorySpec', () => {
  const factory = (profile: 'laptop' | 'managed') =>
    buildFactorySpec({ ...base, profile, port: 3003, agentosUrl: 'http://127.0.0.1:3001', secrets })

  it('uses FACTORY_SECURITY_MODE (not AGENTOS_SECURITY_MODE), loopback bind and its own secrets', () => {
    const spec = factory('laptop')
    expect(spec.env['FACTORY_SECURITY_MODE']).toBe('auth')
    expect(spec.env['AGENTOS_SECURITY_MODE']).toBeUndefined()
    expect(spec.env['FACTORY_BIND_HOST']).toBe('127.0.0.1')
    // FACTORY_BIND_HOST alone does not bind Tomcat: the Spring flag does.
    expect(spec.args).toContain('--server.address=127.0.0.1')
    expect(spec.env['FACTORY_FAKE_IDP_SECRET']).toBe('idp-secret')
    expect(spec.env['FACTORY_ENCRYPTION_KEY']).toBe('fk')
    expect(spec.env['FACTORY_ENCRYPTION_SALT']).toBe('fs')
    expect(spec.env['AGENTOS_URL']).toBe('http://127.0.0.1:3001')
    expect(spec.args).toEqual(
      expect.arrayContaining(['-jar', '/c/factory/factory-service-1.0.0.jar', '--server.port=3003'])
    )
    expect(spec.healthUrl).toBe('http://127.0.0.1:3003/actuator/health')
  })

  it('leaves the managed profile alone', () => {
    const spec = factory('managed')
    expect(spec.env['FACTORY_SECURITY_MODE']).toBeUndefined()
    expect(spec.args.join(' ')).not.toContain('--server.address')
    expect(spec.env['FACTORY_FAKE_IDP_SECRET']).toBeUndefined()
    expect(spec.env['AGENTOS_URL']).toBe('http://127.0.0.1:3001')
  })
})
