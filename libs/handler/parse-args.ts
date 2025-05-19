/**
 * Parses a command string for key-value and flag arguments based on specified keys and aliases.
 * 
 * Supports arguments in the forms:
 *   --key=value, --key:value, --key (flag)
 *   -a=value, -a:value, -a (flag)
 * 
 * Non-matching arguments are preserved in the `rest` property as a string.
 *
 * @param command - The raw command-line string to parse.
 * @param inputs - An array of KeyInput objects defining keys and optional aliases to match.
 * @returns An object containing a property for each matched key (value is string or boolean) and a `rest` field with unmatched command content.
 */
export type KeyInput = {
  key: string
  alias?: string
}

export function parseArgs(command: string, inputs: KeyInput[]): Record<string, string | boolean> & { rest: string } {
  const SEPARATOR = ' '
  const splited = command.split(SEPARATOR).filter((v) => !!v)
  const result: any & { rest: string } = { rest: '' }
  inputs.forEach((input) => {
    const matchesKey = (value: string) => value.startsWith(`--${input.key}`)
    const matchesAlias = (value: string) => value.startsWith(`-${input?.alias}`)

    // get just the first match
    const index = splited.findIndex((v) => matchesKey(v) || (input.alias && matchesAlias(v)))
    if (index === -1) {
      return
    }
    const match = splited[index]

    // remove the matched element
    splited.splice(index, 1)

    // get the value-text of the argument
    const prefixLength = matchesKey(match) ? input.key.length + 2 : input.alias?.length + 1
    const valueText = match.slice(prefixLength)

    // if there is a value-text, remove the first char and take it (like in --key=foo or -k:bar)
    // otherwise just ack key arg was there
    result[input.key] = valueText.length ? valueText.slice(1) : true
  })

  // rebuild the rest of the command as it can be natural language text
  result.rest = splited.join(SEPARATOR)

  return result
}
