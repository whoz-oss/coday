import { effortOptionsFor } from './effort-command'
import { modelCommandArg } from './model-command'

/** A leading `/model` or `/effort`, with the single argument that may follow it, ending at a space or the end. */
const LEADING_COMMAND = /^\s*\/(?:model|effort)(?:[ \t]+\S+)?(?=\s|$)/i

/**
 * The composer text once a `/model` or `/effort` choice has been applied: the command is removed, nothing else.
 *
 * A choice made from the chip leaves no command in the composer, so the text is returned as it is (the same
 * string, so a host can tell that nothing changed and leave the textarea alone). A choice made after typing the
 * command (`/model big`, or the partial `/mo` that opened the picker) removes the command, which is in practice
 * the whole value; should text follow it, that text is kept.
 */
export function stripSettingsCommand(value: string): string {
  // The whole value is the command being typed: what opens the picker, or what a submit parses.
  if (modelCommandArg(value) !== null || effortOptionsFor(value) !== null) return ''
  const stripped = value.replace(LEADING_COMMAND, '')
  return stripped === value ? value : stripped.trimStart()
}
