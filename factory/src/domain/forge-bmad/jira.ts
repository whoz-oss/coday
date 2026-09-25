/**
 * Pure Jira domain: ticket-id extraction, Atlassian Document Format flattening
 * and the comment character budget.
 *
 * Network access (`fetchJiraComments`, `fetchJiraTicket`) lives in
 * `adapters/jira/jira-client.ts`.
 *
 * Domain purity: no `node:fs`, HTTP, AgentOS or Git CLI dependency.
 */

/** Budget of characters for the comments included in ticketContent. */
export const COMMENTS_CHAR_BUDGET = 8000

/** One flattened Jira comment. */
export interface JiraComment {
  author: string
  created: string
  body: string
}

/**
 * Extract the Jira identifier from a raw id or a full URL.
 *
 *   'PROJ-1234'                                         → 'PROJ-1234'
 *   'https://foo.atlassian.net/browse/PROJ-1234'        → 'PROJ-1234'
 *   'proj-1234'                                         → 'PROJ-1234' (normalized)
 *   'pas-un-ticket'                                     → null
 *   ''                                                  → null
 */
export function extractTicketId(input: unknown): string | null {
  if (!input || typeof input !== 'string') return null
  const urlMatch = input.match(/\/browse\/([A-Z][A-Z0-9]+-\d+)/i)
  if (urlMatch) return urlMatch[1]!.toUpperCase()
  const idMatch = input.match(/^([A-Z][A-Z0-9]+-\d+)$/i)
  if (idMatch) return idMatch[1]!.toUpperCase()
  return null
}

const BLOCK_TYPES = new Set([
  'paragraph',
  'heading',
  'listItem',
  'bulletList',
  'orderedList',
  'blockquote',
  'codeBlock',
  'rule',
])

/**
 * Recursively extract the raw text of an Atlassian Document Format (ADF) node.
 *
 * The Jira v3 API returns text fields (description, acceptance criteria…) as an
 * ADF tree of typed JSON nodes. This function flattens the tree into plain text,
 * appending a newline after structural block types.
 */
export function extractAdfText(node: any): string {
  if (!node || typeof node !== 'object') return ''
  if (node.type === 'text' && typeof node.text === 'string') return node.text
  const children = node.content ?? []
  const parts = children.map(extractAdfText)
  return BLOCK_TYPES.has(node.type) ? parts.join('') + '\n' : parts.join('')
}

/**
 * Apply the character budget to comments (ordered newest-first).
 *
 * Includes comments from the newest to the oldest until the budget is spent.
 * Returns the included comments and the number omitted.
 */
export function applyCommentBudget(
  comments: readonly JiraComment[],
  budget: number
): { included: JiraComment[]; omitted: number } {
  let remaining = budget
  const included: JiraComment[] = []

  for (const c of comments) {
    const size = c.author.length + c.created.length + c.body.length + 50 // overhead per comment
    if (remaining <= 0) break
    included.push(c)
    remaining -= size
  }

  const omitted = comments.length - included.length
  return { included, omitted }
}
