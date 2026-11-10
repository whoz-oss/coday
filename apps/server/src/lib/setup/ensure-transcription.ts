/**
 * Catch-up of the WHISPER transcription model on an install whose setup is already done.
 *
 * The wizard creates WHISPER while applying, once. An install configured before that, or whose WHISPER was removed,
 * would never get one: the API keys are not kept, so the apply cannot be replayed. But the platform providers already
 * hold their key, so only the missing model has to be created.
 *
 * Run by the welcome status (see routes.ts) once AgentOS is healthy. It only adds, never updates nor deletes:
 *   - a WHISPER model already on ANY platform provider -> nothing to do (the voice capability picks the best one)
 *   - otherwise one WHISPER per platform provider that is in the catalogue WITH a transcription API (OpenAI, Requesty),
 *     at the priority of that provider's other models, so the rank chosen in the wizard is preserved.
 */

import { AgentosClient } from './agentos-client'
import { MODEL_CATALOGUE, TRANSCRIPTION_ALIAS } from './model-catalogue'

const isWhisper = (alias: string | undefined): boolean => alias?.toLowerCase() === TRANSCRIPTION_ALIAS.toLowerCase()

/** Used when the provider has no model to take the priority from. */
const FALLBACK_PRIORITY = 10

/** Returns the number of WHISPER models created. */
export async function ensureTranscriptionModel(client: AgentosClient): Promise<number> {
  const providers = await client.listPlatformProviders()
  const modelsByProvider = new Map<string, Awaited<ReturnType<AgentosClient['listModelsOfProvider']>>>()
  for (const provider of providers) {
    if (provider.id) modelsByProvider.set(provider.id, await client.listModelsOfProvider(provider.id))
  }
  if ([...modelsByProvider.values()].some((models) => models.some((m) => isWhisper(m.alias)))) return 0

  let created = 0
  for (const catalogue of Object.values(MODEL_CATALOGUE)) {
    const transcription = catalogue.transcription
    const provider = providers.find((p) => p.name.toLowerCase() === catalogue.name.toLowerCase())
    if (!transcription || !provider?.id) continue
    const priorities = (modelsByProvider.get(provider.id) ?? []).map((m) => m.priority)
    await client.createModel({
      aiProviderId: provider.id,
      apiModelName: transcription.apiModelName,
      alias: transcription.alias,
      priority: priorities.length ? Math.max(...priorities) : FALLBACK_PRIORITY,
      description: transcription.description,
    })
    created++
  }
  return created
}
