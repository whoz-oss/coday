import { ToolRequestEvent } from '@coday/model'

/**
 * Builds the full expandable content for a ToolRequestEvent chat message.
 *
 * The returned string is displayed verbatim inside a <pre> block, so it must be
 * plain text (no HTML, no markdown). It shows the tool name and the complete,
 * un-truncated arguments — pretty-printed as JSON when the args are valid JSON,
 * raw otherwise. Never throws.
 */
export function buildToolRequestFullContent(event: ToolRequestEvent): string {
  const args = event.args ?? ''

  let formattedArgs: string
  try {
    const parsed = JSON.parse(args)
    formattedArgs = JSON.stringify(parsed, null, 2)
  } catch {
    formattedArgs = args
  }

  return `🔧 ${event.name}\n\n${formattedArgs}`
}
