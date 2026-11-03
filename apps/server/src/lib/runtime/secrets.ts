/**
 * Secrets generated once per install, stored in `<configDir>/runtime/secrets.json` (mode 0600).
 *
 *   FACTORY_FAKE_IDP_SECRET            the default is a public literal: always replaced
 *   AGENTOS_ENCRYPTION_KEY / _SALT     field encryption of AgentOS
 *   FACTORY_ENCRYPTION_KEY / _SALT     field encryption of Factory
 *
 * MIGRATION RULE: encrypting changes how data is read. An install that already has data written
 * in plaintext must keep working, so a service only gets real keys when ITS data directory does
 * not exist yet. Otherwise `NONE` (the plaintext sentinel) is stored explicitly in the file, so
 * the decision is made once and never flips when a data directory appears later.
 */

import * as crypto from 'crypto'
import * as fs from 'fs'
import { readJsonFile, writeJsonFile } from './json-file'
import { agentosDataDir, factoryDataDir, secretsPath } from './paths'

export const NO_ENCRYPTION = 'NONE'

export interface RuntimeSecrets {
  factoryFakeIdpSecret: string
  agentosEncryptionKey: string
  agentosEncryptionSalt: string
  factoryEncryptionKey: string
  factoryEncryptionSalt: string
}

export interface EnsureSecretsResult {
  secrets: RuntimeSecrets
  /** True when the file was created by this call. */
  created: boolean
  /** Services kept in plaintext because they already hold data (migration safety). */
  plaintextServices: Array<'agentos' | 'factory'>
}

/** AgentOS wants a strong key (>= 32 chars) and a hex salt (>= 16 hex chars). */
const randomKey = (): string => crypto.randomBytes(32).toString('base64')
const randomSalt = (): string => crypto.randomBytes(16).toString('hex')

function isComplete(value: Partial<RuntimeSecrets> | undefined): value is RuntimeSecrets {
  return (
    !!value &&
    typeof value.factoryFakeIdpSecret === 'string' &&
    typeof value.agentosEncryptionKey === 'string' &&
    typeof value.agentosEncryptionSalt === 'string' &&
    typeof value.factoryEncryptionKey === 'string' &&
    typeof value.factoryEncryptionSalt === 'string'
  )
}

export function ensureSecrets(
  configDir: string,
  log: (message: string) => void = () => undefined
): EnsureSecretsResult {
  const file = secretsPath(configDir)
  const existing = readJsonFile<Partial<RuntimeSecrets>>(file)
  if (isComplete(existing)) {
    // Re-assert the permissions: the file may have been copied around.
    try {
      fs.chmodSync(file, 0o600)
    } catch {
      /* best effort (Windows) */
    }
    return { secrets: existing, created: false, plaintextServices: [] }
  }

  const plaintextServices: Array<'agentos' | 'factory'> = []
  const agentosHasData = fs.existsSync(agentosDataDir(configDir))
  const factoryHasData = fs.existsSync(factoryDataDir(configDir))
  if (agentosHasData) plaintextServices.push('agentos')
  if (factoryHasData) plaintextServices.push('factory')

  const secrets: RuntimeSecrets = {
    factoryFakeIdpSecret: crypto.randomBytes(32).toString('base64'),
    agentosEncryptionKey: agentosHasData ? NO_ENCRYPTION : randomKey(),
    agentosEncryptionSalt: agentosHasData ? NO_ENCRYPTION : randomSalt(),
    factoryEncryptionKey: factoryHasData ? NO_ENCRYPTION : randomKey(),
    factoryEncryptionSalt: factoryHasData ? NO_ENCRYPTION : randomSalt(),
  }
  for (const service of plaintextServices) {
    log(
      `WARNING: ${service} already has data without encryption: keeping it unencrypted (NONE) so it stays readable. ` +
        `Migrating to encrypted storage is a manual operation.`
    )
  }
  writeJsonFile(file, secrets, 0o600)
  return { secrets, created: true, plaintextServices }
}
