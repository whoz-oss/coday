/**
 * The command words of the bundled server. Kept in a leaf module (no import of version.ts, which uses
 * `import.meta`) so specs, compiled as CommonJS, can import it.
 */

export const COMMANDS = ['workspace', 'provision', 'agentos', 'factory', 'server', 'managed'] as const
export type Command = (typeof COMMANDS)[number]

export const isCommand = (value: string | undefined): value is Command => COMMANDS.some((c) => c === value)

/** The command word, when the first positional argument is one of ours. */
export function parseCommand(argv: string[]): Command | undefined {
  const first = argv.find((a) => !a.startsWith('-'))
  return isCommand(first) ? first : undefined
}
