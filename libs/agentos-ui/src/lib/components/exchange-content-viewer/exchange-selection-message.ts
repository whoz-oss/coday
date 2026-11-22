import { detectFormat } from '../../services/exchange-content.utils'
import type { ExchangeSelectionAction } from '../../services/exchange-state.service'

/**
 * Pure builder of the chat message sent when the user asks an agent to act on a part of an exchange document
 * (no Angular, no HTTP).
 *
 * The message holds the instruction first (so an `@agent` mention in it still routes the message), then the
 * selection in a code fence, then the `[Files referenced: scope:path]` line that tells the agent which file
 * to open or edit with its exchange tools. The path is scope-qualified the way the exchange tools read it
 * (`case:notes/todo.md`).
 */

/** Language tag of the code fence, empty when the format has no specific one. */
function fenceLanguage(path: string): string {
  const format = detectFormat(path.split('/').pop() ?? path)
  return format === 'text' ? '' : format
}

/** A backtick fence longer than any run of backticks in [text], and at least three long. */
export function fenceFor(text: string): string {
  const longestRun = (text.match(/`+/g) ?? []).reduce((longest, run) => Math.max(longest, run.length), 0)
  return '`'.repeat(Math.max(3, longestRun + 1))
}

/** `case:notes/todo.md` — the scope-qualified path an agent reads with its exchange tools. */
function qualifiedPath(action: ExchangeSelectionAction): string {
  return `${action.ref.scope.toLowerCase()}:${action.ref.path}`
}

function lineLabel(action: ExchangeSelectionAction): string {
  const { startLine, endLine } = action
  if (startLine === undefined || endLine === undefined) return ''
  return startLine === endLine ? `, line ${startLine}` : `, lines ${startLine}-${endLine}`
}

/** The message to send to the agent for a selection action. */
export function buildSelectionMessage(action: ExchangeSelectionAction): string {
  const fence = fenceFor(action.selection)
  const parts = [
    action.instruction.trim(),
    `[Selection from ${qualifiedPath(action)}${lineLabel(action)}]\n${fence}${fenceLanguage(action.ref.path)}\n${action.selection}\n${fence}`,
  ]
  if (action.unsavedChanges) {
    parts.push('(The selection comes from unsaved edits in the editor: the file may not contain it yet.)')
  }
  parts.push(`[Files referenced: ${qualifiedPath(action)}]`)
  return parts.join('\n\n')
}
