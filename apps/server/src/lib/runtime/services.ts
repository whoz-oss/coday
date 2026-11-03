/**
 * How AgentOS and Factory are launched: pure functions turning the runtime facts (ports, secrets,
 * identity, profile) into a spawn specification. Shared by the Nx tasks and the in-process
 * (`--managed`) orchestrator, and unit-tested without spawning anything.
 */

import * as path from 'path'
import { javaBinary } from './java'
import { agentosDataDir, agentosExchangeDir, factoryDataDir } from './paths'
import { RuntimeSecrets } from './secrets'
import { RuntimeProfile } from './state'

export interface ServiceSpec {
  name: 'agentos' | 'factory'
  bin: string
  args: string[]
  env: NodeJS.ProcessEnv
  cwd: string
  port: number
  healthUrl: string
}

export const LOOPBACK = '127.0.0.1'

interface CommonInput {
  configDir: string
  version: string
  profile: RuntimeProfile
  baseEnv: NodeJS.ProcessEnv
}

export interface AgentosSpecInput extends CommonInput {
  port: number
  boltPort: number
  expressPort: number
  /** Bootstrap admin (laptop profile). */
  identityEmail?: string
  secrets?: RuntimeSecrets
}

export function agentosJarPath(configDir: string, version: string): string {
  return path.join(configDir, 'agentos', `agentos-service-${version}.jar`)
}

export function factoryJarPath(configDir: string, version: string): string {
  return path.join(configDir, 'factory', `factory-service-${version}.jar`)
}

export function buildAgentosSpec(input: AgentosSpecInput): ServiceSpec {
  const { configDir, profile, baseEnv, port, boltPort, expressPort } = input
  const cwd = path.join(configDir, 'agentos')
  const boltPortValue = baseEnv.AGENTOS_PERSISTENCE_EMBEDDED_BOLT_PORT ?? String(boltPort)

  const env: NodeJS.ProcessEnv = {
    ...baseEnv,
    // The callback must point at the actual Express port; an explicit env value wins (reverse proxy).
    AGENTOS_OAUTH_REDIRECT_URI:
      baseEnv.AGENTOS_OAUTH_REDIRECT_URI ?? `http://localhost:${expressPort}/agentos/oauth/callback`,
    // Bolt port of the embedded Neo4j (7788 by default, next free one otherwise; 7688 is the dev default),
    // SDN must connect to the same one.
    AGENTOS_PERSISTENCE_EMBEDDED_BOLT_PORT: boltPortValue,
    SPRING_NEO4J_URI: baseEnv.SPRING_NEO4J_URI ?? `bolt://localhost:${boltPortValue}`,
  }
  const args = ['-jar', agentosJarPath(configDir, input.version), `--server.port=${port}`]

  if (profile === 'laptop') {
    // `auth` mode trusts identity headers: nothing but loopback may reach AgentOS.
    args.push(`--server.address=${LOOPBACK}`)
    env.AGENTOS_SECURITY_MODE = 'auth'
    if (input.identityEmail) env.AGENTOS_BOOTSTRAP_ADMIN_EXTERNAL_ID = input.identityEmail
    // Data under configDir (the Spring defaults point at ~/.coday whatever --configDir says).
    env.AGENTOS_PERSISTENCE_DATA_DIR = agentosDataDir(configDir)
    env.AGENTOS_EXCHANGE_MOUNT_ROOT = agentosExchangeDir(configDir)
    env.AGENTOS_ENCRYPTION_KEY = input.secrets?.agentosEncryptionKey ?? 'NONE'
    env.AGENTOS_ENCRYPTION_SALT = input.secrets?.agentosEncryptionSalt ?? 'NONE'
  }

  return {
    name: 'agentos',
    bin: javaBinary(baseEnv),
    args,
    env,
    cwd,
    port,
    healthUrl: `http://${LOOPBACK}:${port}/management/health`,
  }
}

export interface FactorySpecInput extends CommonInput {
  port: number
  agentosUrl: string
  secrets?: RuntimeSecrets
}

export function buildFactorySpec(input: FactorySpecInput): ServiceSpec {
  const { configDir, profile, baseEnv, port } = input
  const env: NodeJS.ProcessEnv = { ...baseEnv, AGENTOS_URL: input.agentosUrl }
  const args = [
    '--enable-native-access=ALL-UNNAMED',
    '-jar',
    factoryJarPath(configDir, input.version),
    `--server.port=${port}`,
  ]

  if (profile === 'laptop') {
    // Factory reads FACTORY_SECURITY_MODE, NOT AGENTOS_SECURITY_MODE.
    env.FACTORY_SECURITY_MODE = 'auth'
    env.FACTORY_BIND_HOST = LOOPBACK
    // FACTORY_BIND_HOST only feeds Factory's own safety banner: Tomcat still listens on every interface
    // (observed on *:<port>) unless Spring is told. Auth mode trusts identity headers: loopback only.
    args.push(`--server.address=${LOOPBACK}`)
    env.FACTORY_PERSISTENCE_DATA_DIR = factoryDataDir(configDir)
    if (input.secrets) {
      env.FACTORY_FAKE_IDP_SECRET = input.secrets.factoryFakeIdpSecret
      env.FACTORY_ENCRYPTION_KEY = input.secrets.factoryEncryptionKey
      env.FACTORY_ENCRYPTION_SALT = input.secrets.factoryEncryptionSalt
    }
  }

  return {
    name: 'factory',
    bin: javaBinary(baseEnv),
    args,
    env,
    cwd: path.join(configDir, 'factory'),
    port,
    healthUrl: `http://${LOOPBACK}:${port}/actuator/health`,
  }
}
