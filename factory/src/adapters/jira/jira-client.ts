/**
 * HTTP adapter for the Jira REST API v3.
 *
 * The pure helpers (`extractTicketId`, `extractAdfText`, `applyCommentBudget`)
 * live in `domain/forge-bmad/jira.ts`; this adapter owns the network boundary.
 *
 * The TypeScript source is bundled into `factory/runtime/factory-operational.mjs`;
 * `factory/lib/jira.mjs` re-exports it as a stateless facade.
 */

import {
  COMMENTS_CHAR_BUDGET,
  applyCommentBudget,
  extractAdfText,
  type JiraComment,
} from '../../domain/forge-bmad/jira.js'

/** Format comments as a Markdown block. */
function formatCommentsSection(comments: readonly JiraComment[], omitted: number): string {
  const parts = comments.map((c) => {
    const date = c.created ? new Date(c.created).toISOString().slice(0, 10) : ''
    return `**${c.author}** (${date}):\n${c.body}`
  })

  let section = parts.join('\n\n---\n\n')

  if (omitted > 0) {
    section += `\n\n*(${omitted} older comment${omitted === 1 ? '' : 's'} omitted — budget exceeded)*`
  }

  return section
}

/**
 * Fetch all comments of a Jira ticket, paginating until exhaustion.
 *
 * Calls GET /rest/api/3/issue/{ticketId}/comment?orderBy=-created&maxResults=50
 * and returns comments ordered newest-first.
 */
export async function fetchJiraComments(
  ticketId: string,
  jiraBaseUrl: string,
  jiraEmail: string,
  jiraApiToken: string
): Promise<JiraComment[]> {
  const credentials = Buffer.from(`${jiraEmail}:${jiraApiToken}`).toString('base64')
  const base = jiraBaseUrl.replace(/\/$/, '')
  const PAGE_SIZE = 50

  const allComments: JiraComment[] = []
  let startAt = 0

  while (true) {
    const url =
      `${base}/rest/api/3/issue/${encodeURIComponent(ticketId)}/comment` +
      `?orderBy=-created&maxResults=${PAGE_SIZE}&startAt=${startAt}`

    const res = await fetch(url, {
      method: 'GET',
      headers: {
        Authorization: `Basic ${credentials}`,
        Accept: 'application/json',
      },
    })

    if (!res.ok) {
      const body = await res.text().catch(() => '')
      throw new Error(`Jira comments API ${res.status} pour ${ticketId} : ${body.slice(0, 200)}`)
    }

    const data: any = await res.json()
    const comments = data.comments ?? []
    const total = data.total ?? 0

    for (const c of comments) {
      const authorName = c.author?.displayName ?? c.author?.emailAddress ?? c.author?.accountId ?? 'Unknown'
      const created = c.created ?? ''
      let body = ''
      if (c.body) {
        if (typeof c.body === 'string') {
          body = c.body
        } else {
          body = extractAdfText(c.body).trim()
        }
      }
      allComments.push({ author: authorName, created, body })
    }

    startAt += comments.length
    if (startAt >= total || comments.length === 0) break
  }

  // allComments is already ordered newest-first (orderBy=-created)
  return allComments
}

/**
 * Fetch a Jira ticket (and its comments) and return the content as Markdown.
 * Throws on any HTTP error (ticket or comments).
 */
export async function fetchJiraTicket(
  ticketId: string,
  jiraBaseUrl: string,
  jiraEmail: string,
  jiraApiToken: string
): Promise<{
  ticketContent: string
  summary: string
  epicKey: string | null
  epicSummary: string | null
  fieldCount: number
  commentCount: number
  commentsIncluded: number
  commentsTruncated: boolean
}> {
  const url = `${jiraBaseUrl.replace(/\/$/, '')}/rest/api/3/issue/${encodeURIComponent(ticketId)}`
  const credentials = Buffer.from(`${jiraEmail}:${jiraApiToken}`).toString('base64')

  const res = await fetch(url, {
    method: 'GET',
    headers: {
      Authorization: `Basic ${credentials}`,
      Accept: 'application/json',
    },
  })

  if (!res.ok) {
    const body = await res.text().catch(() => '')
    throw new Error(`Jira API ${res.status} pour ${ticketId} : ${body.slice(0, 200)}`)
  }

  const data: any = await res.json()
  const fields = data.fields ?? {}

  const summary = fields.summary ?? ''

  // Parent ticket (Epic or Jira hierarchy)
  const parent = fields.parent ?? null
  const epicKey = parent?.key ?? null
  const epicSummary = parent?.fields?.summary ?? null

  // Description: ADF (Jira v3) or plain text (fallback)
  let description = ''
  if (fields.description) {
    if (typeof fields.description === 'string') {
      description = fields.description
    } else {
      description = extractAdfText(fields.description).trim()
    }
  }

  // Acceptance criteria: current custom field (customfield_10016 or similar)
  let acceptanceCriteria = ''
  for (const [key, value] of Object.entries(fields)) {
    if (!value) continue
    if (key.toLowerCase().includes('acceptance') || key === 'customfield_10016') {
      if (typeof value === 'string') {
        acceptanceCriteria = value
        break
      } else if (typeof value === 'object') {
        acceptanceCriteria = extractAdfText(value).trim()
        break
      }
    }
  }

  // Comments — fatal error if the call fails (fetch-ticket contract)
  const allComments = await fetchJiraComments(ticketId, jiraBaseUrl, jiraEmail, jiraApiToken)
  const commentCount = allComments.length

  const { included, omitted } = applyCommentBudget(allComments, COMMENTS_CHAR_BUDGET)
  const commentsIncluded = included.length
  const commentsTruncated = omitted > 0

  // Build the Markdown
  const sections = [`## Summary\n${summary}`]
  if (description) sections.push(`## Description\n${description}`)
  if (acceptanceCriteria) sections.push(`## Acceptance criteria\n${acceptanceCriteria}`)

  if (included.length > 0) {
    sections.push(`## Comments\n${formatCommentsSection(included, omitted)}`)
  }

  const ticketContent = sections.join('\n\n')

  const fieldCount = [summary, description, acceptanceCriteria].filter(Boolean).length

  return {
    ticketContent,
    summary,
    epicKey,
    epicSummary,
    fieldCount,
    commentCount,
    commentsIncluded,
    commentsTruncated,
  }
}
