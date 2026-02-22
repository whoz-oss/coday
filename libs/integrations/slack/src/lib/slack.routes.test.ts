import { buildThreadKey, stripBotMention, verifySlackSignature, shouldHandleMessage } from './slack.routes'

const signingSecret = 'test_secret'

describe('slack.routes helpers', () => {
  it('buildThreadKey returns channel (one channel = one thread)', () => {
    // Current implementation: one channel = one Coday thread
    expect(buildThreadKey('C123', '111.222', '333.444')).toBe('C123')
    expect(buildThreadKey('C123', undefined, '333.444')).toBe('C123')
    expect(buildThreadKey('C456')).toBe('C456')
  })

  it('stripBotMention removes bot mention', () => {
    expect(stripBotMention('<@U123> hello', 'U123')).toBe('hello')
    expect(stripBotMention('no mention', 'U123')).toBe('no mention')
  })

  it('verifySlackSignature validates a known signature', () => {
    const timestamp = Math.floor(Date.now() / 1000).toString()
    const rawBody = 'test-body'
    const baseString = `v0:${timestamp}:${rawBody}`
    const crypto = require('node:crypto')
    const signature = `v0=${crypto.createHmac('sha256', signingSecret).update(baseString).digest('hex')}`

    expect(verifySlackSignature(rawBody, signingSecret, signature, timestamp)).toBe(true)
    expect(verifySlackSignature(rawBody, signingSecret, 'v0=bad', timestamp)).toBe(false)
  })

  it('shouldHandleMessage enforces mention-only and allowlist', () => {
    const event = {
      type: 'message',
      text: 'hello',
      channel: 'C123',
      ts: '111.222',
      channel_type: 'channel',
    }

    expect(shouldHandleMessage(event, { requireMention: false }).allowed).toBe(true)
    expect(shouldHandleMessage(event, { requireMention: true, botUserId: 'U123' }).allowed).toBe(false)
    expect(
      shouldHandleMessage({ ...event, text: '<@U123> hello' }, { requireMention: true, botUserId: 'U123' }).allowed
    ).toBe(true)
    expect(shouldHandleMessage(event, { channelAllowlist: ['C999'] }).allowed).toBe(false)
  })
})
