/**
 * Model catalogue of the first-run wizard: DATA ONLY, no logic.
 *
 * provider -> { FRONTIER, BIGGEST, BIG, SMALL } -> { apiModelName, contextWindow, maxCompletionTokens }.
 * Aliases are what agents reference (`modelName: BIG`); the provider rank chosen in the wizard
 * becomes the `priority` of its models, so when several providers are configured the best-ranked
 * one wins each alias.
 *
 * Model names were verified against the official documentation at implementation time:
 *   - Anthropic  docs.anthropic.com/en/docs/about-claude/models/overview (dateless IDs, 4.6+ format)
 *   - OpenAI     platform.openai.com/docs/models (gpt-6-astra, gpt-6.1-sol, gpt-6.1-terra, gpt-6-luna)
 *   - Requesty   router.requesty.ai/v1/models (provider-prefixed names, `anthropic/claude-fable-5.1`
 *                keeps a dot where the Anthropic ID has a dash)
 * Model availability changes: update this file, nothing else.
 */

export const MODEL_ALIASES = ['FRONTIER', 'BIGGEST', 'BIG', 'SMALL'] as const
export type ModelAlias = (typeof MODEL_ALIASES)[number]

export type CatalogueProviderId = 'anthropic' | 'openai' | 'requesty'
export type AiApiType = 'OpenAI' | 'Anthropic' | 'Gemini' | 'vLLM' | 'Ollama'

export interface CatalogueModel {
  apiModelName: string
  description: string
  contextWindow?: number
  maxCompletionTokens?: number
}

export interface CatalogueProvider {
  id: CatalogueProviderId
  /** Name of the platform provider created in AgentOS (also the idempotency key). */
  name: string
  label: string
  description: string
  apiType: AiApiType
  baseUrl: string
  /** Where the user gets a key (shown in the wizard). */
  keyUrl: string
  models: Record<ModelAlias, CatalogueModel>
}

const anthropicModels = (prefix: string, fableName: string): CatalogueProvider['models'] => ({
  FRONTIER: {
    apiModelName: `${prefix}${fableName}`,
    description: 'Claude Fable 5.1: demanding reasoning and long-horizon agentic work',
    contextWindow: 1_000_000,
    maxCompletionTokens: 128_000,
  },
  BIGGEST: {
    apiModelName: `${prefix}claude-opus-5-5`,
    description: 'Claude Opus 5.5: long-running agentic coding and knowledge work',
    contextWindow: 1_000_000,
    maxCompletionTokens: 128_000,
  },
  BIG: {
    apiModelName: `${prefix}claude-sonnet-5-5`,
    description: 'Claude Sonnet 5.5: the best combination of speed and intelligence',
    contextWindow: 1_000_000,
    maxCompletionTokens: 128_000,
  },
  SMALL: {
    apiModelName: `${prefix}claude-haiku-5-5`,
    description: 'Claude Haiku 5.5: fast, high-volume tasks',
    contextWindow: 1_000_000,
    maxCompletionTokens: 128_000,
  },
})

const openaiModels = (prefix: string): CatalogueProvider['models'] => ({
  FRONTIER: {
    apiModelName: `${prefix}gpt-6-astra`,
    description: 'GPT-6 Astra: the most capable model for the most demanding work',
    contextWindow: 1_050_000,
    maxCompletionTokens: 128_000,
  },
  BIGGEST: {
    apiModelName: `${prefix}gpt-6.1-sol`,
    description: 'GPT-6.1 Sol: near-Astra performance for complex work at a lower cost',
    contextWindow: 1_050_000,
    maxCompletionTokens: 128_000,
  },
  BIG: {
    apiModelName: `${prefix}gpt-6.1-terra`,
    description: 'GPT-6.1 Terra: fast and efficient, the everyday model',
    contextWindow: 1_050_000,
    maxCompletionTokens: 128_000,
  },
  SMALL: {
    apiModelName: `${prefix}gpt-6-luna`,
    description: 'GPT-6 Luna: the most efficient model for focused, high-volume tasks',
    contextWindow: 1_050_000,
    maxCompletionTokens: 128_000,
  },
})

export const MODEL_CATALOGUE: Record<CatalogueProviderId, CatalogueProvider> = {
  anthropic: {
    id: 'anthropic',
    name: 'anthropic',
    label: 'Anthropic',
    description: 'Claude models, directly from Anthropic.',
    apiType: 'Anthropic',
    baseUrl: 'https://api.anthropic.com',
    keyUrl: 'https://console.anthropic.com/settings/keys',
    models: anthropicModels('', 'claude-fable-5-1'),
  },
  openai: {
    id: 'openai',
    name: 'openai',
    label: 'OpenAI',
    description: 'GPT models, directly from OpenAI.',
    apiType: 'OpenAI',
    baseUrl: 'https://api.openai.com',
    keyUrl: 'https://platform.openai.com/api-keys',
    models: openaiModels(''),
  },
  requesty: {
    id: 'requesty',
    name: 'requesty',
    label: 'Requesty',
    description: 'One key for Claude, GPT and 600+ other models through the Requesty router.',
    apiType: 'OpenAI',
    baseUrl: 'https://router.requesty.ai/v1',
    keyUrl: 'https://app.requesty.ai/api-keys',
    // Requesty prefixes the lab and spells Fable 5.1 with a dot. It serves both labs, but one alias
    // maps to ONE model per provider: the Claude family is used (the Coday agent is tuned on it).
    models: anthropicModels('anthropic/', 'claude-fable-5.1'),
  },
}

export const CATALOGUE_PROVIDER_IDS = Object.keys(MODEL_CATALOGUE) as CatalogueProviderId[]

export function getCatalogueProvider(id: string): CatalogueProvider | undefined {
  return (MODEL_CATALOGUE as Record<string, CatalogueProvider | undefined>)[id]
}

/**
 * Priority of a provider's models from its rank in the wizard (1 = preferred). AgentOS picks the
 * highest priority among the models sharing an alias, so rank 1 gets the highest number.
 */
export function priorityForRank(rank: number, providerCount: number): number {
  const clamped = Math.min(Math.max(Math.trunc(rank), 1), Math.max(providerCount, 1))
  return (providerCount - clamped + 1) * 10
}
