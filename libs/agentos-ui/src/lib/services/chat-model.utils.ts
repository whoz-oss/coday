import { AiModel } from '@whoz-oss/agentos-api-client'
import { buildModelOptions, ModelOption } from '../components/agent-config-form/model-options'

/**
 * Transcription models (the platform `WHISPER` alias, or an API name such as `openai/whisper-1`) are not chat
 * models: an agent cannot run on them, so no place that lets a user pick the model of a case offers them.
 */
const TRANSCRIPTION_MODEL = /whisper/i

/** True when [model] can run an agent: neither its alias nor its API model name is a transcription model. */
export function isChatModel(model: AiModel): boolean {
  return !TRANSCRIPTION_MODEL.test(model.alias ?? '') && !TRANSCRIPTION_MODEL.test(model.apiModelName ?? '')
}

/** The models of [models] an agent can run on. */
export function chatModels(models: AiModel[]): AiModel[] {
  return models.filter(isChatModel)
}

/**
 * The choices of a model picker for a case: the aliases first, then the API model names no alias already
 * answers for (see [buildModelOptions]), transcription models left out. Each [ModelOption.value] is what the
 * case stores in `modelName`.
 */
export function chatModelOptions(models: AiModel[]): ModelOption[] {
  const { aliases, apiNames } = buildModelOptions(chatModels(models), null)
  return [...aliases, ...apiNames]
}
