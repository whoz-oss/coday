import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import { applyProxyIdentity, decideProxyIdentity } from '../proxy-identity'
import { createIdentityProvider } from './identity'
import { webSetupPath } from './paths'
import { initialState, updateState } from './state'
import { saveIdentity } from './web-setup'

let configDir: string
beforeEach(() => {
  configDir = fs.mkdtempSync(path.join(os.tmpdir(), 'identity-'))
})
afterEach(() => fs.rmSync(configDir, { recursive: true, force: true }))

const setProfile = (profile: 'laptop' | 'managed') =>
  updateState(configDir, { profile }, initialState('in-process', profile))

describe('createIdentityProvider (read at request time)', () => {
  it('reads the identity written AFTER the provider was created, without any restart', () => {
    setProfile('laptop')
    const identity = createIdentityProvider(configDir, {})
    expect(identity()).toBeUndefined()

    saveIdentity(configDir, '  Me@Acme.com ')
    expect(identity()).toBe('me@acme.com')

    saveIdentity(configDir, 'other@acme.com')
    expect(identity()).toBe('other@acme.com')
  })

  it('feeds the proxy header decision: set once known, untouched before', () => {
    setProfile('laptop')
    const identity = createIdentityProvider(configDir, {})
    const decide = () =>
      decideProxyIdentity({ authEnabled: false, envUserId: identity(), resolveUser: () => 'os-user' })

    expect(decide()).toEqual({ action: 'keep' })
    saveIdentity(configDir, 'me@acme.com')
    expect(decide()).toEqual({ action: 'set', value: 'me@acme.com' })

    const req = { setHeader: jest.fn(), removeHeader: jest.fn() }
    applyProxyIdentity(req, decide())
    expect(req.setHeader).toHaveBeenCalledWith('x-external-user-id', 'me@acme.com')
  })

  it('lets the explicit AGENTOS_EXTERNAL_USERID env var win (unchanged behaviour)', () => {
    setProfile('laptop')
    saveIdentity(configDir, 'me@acme.com')
    expect(createIdentityProvider(configDir, { AGENTOS_EXTERNAL_USERID: 'service' })()).toBe('service')
  })

  it('ignores the wizard identity outside the laptop profile (pre-wizard behaviour)', () => {
    setProfile('managed')
    saveIdentity(configDir, 'me@acme.com')
    expect(createIdentityProvider(configDir, {})()).toBeUndefined()
  })

  it('ignores a corrupted or invalid web-setup.json', () => {
    setProfile('laptop')
    fs.writeFileSync(webSetupPath(configDir), '{ nope')
    expect(createIdentityProvider(configDir, {})()).toBeUndefined()
    fs.writeFileSync(webSetupPath(configDir), JSON.stringify({ identity: { email: 'not-an-email' } }))
    expect(createIdentityProvider(configDir, {})()).toBeUndefined()
  })
})
