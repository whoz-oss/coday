import { ModelOption } from '../agent-config-form/model-options'

/** One entry of the /model picker. A null model removes the case override. */
export interface ModelChoice {
  /** The alias or API model name stored on the case; null = each agent uses its own model. */
  model: string | null
  /** What the user types after /model. */
  label: string
  description: string
}

const COMMAND = 'model'

/** The entry that removes the override. Not `default`: a platform alias of that name usually exists. */
export const AGENT_MODEL_CHOICE: ModelChoice = {
  model: null,
  label: 'agent',
  description: 'Each agent uses its own model',
}

/** The picker entries for the models of a scope: the way back to the agents' own models, then one per option. */
export function modelChoices(options: ModelOption[]): ModelChoice[] {
  return [
    AGENT_MODEL_CHOICE,
    ...options.map((option) => ({
      model: option.value,
      label: option.value,
      // An alias is labelled `ALIAS (api-model-name)`: only the part after the alias adds something.
      description:
        option.label.startsWith(`${option.value} (`) && option.label.endsWith(')')
          ? option.label.slice(option.value.length + 2, -1)
          : '',
    })),
  ]
}

/**
 * The text typed after /model when [value] is a /model command, or null when it is not: '' while only the
 * command is typed, the (possibly partial) model name after a space.
 *
 * Two letters of the command are enough to open the picker ("/mo"), and a name prefix after a space filters it
 * ("/model B").
 */
export function modelCommandArg(value: string): string | null {
  const match = /^\/(\S+)(\s+(\S*))?$/.exec(value)
  if (!match) return null
  const command = (match[1] ?? '').toLowerCase()
  if (match[2] === undefined) {
    return command.length >= 2 && COMMAND.startsWith(command) ? '' : null
  }
  return command === COMMAND ? (match[3] ?? '') : null
}

/** The [choices] whose label starts with [prefix], case-insensitively. */
export function filterModelChoices(choices: ModelChoice[], prefix: string): ModelChoice[] {
  const wanted = prefix.toLowerCase()
  return choices.filter((choice) => choice.label.toLowerCase().startsWith(wanted))
}

/**
 * For a submitted value: the model name an "/model <name>" command asks for, null when the name is missing, and
 * undefined when the value is not a /model command (so it is a message for the agent). The name is not checked
 * here: the models a case can use depend on its namespace.
 */
export function parseModelCommand(value: string): string | null | undefined {
  const match = /^\/model(?:\s+(\S+))?$/i.exec(value.trim())
  if (!match) return undefined
  return match[1] ?? null
}

/** The choice named [name] (a label, case-insensitively), if there is one. */
export function findModelChoice(choices: ModelChoice[], name: string): ModelChoice | undefined {
  const wanted = name.trim().toLowerCase()
  return choices.find((choice) => choice.label.toLowerCase() === wanted)
}

/** True when two case-level model names are the same model, as the server matches them (case-insensitively). */
export function sameModel(a: string | null | undefined, b: string | null | undefined): boolean {
  return (a ?? '').trim().toLowerCase() === (b ?? '').trim().toLowerCase()
}

/** How the case-level model reads in the case window. */
export function modelLabel(model: string | null | undefined): string {
  return model?.trim() ? model : 'agent default'
}
