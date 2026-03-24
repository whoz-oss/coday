import { extractEmailFromCfJwt, resolveUsername } from './resolve-username'

// Helper: build a minimal JWT-shaped token with the given payload
function buildJwt(payload: object): string {
  const header = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url')
  const body = Buffer.from(JSON.stringify(payload)).toString('base64url')
  return `${header}.${body}.fakesignature`
}

describe('extractEmailFromCfJwt', () => {
  it('returns the email claim from a valid JWT', () => {
    const token = buildJwt({ sub: 'user-id', email: 'alice@example.com' })
    expect(extractEmailFromCfJwt(token)).toBe('alice@example.com')
  })

  it('returns null when token is undefined', () => {
    expect(extractEmailFromCfJwt(undefined)).toBeNull()
  })

  it('returns null when token is not a valid JWT (wrong segment count)', () => {
    expect(extractEmailFromCfJwt('not.a.valid.jwt.here')).toBeNull()
  })

  it('returns null when payload is not valid JSON', () => {
    const token = `header.!!!invalid_base64!!!.sig`
    expect(extractEmailFromCfJwt(token)).toBeNull()
  })

  it('returns null when JWT payload has no email claim', () => {
    const token = buildJwt({ sub: 'user-id', name: 'Alice' })
    expect(extractEmailFromCfJwt(token)).toBeNull()
  })

  it('returns null when email claim is an empty string', () => {
    const token = buildJwt({ email: '' })
    expect(extractEmailFromCfJwt(token)).toBeNull()
  })
})

describe('resolveUsername', () => {
  describe('when auth is disabled', () => {
    it('returns the OS username regardless of headers', () => {
      const result = resolveUsername({}, false)
      // Just verify it returns a non-empty string (the actual OS user)
      expect(typeof result).toBe('string')
      expect(result.length).toBeGreaterThan(0)
    })
  })

  describe('when auth is enabled', () => {
    it('uses CF_Authorization JWT email (path 1)', () => {
      const token = buildJwt({ email: 'cf-user@example.com' })
      const headers = { cf_authorization: token }
      expect(resolveUsername(headers, true)).toBe('cf-user@example.com')
    })

    it('falls back to x-forwarded-email when CF header is absent (path 2)', () => {
      const headers = { 'x-forwarded-email': 'proxy-user@example.com' }
      expect(resolveUsername(headers, true)).toBe('proxy-user@example.com')
    })

    it('falls back to x-forwarded-email when CF JWT is malformed (path 2)', () => {
      const headers = {
        cf_authorization: 'not-a-jwt',
        'x-forwarded-email': 'proxy-user@example.com',
      }
      expect(resolveUsername(headers, true)).toBe('proxy-user@example.com')
    })

    it('falls back to x-forwarded-email when CF JWT has no email claim (path 2)', () => {
      const token = buildJwt({ sub: 'user-id' })
      const headers = {
        cf_authorization: token,
        'x-forwarded-email': 'proxy-user@example.com',
      }
      expect(resolveUsername(headers, true)).toBe('proxy-user@example.com')
    })

    it('throws when neither header provides a valid identity (path 3)', () => {
      expect(() => resolveUsername({}, true)).toThrow('Authentication required but no valid identity header found.')
    })

    it('throws when CF JWT is malformed and x-forwarded-email is absent (path 3)', () => {
      const headers = { cf_authorization: 'bad.token' }
      expect(() => resolveUsername(headers, true)).toThrow(
        'Authentication required but no valid identity header found.'
      )
    })
  })
})
