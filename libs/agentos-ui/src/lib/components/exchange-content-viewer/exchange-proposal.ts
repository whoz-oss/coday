/**
 * Pure parsing of an agent answer to a selection request (no Angular).
 *
 * The agent is asked to explain, then to give exactly one fenced block whose info string is `proposal` holding the
 * full replacement text of the selected passage. Agents are not perfect: several blocks, CRLF line endings, longer
 * fences or `~~~` fences are all accepted, and when there are several proposal blocks the LAST one wins (an agent
 * that corrects itself ends with its final answer). A missing block means "no change proposed".
 */

export interface ParsedProposal {
  /** The answer without the proposal block, trimmed. */
  explanation: string
  /** The replacement text, null when the answer carries no proposal block. */
  proposal: string | null
}

const OPENING_FENCE = /^ {0,3}(`{3,}|~{3,})[ \t]*([^\s`]*)[^`]*$/

export function parseProposal(text: string): ParsedProposal {
  const lines = text.replace(/\r\n?/g, '\n').split('\n')
  let found: { start: number; end: number; body: string[] } | null = null

  let index = 0
  while (index < lines.length) {
    const opening = OPENING_FENCE.exec(lines[index] ?? '')
    if (!opening) {
      index++
      continue
    }
    const fence = opening[1]!
    const isProposal = opening[2]!.toLowerCase() === 'proposal'
    // A closing fence: the same character, at least as long, and nothing else on the line.
    const closing = new RegExp(`^ {0,3}${fence[0] === '`' ? '`' : '~'}{${fence.length},}[ \\t]*$`)
    let end = index + 1
    while (end < lines.length && !closing.test(lines[end] ?? '')) end++
    // An unclosed fence runs to the end of the text, like markdown does.
    if (isProposal) found = { start: index, end: Math.min(end, lines.length - 1), body: lines.slice(index + 1, end) }
    index = end + 1
  }

  if (!found) return { explanation: text.replace(/\r\n?/g, '\n').trim(), proposal: null }
  const rest = [...lines.slice(0, found.start), ...lines.slice(found.end + 1)]
  return { explanation: rest.join('\n').trim(), proposal: found.body.join('\n') }
}
