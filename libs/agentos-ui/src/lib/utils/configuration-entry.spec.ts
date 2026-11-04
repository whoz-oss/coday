import { ConfigurationEntry, ConfigurationEntrySourceEnum } from '@whoz-oss/agentos-api-client'
import { describeConfigurationEntry } from './configuration-entry'

const entry = (overrides: Partial<ConfigurationEntry>): ConfigurationEntry => ({
  key: 'agentos.some.setting',
  source: ConfigurationEntrySourceEnum.PROPERTY,
  configured: true,
  sensitive: false,
  ...overrides,
})

describe('describeConfigurationEntry', () => {
  it.each(['true', 'TRUE', ' true '])('reads %j as on', (value) => {
    expect(describeConfigurationEntry(entry({ value })).status).toBe('on')
  })

  it.each(['false', 'False'])('reads %j as off', (value) => {
    expect(describeConfigurationEntry(entry({ value })).status).toBe('off')
  })

  it('shows any other value as is', () => {
    expect(describeConfigurationEntry(entry({ value: '8124' }))).toEqual({ status: 'value', text: '8124' })
  })

  it('shows an empty value as empty, which is not the same as unset', () => {
    expect(describeConfigurationEntry(entry({ value: '' }))).toEqual({ status: 'value', text: '(empty)' })
  })

  it('reports a setting nothing sets as unset', () => {
    expect(describeConfigurationEntry(entry({ configured: false }))).toEqual({ status: 'unset', text: 'unset' })
  })

  it('says a secret is set without showing anything', () => {
    const display = describeConfigurationEntry(entry({ sensitive: true, configured: true, value: undefined }))
    expect(display).toEqual({ status: 'hidden', text: 'set (hidden)' })
  })

  it('never shows a value for a secret, even if one were sent', () => {
    const display = describeConfigurationEntry(entry({ sensitive: true, configured: true, value: 'leaked' }))
    expect(JSON.stringify(display)).not.toContain('leaked')
  })

  it('reports a secret that is not set as not set', () => {
    expect(describeConfigurationEntry(entry({ sensitive: true, configured: false }))).toEqual({
      status: 'unset',
      text: 'not set',
    })
  })
})
