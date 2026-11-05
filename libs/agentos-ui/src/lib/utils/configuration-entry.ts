import { ConfigurationEntry } from '@whoz-oss/agentos-api-client'

/**
 * How a setting reads on the configuration page.
 * - `on` / `off`: a boolean-looking value, so "is it enabled?" is answered at a glance
 * - `value`: any other value, shown as is
 * - `hidden`: a secret that is set. The server never sends its value
 * - `unset`: nothing is set (a secret included)
 */
export type ConfigurationStatus = 'on' | 'off' | 'value' | 'hidden' | 'unset'

export interface ConfigurationDisplay {
  status: ConfigurationStatus
  text: string
}

export function describeConfigurationEntry(entry: ConfigurationEntry): ConfigurationDisplay {
  if (entry.sensitive) {
    return entry.configured ? { status: 'hidden', text: 'set (hidden)' } : { status: 'unset', text: 'not set' }
  }
  if (!entry.configured) return { status: 'unset', text: 'unset' }

  const value = entry.value ?? ''
  const normalized = value.trim().toLowerCase()
  if (normalized === 'true') return { status: 'on', text: 'on' }
  if (normalized === 'false') return { status: 'off', text: 'off' }
  return { status: 'value', text: value === '' ? '(empty)' : value }
}
