import { Prompt } from '@whoz-oss/agentos-api-client'

/** The name of the command, without its slash. */
export const BTW_COMMAND = 'btw'

/**
 * What `/btw` looks like in the slash suggestions: the list shows prompts, and selecting one inserts `/<name> `,
 * which is exactly what the command needs before its question. It is not a prompt of the namespace, nothing
 * ever sends it.
 */
export const BTW_PROMPT = {
  name: BTW_COMMAND,
  description: 'Ask a side question without interrupting the agent',
  content: [],
  parameters: [],
  sourceLanguage: 'en',
} as unknown as Prompt

/**
 * For a submitted value: the question of a `/btw <question>` command (trimmed, possibly empty when the
 * question is missing), and undefined when the value is not a /btw command, so it is a message for the agent.
 *
 * The command name needs a word boundary: `/btwx` and `what is /btw?` are not commands.
 */
export function parseBtwCommand(value: string): string | undefined {
  const match = /^\/btw(?:\s+([\s\S]*))?$/i.exec(value.trim())
  if (!match) return undefined
  return (match[1] ?? '').trim()
}
