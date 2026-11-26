import { detectFormat } from '../../services/exchange-content.utils'
import { selectionRequestMarker } from '../../services/exchange-selection-request.service'
import type { ExchangeSelectionAction, ExchangeSelectionContextEntry } from '../../services/exchange-state.service'

/**
 * Pure builder of the chat message sent when the user asks an agent to act on a part of an exchange document
 * (no Angular, no HTTP).
 *
 * The message holds the instruction first (so an `@agent` mention in it still routes the message), then the
 * selection in a code fence, then the request marker with the rules of the answer, then the
 * `[Files referenced: scope:path]` line that tells the agent which file to read for context. The path is
 * scope-qualified the way the exchange tools read it (`case:notes/todo.md`).
 *
 * The agent must NOT edit the file: it answers with an explanation and ONE `proposal` block holding the full
 * replacement of the selected passage, which the user then accepts or rejects in the viewer.
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

/** What the agent is told about the answer it must give. */
export function proposalInstructions(requestId: string, markdown = false, html = false): string {
  return [
    selectionRequestMarker(requestId),
    'Do NOT modify the file and do not call any tool that writes it: read it only if you need context.',
    'Answer with a short explanation, followed by exactly one fenced block whose info string is `proposal` and that holds ' +
      'the full replacement text for the selected passage (the passage only, not the whole file).',
    ...(html
      ? [
          'The selection is given as HTML, and the proposal must be an HTML fragment (not a whole document): keep the ' +
            'existing tags, classes, inline styles and attributes unless the instruction asks to change them. Do not add ' +
            '`<script>`, event handler attributes (`onclick`...) or external resources.',
        ]
      : []),
    ...(markdown
      ? [
          'The selection is given as markdown, and the proposal must be markdown too: it may contain headings, lists, ' +
            'emphasis, links and similar, and is rendered as formatted content when accepted. Keep the structure of the ' +
            'selection (for instance its heading levels) unless asked to change it.',
        ]
      : []),
    'If no change is appropriate, answer without that block.',
  ].join('\n')
}

/** The chain of elements around an HTML selection, one per line, from the innermost one up to body. */
function contextLines(context: ExchangeSelectionContextEntry[]): string {
  return context
    .map((entry) => {
      const attrs = [entry.class ? `class="${entry.class}"` : '', entry.style ? `style="${entry.style}"` : '']
        .filter(Boolean)
        .join(' ')
      return `<${entry.tag}${attrs ? ` ${attrs}` : ''}>`
    })
    .join(' inside ')
}

/** The message to send to the agent for a selection action; [requestId] ties its answer back to the request. */
export function buildSelectionMessage(action: ExchangeSelectionAction, requestId: string): string {
  // A selection made in the rendered markdown goes as its markdown source, not as the flattened text.
  const markdown = action.selectionMarkdown !== undefined
  // A selection made in an HTML page goes as its markup, so that the proposal keeps the formatting.
  const html = !markdown && !!action.selectionHtml?.trim()
  const shown = action.selectionMarkdown ?? (html ? action.selectionHtml! : action.selection)
  const fence = fenceFor(shown)
  const language = html ? 'html' : fenceLanguage(action.ref.path)
  const parts = [
    action.instruction.trim(),
    `[Selection from ${qualifiedPath(action)}${lineLabel(action)}]\n${fence}${language}\n${shown}\n${fence}`,
  ]
  if (html && action.selectionContext?.length) {
    parts.push(`(The selection sits in: ${contextLines(action.selectionContext)}.)`)
  }
  if (action.unsavedChanges) {
    parts.push('(The selection comes from unsaved edits in the editor: the file may not contain it yet.)')
  }
  parts.push(proposalInstructions(requestId, markdown, html))
  parts.push(`[Files referenced: ${qualifiedPath(action)}]`)
  return parts.join('\n\n')
}
