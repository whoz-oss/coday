/**
 * The "apply" step of the first-run wizard, run by the Express server against AgentOS as the
 * first-run user:
 *
 *   0. identify   a first call as the first-run user: AgentOS creates the user and its personal namespace on the
 *                 first request it sees, whatever else its database already holds
 *   1. providers  platform-scope AI providers, one per chosen catalogue entry
 *   2. models     the aliased chat models of each provider, its `default` model (a copy of its SMALL one), plus its
 *                 WHISPER transcription model when it has one (OpenAI, Requesty), priority derived from the rank
 *   3. namespace  the personal namespace `user:<email>` (created asynchronously by AgentOS),
 *                 its `sharedExchangePath` set to the chosen folder
 *   4. agent      the default agent is deployed and is the namespace default
 *
 * IDEMPOTENT: every step looks before it writes (providers matched by name at platform scope,
 * models by alias within the provider), so re-running after a partial failure converges.
 * API keys are only ever sent to AgentOS: they are never logged, stored or returned.
 */

import * as fs from 'fs'
import * as path from 'path'
import { AgentosClient, AgentosHttpError, AgentosModel, AgentosModelPricing, AgentosNamespace } from './agentos-client'
import {
  CatalogueModel,
  CatalogueProvider,
  DEFAULT_MODEL_ALIAS,
  defaultModelOf,
  getCatalogueProvider,
  MODEL_ALIASES,
  priorityForRank,
} from './model-catalogue'
import { ApplyTracker } from './progress'

/**
 * Name of the agent AgentOS creates in each personal namespace (CodayAgentSeeder.AGENT_NAME), deployed there
 * as a namespace-scoped agent, and which the wizard makes the namespace default.
 */
export const DEFAULT_AGENT_NAME = 'Coday'

export interface ApplyProviderInput {
  id: string
  apiKey: string
  rank: number
}

export interface ApplyInput {
  providers: ApplyProviderInput[]
  sharedExchangePath: string
}

export class ApplyValidationError extends Error {}

const isSameOrInside = (child: string, parent: string): boolean => {
  const relative = path.relative(parent, child)
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative))
}

/**
 * Validate the request body; throws ApplyValidationError with a message safe to show the user.
 *
 * `configDir` holds the AgentOS database and exchange, the install secrets and the logs: the files folder, which the
 * agents read and write, may neither contain it (the home folder, typically) nor be inside it.
 */
export function validateApplyInput(body: unknown, configDir?: string): ApplyInput {
  const input = body as Partial<ApplyInput> | undefined
  if (!input || !Array.isArray(input.providers) || input.providers.length === 0) {
    throw new ApplyValidationError('Choose at least one provider and give its API key.')
  }
  const seen = new Set<string>()
  for (const p of input.providers) {
    if (!p || typeof p.id !== 'string' || !getCatalogueProvider(p.id)) {
      throw new ApplyValidationError(`Unknown provider '${String(p?.id)}'.`)
    }
    if (seen.has(p.id)) throw new ApplyValidationError(`Provider '${p.id}' is listed twice.`)
    seen.add(p.id)
    if (typeof p.apiKey !== 'string' || p.apiKey.trim().length < 8) {
      throw new ApplyValidationError(`The API key of ${p.id} looks too short.`)
    }
    if (!Number.isFinite(p.rank)) throw new ApplyValidationError(`The rank of ${p.id} is missing.`)
  }
  if (typeof input.sharedExchangePath !== 'string' || !path.isAbsolute(input.sharedExchangePath)) {
    throw new ApplyValidationError('The files folder must be an absolute path.')
  }
  const sharedExchangePath = path.normalize(input.sharedExchangePath)
  if (configDir) {
    const own = path.resolve(configDir)
    if (isSameOrInside(sharedExchangePath, own) || isSameOrInside(own, sharedExchangePath)) {
      throw new ApplyValidationError(
        `The files folder cannot contain or be inside ${own}, where Coday keeps its data and keys. Choose another folder.`
      )
    }
  }
  return {
    providers: input.providers.map((p) => ({ id: p.id, apiKey: p.apiKey.trim(), rank: p.rank })),
    sharedExchangePath,
  }
}

export interface ApplyDeps {
  client: AgentosClient
  /** Waits for AgentOS to answer; false when it never does. */
  waitForAgentos: () => Promise<boolean>
  tracker: ApplyTracker
  userEmail: string
  sleep?: (ms: number) => Promise<void>
  mkdir?: (dir: string) => void
  /** Personal namespace lookup budget (it is provisioned asynchronously after the user's creation). */
  namespaceAttempts?: number
  namespaceIntervalMs?: number
}

const defaultSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

const describe = (err: unknown): string => (err instanceof Error ? err.message : String(err))

/**
 * What to tell the user. A 403 from a user known not to be an administrator is not a glitch to retry: the providers and
 * the files folder are platform settings, and AgentOS only makes the wizard's email its administrator when its database
 * is empty.
 */
function describeFailure(err: unknown, email: string, isAdmin: boolean | undefined): string {
  if (err instanceof AgentosHttpError && err.status === 403 && isAdmin === false) {
    return (
      `AgentOS refused: ${email} is not an administrator of this AgentOS, and only an administrator can configure ` +
      'the providers and the files folder. Use the email of its administrator, or start again with an empty AgentOS data folder.'
    )
  }
  return describe(err)
}

/** Never leak an API key into an error message that ends up in the UI or the logs. */
const redact = (message: string, secrets: string[]): string =>
  secrets.reduce((acc, secret) => (secret ? acc.split(secret).join('***') : acc), message)

async function ensureProvider(
  deps: ApplyDeps,
  catalogue: CatalogueProvider,
  apiKey: string,
  existing: Array<{ id?: string; name: string }>
): Promise<string> {
  const body = {
    name: catalogue.name,
    description: `${catalogue.label} (created by the first-run setup)`,
    apiType: catalogue.apiType,
    baseUrl: catalogue.baseUrl,
    apiKey,
  }
  const match = existing.find((p) => p.name.toLowerCase() === catalogue.name.toLowerCase())
  if (match?.id) {
    // Re-running with another key must take effect: the key is the one thing we cannot compare.
    await deps.client.updateProvider(match.id, body)
    return match.id
  }
  const created = await deps.client.createProvider(body)
  if (!created.id) throw new Error(`AgentOS returned no id for the ${catalogue.label} provider`)
  return created.id
}

const PRICING_KEYS = ['inputMTokens', 'outputMTokens', 'cacheRead', 'cacheWrite'] as const

/** AgentOS answers an unset rate as null or leaves it out: both mean "no rate". */
const samePricing = (a: AgentosModelPricing | null | undefined, b: AgentosModelPricing | undefined): boolean =>
  PRICING_KEYS.every((key) => (a?.[key] ?? null) === (b?.[key] ?? null))

const findByAlias = (models: AgentosModel[], alias: string): AgentosModel | undefined =>
  models.find((m) => m.alias?.toLowerCase() === alias.toLowerCase())

async function ensureModels(deps: ApplyDeps, catalogue: CatalogueProvider, providerId: string, priority: number) {
  const existing = await deps.client.listModelsOfProvider(providerId)
  // The 4 chat aliases, then `default`: a copy of SMALL, the model AgentOS falls back to when an agent names none.
  const chatModels: Array<[string, CatalogueModel]> = [
    ...MODEL_ALIASES.map((alias): [string, CatalogueModel] => [alias, catalogue.models[alias]]),
    [DEFAULT_MODEL_ALIAS, defaultModelOf(catalogue)],
  ]
  for (const [alias, wanted] of chatModels) {
    const body = {
      aiProviderId: providerId,
      apiModelName: wanted.apiModelName,
      alias,
      priority,
      description: wanted.description,
      pricing: wanted.pricing,
      ...(wanted.contextWindow ? { contextWindow: wanted.contextWindow } : {}),
      ...(wanted.maxCompletionTokens ? { maxCompletionTokens: wanted.maxCompletionTokens } : {}),
    }
    const match = findByAlias(existing, alias)
    if (!match) {
      await deps.client.createModel(body)
    } else if (
      match.id &&
      (match.apiModelName !== body.apiModelName ||
        match.priority !== priority ||
        !samePricing(match.pricing, body.pricing))
    ) {
      await deps.client.updateModel(match.id, body)
    }
  }

  // Transcription: same idempotency (by alias) and same priority rule, but no pricing nor limits to compare.
  const transcription = catalogue.transcription
  if (transcription) {
    const body = {
      aiProviderId: providerId,
      apiModelName: transcription.apiModelName,
      alias: transcription.alias,
      priority,
      description: transcription.description,
    }
    const match = findByAlias(existing, transcription.alias)
    if (!match) {
      await deps.client.createModel(body)
    } else if (match.id && (match.apiModelName !== body.apiModelName || match.priority !== priority)) {
      await deps.client.updateModel(match.id, body)
    }
  }
}

const modelCountOf = (catalogue: CatalogueProvider): number =>
  MODEL_ALIASES.length + 1 + (catalogue.transcription ? 1 : 0)

async function findPersonalNamespace(deps: ApplyDeps): Promise<AgentosNamespace> {
  const attempts = deps.namespaceAttempts ?? 30
  const interval = deps.namespaceIntervalMs ?? 2_000
  const sleep = deps.sleep ?? defaultSleep
  const externalId = `user:${deps.userEmail}`
  for (let attempt = 1; attempt <= attempts; attempt++) {
    const found = await deps.client.findNamespacesByExternalIds([externalId]).catch((err) => {
      // 404/401 right after boot: the user is not provisioned yet, keep trying.
      if (err instanceof AgentosHttpError && [401, 403, 404].includes(err.status)) return []
      throw err
    })
    const namespace = found.find((n) => n.externalId === externalId) ?? found[0]
    if (namespace?.id) return namespace
    await sleep(interval)
  }
  throw new Error(`Your personal namespace (${externalId}) was not created by AgentOS in time. Retry in a moment.`)
}

async function verifyDefaultAgent(deps: ApplyDeps, namespaceId: string): Promise<void> {
  const attempts = deps.namespaceAttempts ?? 30
  const interval = deps.namespaceIntervalMs ?? 2_000
  const sleep = deps.sleep ?? defaultSleep
  for (let attempt = 1; attempt <= attempts; attempt++) {
    const agents = await deps.client.searchAgents(namespaceId)
    if (agents.some((a) => a.name.toLowerCase() === DEFAULT_AGENT_NAME.toLowerCase() && a.enabled !== false)) return
    await sleep(interval)
  }
  throw new Error(`The ${DEFAULT_AGENT_NAME} agent is not deployed in your namespace.`)
}

/**
 * Run the whole apply flow. Never throws: the outcome (and the failing step) is recorded in the
 * tracker, which the status endpoint exposes. Returns true on success.
 */
export async function runApply(
  input: ApplyInput,
  deps: ApplyDeps,
  onDone: () => void = () => undefined
): Promise<boolean> {
  const { tracker } = deps
  const secrets = input.providers.map((p) => p.apiKey)
  const mkdir = deps.mkdir ?? ((dir: string) => void fs.mkdirSync(dir, { recursive: true }))
  tracker.start()
  let isAdmin: boolean | undefined
  try {
    tracker.step('providers', 'running', 'Waiting for AgentOS')
    if (!(await deps.waitForAgentos())) throw new Error('AgentOS did not start. Check the agentos log.')

    // First authenticated call, as the wizard user: AgentOS creates the user and its personal namespace on the first
    // request it sees, whatever else the database holds.
    tracker.step('providers', 'running', 'Identifying you')
    isAdmin = (await deps.client.getMe()).isAdmin

    // 1. providers + 2. models, in rank order
    const ordered = [...input.providers].sort((a, b) => a.rank - b.rank)
    const existing = await deps.client.listPlatformProviders()
    const providerIds = new Map<string, string>()
    for (const [index, p] of ordered.entries()) {
      const catalogue = getCatalogueProvider(p.id) as CatalogueProvider
      tracker.step('providers', 'running', `Configuring ${catalogue.label}`)
      providerIds.set(p.id, await ensureProvider(deps, catalogue, p.apiKey, existing))
      void index
    }
    tracker.step('providers', 'done')

    tracker.step('models', 'running')
    let modelCount = 0
    for (const [index, p] of ordered.entries()) {
      const catalogue = getCatalogueProvider(p.id) as CatalogueProvider
      await ensureModels(deps, catalogue, providerIds.get(p.id) as string, priorityForRank(index + 1, ordered.length))
      modelCount += modelCountOf(catalogue)
    }
    const modelCount = ordered.reduce(
      (count, p) => count + Object.keys((getCatalogueProvider(p.id) as CatalogueProvider).models).length,
      0
    )
    tracker.step('models', 'done', `${modelCount} models`)

    // 3. personal namespace + shared folder
    tracker.step('namespace', 'running', 'Waiting for your personal namespace')
    const namespace = await findPersonalNamespace(deps)
    tracker.setNamespaceId(namespace.id as string)
    mkdir(input.sharedExchangePath)
    const needsUpdate =
      namespace.sharedExchangePath !== input.sharedExchangePath || namespace.defaultAgentName !== DEFAULT_AGENT_NAME
    if (needsUpdate) {
      await deps.client.updateNamespace(namespace.id as string, {
        ...namespace,
        sharedExchangePath: input.sharedExchangePath,
        defaultAgentName: DEFAULT_AGENT_NAME,
      })
    }
    tracker.step('namespace', 'done', input.sharedExchangePath)

    // 4. default agent
    tracker.step('agent', 'running', `Checking the ${DEFAULT_AGENT_NAME} agent`)
    await verifyDefaultAgent(deps, namespace.id as string)
    tracker.step('agent', 'done')

    tracker.finish()
    onDone()
    return true
  } catch (err) {
    tracker.fail(redact(describeFailure(err, deps.userEmail, isAdmin), secrets))
    return false
  }
}
