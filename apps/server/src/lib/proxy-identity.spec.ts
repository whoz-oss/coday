import { applyProxyIdentity, decideProxyIdentity } from './proxy-identity'

describe('decideProxyIdentity', () => {
  it('sets the resolved user when auth is enabled', () => {
    const result = decideProxyIdentity({ authEnabled: true, resolveUser: () => 'alice@example.com' })
    expect(result).toEqual({ action: 'set', value: 'alice@example.com' })
  })

  it('prefers the resolved user over the env var when auth is enabled', () => {
    const result = decideProxyIdentity({
      authEnabled: true,
      envUserId: 'service-account',
      resolveUser: () => 'alice@example.com',
    })
    expect(result).toEqual({ action: 'set', value: 'alice@example.com' })
  })

  it('removes any client-supplied identity when auth is enabled but resolution fails', () => {
    const result = decideProxyIdentity({
      authEnabled: true,
      envUserId: 'service-account',
      resolveUser: () => {
        throw new Error('no identity')
      },
    })
    expect(result).toEqual({ action: 'remove' })
  })

  it('uses the env var when auth is disabled', () => {
    const result = decideProxyIdentity({
      authEnabled: false,
      envUserId: 'service-account',
      resolveUser: () => 'os-user',
    })
    expect(result).toEqual({ action: 'set', value: 'service-account' })
  })

  it('leaves the request untouched when auth is disabled and no env var is set', () => {
    const result = decideProxyIdentity({ authEnabled: false, resolveUser: () => 'os-user' })
    expect(result).toEqual({ action: 'keep' })
  })
})

describe('applyProxyIdentity', () => {
  const makeReq = () => ({ setHeader: jest.fn(), removeHeader: jest.fn() })

  it('sets a lowercase x-external-user-id header', () => {
    const req = makeReq()
    applyProxyIdentity(req, { action: 'set', value: 'alice@example.com' })
    expect(req.setHeader).toHaveBeenCalledWith('x-external-user-id', 'alice@example.com')
    expect(req.removeHeader).not.toHaveBeenCalled()
  })

  it('removes the header', () => {
    const req = makeReq()
    applyProxyIdentity(req, { action: 'remove' })
    expect(req.removeHeader).toHaveBeenCalledWith('x-external-user-id')
    expect(req.setHeader).not.toHaveBeenCalled()
  })

  it('does nothing on keep', () => {
    const req = makeReq()
    applyProxyIdentity(req, { action: 'keep' })
    expect(req.setHeader).not.toHaveBeenCalled()
    expect(req.removeHeader).not.toHaveBeenCalled()
  })
})
