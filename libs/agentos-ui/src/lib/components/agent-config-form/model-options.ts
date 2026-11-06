import { AiModel } from '@whoz-oss/agentos-api-client'

/** One entry of the model select: [value] is what gets written to `AgentConfig.modelName`. */
export interface ModelOption {
  value: string
  label: string
}

export interface ModelOptions {
  /** Aliases, which the server tries first. Labelled with the API model name they resolve to. */
  aliases: ModelOption[]
  /** API model names no alias already answers for. */
  apiNames: ModelOption[]
  /**
   * The value the agent already holds when the lists above do not carry it verbatim: a name that
   * matches nothing (renamed or deleted model, other scope), or one differing only by case. Kept so
   * the select shows what is stored and an unrelated save preserves it, instead of blanking it.
   */
  current: ModelOption | null
}

const key = (name: string): string => name.trim().toLowerCase()

/** Positive when [a] wins over [b], as `AiModelServiceImpl.MODEL_COMPARATOR`: scope first, then priority. */
function precedence(a: AiModel, b: AiModel): number {
  const scope = (m: AiModel) => (m.namespaceId ? 1 : 0)
  return scope(a) - scope(b) || a.priority - b.priority
}

const byLabel = (a: ModelOption, b: ModelOption): number => a.label.localeCompare(b.label)

/**
 * The options of the model select, from the models the agent's scope can resolve.
 *
 * The server resolves `modelName` case-insensitively, by alias first and by API model name
 * otherwise (`AiModelServiceImpl.findAiModel`). The options follow that: one per distinct alias (the
 * model that wins the name carries the hint), then one per distinct API model name that no alias
 * shadows — offering a name an alias already takes would list a value that resolves to another model.
 */
export function buildModelOptions(models: AiModel[], current: string | null): ModelOptions {
  const aliasWinners = new Map<string, AiModel>()
  for (const model of models) {
    const alias = model.alias?.trim()
    if (!alias) continue
    const winner = aliasWinners.get(key(alias))
    if (!winner || precedence(model, winner) > 0) aliasWinners.set(key(alias), model)
  }
  const aliases: ModelOption[] = [...aliasWinners.values()]
    .map((m) => ({ value: m.alias!.trim(), label: `${m.alias!.trim()} (${m.apiModelName})` }))
    .sort(byLabel)

  const apiKeys = new Set<string>()
  const apiNames: ModelOption[] = []
  for (const model of models) {
    const name = model.apiModelName?.trim()
    if (!name || apiKeys.has(key(name))) continue
    apiKeys.add(key(name))
    if (!aliasWinners.has(key(name))) apiNames.push({ value: name, label: name })
  }
  apiNames.sort(byLabel)

  const stored = current ?? ''
  if (stored.trim().length === 0 || [...aliases, ...apiNames].some((o) => o.value === stored)) {
    return { aliases, apiNames, current: null }
  }
  const known = aliasWinners.has(key(stored)) || apiKeys.has(key(stored))
  return { aliases, apiNames, current: { value: stored, label: known ? stored : `${stored} (not in the list)` } }
}
