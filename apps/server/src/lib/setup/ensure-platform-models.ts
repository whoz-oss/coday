/**
 * Catch-up of the platform models the wizard creates while applying, on an install whose setup is already done.
 *
 * The wizard creates them once. An install configured before they existed, or whose model was removed, would never get
 * them: the API keys are not kept, so the apply cannot be replayed. But the platform providers already hold their key,
 * so only the missing models have to be created.
 *
 * Run by the server (see routes.ts) once AgentOS is healthy. It only adds, never updates nor deletes. For each model:
 *   - one with that alias already on ANY platform provider -> nothing to do (whatever its provider, or who made it)
 *   - otherwise one per platform provider that is in the catalogue and has that model, at the priority of the
 *     provider's other models, so the rank chosen in the wizard is preserved.
 *
 * The models:
 *   - `default`  a copy of the provider's SMALL model: what AgentOS uses for an agent that names no model
 *   - `WHISPER`  the transcription model, only on providers with a transcription API (OpenAI, Requesty)
 */

import { AgentosClient, AgentosModel } from './agentos-client'
import { CatalogueProvider, DEFAULT_MODEL_ALIAS, defaultModelOf, MODEL_CATALOGUE } from './model-catalogue'

type WantedModel = Omit<AgentosModel, 'aiProviderId' | 'priority'>

interface CatchUpModel {
  alias: string
  /** The model of this provider, or undefined when it has none. */
  of: (provider: CatalogueProvider) => WantedModel | undefined
}

const CATCH_UP_MODELS: CatchUpModel[] = [
  {
    alias: DEFAULT_MODEL_ALIAS,
    of: (provider) => {
      const model = defaultModelOf(provider)
      return {
        apiModelName: model.apiModelName,
        alias: DEFAULT_MODEL_ALIAS,
        description: model.description,
        pricing: model.pricing,
        ...(model.contextWindow ? { contextWindow: model.contextWindow } : {}),
        ...(model.maxCompletionTokens ? { maxCompletionTokens: model.maxCompletionTokens } : {}),
      }
    },
  },
  {
    alias: 'WHISPER',
    of: (provider) =>
      provider.transcription && {
        apiModelName: provider.transcription.apiModelName,
        alias: provider.transcription.alias,
        description: provider.transcription.description,
      },
  },
]

const sameAlias = (a: string | undefined, b: string): boolean => a?.toLowerCase() === b.toLowerCase()

/** Used when the provider has no model to take the priority from. */
const FALLBACK_PRIORITY = 10

/** Returns the number of models created. */
export async function ensurePlatformModels(client: AgentosClient): Promise<number> {
  const providers = await client.listPlatformProviders()
  const modelsByProvider = new Map<string, AgentosModel[]>()
  for (const provider of providers) {
    if (provider.id) modelsByProvider.set(provider.id, await client.listModelsOfProvider(provider.id))
  }
  const allModels = [...modelsByProvider.values()].flat()

  let created = 0
  for (const wanted of CATCH_UP_MODELS) {
    if (allModels.some((m) => sameAlias(m.alias, wanted.alias))) continue
    for (const catalogue of Object.values(MODEL_CATALOGUE)) {
      const provider = providers.find((p) => p.name.toLowerCase() === catalogue.name.toLowerCase())
      const model = wanted.of(catalogue)
      if (!model || !provider?.id) continue
      const priorities = (modelsByProvider.get(provider.id) ?? []).map((m) => m.priority)
      await client.createModel({
        ...model,
        aiProviderId: provider.id,
        priority: priorities.length ? Math.max(...priorities) : FALLBACK_PRIORITY,
      })
      created++
    }
  }
  return created
}
