/**
 * Model catalogue of the first-run wizard.
 *
 * Providers declare FRONTIER, BIGGEST, BIG and SMALL; Requesty also declares default for utility calls.
 * The wizard rank sets priority among providers sharing an alias, not a global provider fallback.
 * default currently belongs only to Requesty, regardless of the ranking.
 *
 * Model names were verified against the official documentation at implementation time:
 *   - Anthropic  docs.anthropic.com/en/docs/about-claude/models/overview (dateless IDs, 4.6+ format)
 *   - OpenAI     platform.openai.com/docs/models (gpt-6-astra, gpt-6.1-sol, gpt-6.1-terra, gpt-6-luna)
 *   - Requesty   FRONTIER retains anthropic/claude-fable-5.1. The other tiers use account policies:
 *                configure claude-haiku, claude-sonnet and claude-opus in Requesty before use.
 *
 * Prices are USD per million tokens (the `pricing` of an AgentOS model, which uses it to estimate costs). Same sources:
 *   - Anthropic  docs.anthropic.com/en/docs/about-claude/pricing
 *   - OpenAI     platform.openai.com/docs/pricing
 *   - Requesty   policy rates and context windows are inherited estimates, not policy guarantees.
 *                Haiku/default use the existing ai-models/SMALL.yaml estimates; backing models may change.
 * AgentOS prices ONE tier per model. Claude Haiku 5.5 is dearer past 100k prompt tokens ($0.50 in / $2.50 out):
 * the base tier is retained for direct Anthropic. Cache write rates are only used with the Anthropic API type
 * (OpenAI-style APIs, Requesty included, do not report cache writes). Haiku's retained estimate is therefore unused
 * with Requesty. Policies omit maxCompletionTokens to leave the output limit to the backing model.
 * Keep policy mappings and estimates aligned with the active ai-models/*.yaml catalogue.
 */

export const MODEL_ALIASES = ['FRONTIER', 'BIGGEST', 'BIG', 'SMALL'] as const
export type ModelAlias = (typeof MODEL_ALIASES)[number]

export type CatalogueProviderId = 'anthropic' | 'openai' | 'requesty'
export type AiApiType = 'OpenAI' | 'Anthropic' | 'Gemini' | 'vLLM' | 'Ollama'

/** USD per million tokens, the shape of AgentOS `ModelPricing`. */
export interface CatalogueModelPricing {
  inputMTokens: number
  outputMTokens: number
  cacheRead: number
  cacheWrite?: number
}

export interface CatalogueModel {
  apiModelName: string
  description: string
  contextWindow?: number
  maxCompletionTokens?: number
  pricing: CatalogueModelPricing
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
  models: Record<ModelAlias, CatalogueModel> & { default?: CatalogueModel }
}

const ANTHROPIC_PRICING: Record<ModelAlias, CatalogueModelPricing> = {
  FRONTIER: { inputMTokens: 10, outputMTokens: 50, cacheRead: 0.25, cacheWrite: 12.5 },
  BIGGEST: { inputMTokens: 4, outputMTokens: 20, cacheRead: 0.2, cacheWrite: 5 },
  BIG: { inputMTokens: 2, outputMTokens: 10, cacheRead: 0.1, cacheWrite: 2.5 },
  SMALL: { inputMTokens: 0.1, outputMTokens: 0.5, cacheRead: 0.01, cacheWrite: 0.125 },
}

const REQUESTY_PRICING: Record<ModelAlias, CatalogueModelPricing> = {
  FRONTIER: { inputMTokens: 10, outputMTokens: 50, cacheRead: 0.25 },
  BIGGEST: { inputMTokens: 4, outputMTokens: 20, cacheRead: 0.2 },
  BIG: { inputMTokens: 2, outputMTokens: 10, cacheRead: 0.2 },
  SMALL: { inputMTokens: 1, outputMTokens: 5, cacheRead: 0.1, cacheWrite: 1.25 },
}

const OPENAI_PRICING: Record<ModelAlias, CatalogueModelPricing> = {
  FRONTIER: { inputMTokens: 10, outputMTokens: 50, cacheRead: 1 },
  BIGGEST: { inputMTokens: 2, outputMTokens: 10, cacheRead: 0.1 },
  BIG: { inputMTokens: 1, outputMTokens: 5, cacheRead: 0.05 },
  SMALL: { inputMTokens: 0.1, outputMTokens: 0.5, cacheRead: 0.01 },
}

const anthropicModels = (
  prefix: string,
  fableName: string,
  pricing: Record<ModelAlias, CatalogueModelPricing>
): CatalogueProvider['models'] => ({
  FRONTIER: {
    apiModelName: `${prefix}${fableName}`,
    description: 'Claude Fable 5.1: demanding reasoning and long-horizon agentic work',
    contextWindow: 1_000_000,
    maxCompletionTokens: 128_000,
    pricing: pricing.FRONTIER,
  },
  BIGGEST: {
    apiModelName: `${prefix}claude-opus-5-5`,
    description: 'Claude Opus 5.5: long-running agentic coding and knowledge work',
    contextWindow: 1_000_000,
    maxCompletionTokens: 128_000,
    pricing: pricing.BIGGEST,
  },
  BIG: {
    apiModelName: `${prefix}claude-sonnet-5-5`,
    description: 'Claude Sonnet 5.5: the best combination of speed and intelligence',
    contextWindow: 1_000_000,
    maxCompletionTokens: 128_000,
    pricing: pricing.BIG,
  },
  SMALL: {
    apiModelName: `${prefix}claude-haiku-5-5`,
    description: 'Claude Haiku 5.5: fast, high-volume tasks',
    contextWindow: 1_000_000,
    maxCompletionTokens: 128_000,
    pricing: pricing.SMALL,
  },
})

const openaiModels = (prefix: string): CatalogueProvider['models'] => ({
  FRONTIER: {
    apiModelName: `${prefix}gpt-6-astra`,
    description: 'GPT-6 Astra: the most capable model for the most demanding work',
    contextWindow: 1_050_000,
    maxCompletionTokens: 128_000,
    pricing: OPENAI_PRICING.FRONTIER,
  },
  BIGGEST: {
    apiModelName: `${prefix}gpt-6.1-sol`,
    description: 'GPT-6.1 Sol: near-Astra performance for complex work at a lower cost',
    contextWindow: 1_050_000,
    maxCompletionTokens: 128_000,
    pricing: OPENAI_PRICING.BIGGEST,
  },
  BIG: {
    apiModelName: `${prefix}gpt-6.1-terra`,
    description: 'GPT-6.1 Terra: fast and efficient, the everyday model',
    contextWindow: 1_050_000,
    maxCompletionTokens: 128_000,
    pricing: OPENAI_PRICING.BIG,
  },
  SMALL: {
    apiModelName: `${prefix}gpt-6-luna`,
    description: 'GPT-6 Luna: the most efficient model for focused, high-volume tasks',
    contextWindow: 1_050_000,
    maxCompletionTokens: 128_000,
    pricing: OPENAI_PRICING.SMALL,
  },
})

// Keep FRONTIER unchanged. Policy pricing and limits retain the existing estimates;
// they are not guarantees of whichever backing model Requesty selects.
const requestyModels = anthropicModels('anthropic/', 'claude-fable-5.1', REQUESTY_PRICING)
const requestySmall: CatalogueModel = {
  apiModelName: 'policy/claude-haiku',
  description: 'Claude Haiku tier via your Requesty policy; backing model may change',
  contextWindow: 200_000,
  pricing: REQUESTY_PRICING.SMALL,
}

export const MODEL_CATALOGUE: Record<CatalogueProviderId, CatalogueProvider> = {
  anthropic: {
    id: 'anthropic',
    name: 'anthropic',
    label: 'Anthropic',
    description: 'Claude models, directly from Anthropic.',
    apiType: 'Anthropic',
    baseUrl: 'https://api.anthropic.com',
    keyUrl: 'https://console.anthropic.com/settings/keys',
    models: anthropicModels('', 'claude-fable-5-1', ANTHROPIC_PRICING),
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
    description:
      'Requires Requesty policies named claude-haiku, claude-sonnet and claude-opus configured in your account.',
    apiType: 'OpenAI',
    baseUrl: 'https://router.eu.requesty.ai',
    keyUrl: 'https://app.requesty.ai/api-keys',
    models: {
      ...requestyModels,
      BIGGEST: {
        apiModelName: 'policy/claude-opus',
        description: 'Claude Opus tier via your Requesty policy; backing model may change',
        contextWindow: 1_000_000,
        pricing: REQUESTY_PRICING.BIGGEST,
      },
      BIG: {
        apiModelName: 'policy/claude-sonnet',
        description: 'Claude Sonnet tier via your Requesty policy; backing model may change',
        contextWindow: 1_000_000,
        pricing: REQUESTY_PRICING.BIG,
      },
      SMALL: requestySmall,
      default: requestySmall,
    },
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
