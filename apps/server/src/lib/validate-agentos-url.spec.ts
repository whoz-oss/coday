import { validateAgentOsUrl } from './validate-agentos-url'

describe('validateAgentOsUrl', () => {
  describe('valid URLs', () => {
    it('accepts http with localhost and port', () => {
      expect(() => validateAgentOsUrl('http://localhost:8124')).not.toThrow()
    })

    it('accepts https with a named host', () => {
      expect(() => validateAgentOsUrl('https://agentos.internal')).not.toThrow()
    })

    it('accepts a URL carrying a base path', () => {
      expect(() => validateAgentOsUrl('https://example.com/agentos')).not.toThrow()
    })
  })

  describe('invalid URLs', () => {
    it('rejects a bare hostname without scheme', () => {
      expect(() => validateAgentOsUrl('my-host')).toThrow('Invalid AGENTOS_URL: "my-host"')
    })

    it('rejects localhost:port without scheme (parsed as scheme=localhost:)', () => {
      expect(() => validateAgentOsUrl('localhost:8124')).toThrow('Invalid AGENTOS_URL: "localhost:8124"')
    })

    it('rejects a doubled scheme (http://http://...)', () => {
      expect(() => validateAgentOsUrl('http://http://localhost:8124')).toThrow(
        'Invalid AGENTOS_URL: "http://http://localhost:8124"'
      )
    })

    it('rejects a non-http scheme such as ftp://', () => {
      expect(() => validateAgentOsUrl('ftp://localhost:8124')).toThrow('Invalid AGENTOS_URL: "ftp://localhost:8124"')
    })

    it('error message names the offending value', () => {
      expect(() => validateAgentOsUrl('bad-value')).toThrow('"bad-value"')
    })

    it('error message shows a valid example', () => {
      expect(() => validateAgentOsUrl('bad-value')).toThrow('http://localhost:8124')
    })
  })
})
