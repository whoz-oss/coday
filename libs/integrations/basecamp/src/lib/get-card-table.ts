import { BasecampOAuth } from './basecamp-oauth'

export async function getBasecampCardTable(oauth: BasecampOAuth, cardTableId: number): Promise<string> {
  try {
    if (!oauth.isAuthenticated()) {
      await oauth.authenticate()
    }

    const accessToken = await oauth.getAccessToken()
    const baseUrl = oauth.getApiBaseUrl()

    const response = await fetch(`${baseUrl}/card_tables/${cardTableId}.json`, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'User-Agent': 'Coday (https://github.com/whoz-oss/coday)',
      },
    })

    if (!response.ok) {
      return `Error fetching card table: ${response.status} ${response.statusText}`
    }

    const board = await response.json()

    const lists = (board.lists || []) as any[]

    if (lists.length === 0) {
      return `Card table "${board.title}" (ID: ${board.id}) has no columns.`
    }

    const columnList = lists
      .map((col: any) => `- **${col.title}** (ID: ${col.id}, type: ${col.type}, cards: ${col.cards_count ?? 0})`)
      .join('\n')

    return `# ${board.title} (ID: ${board.id})

**URL:** ${board.app_url}

## Columns

${columnList}

_Use getCardTableCards with a column ID to retrieve the cards in that column._`
  } catch (error: any) {
    return `Error: ${error.message}`
  }
}

export async function getBasecampCardTableCards(
  oauth: BasecampOAuth,
  columnId: number,
  page?: number
): Promise<string> {
  try {
    if (!oauth.isAuthenticated()) {
      await oauth.authenticate()
    }

    const accessToken = await oauth.getAccessToken()
    const baseUrl = oauth.getApiBaseUrl()

    const url = page
      ? `${baseUrl}/card_tables/lists/${columnId}/cards.json?page=${page}`
      : `${baseUrl}/card_tables/lists/${columnId}/cards.json`

    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'User-Agent': 'Coday (https://github.com/whoz-oss/coday)',
      },
    })

    if (!response.ok) {
      return `Error fetching cards: ${response.status} ${response.statusText}`
    }

    const cards = await response.json()

    const totalCount = response.headers.get('X-Total-Count')
    const linkHeader = response.headers.get('Link')
    let nextPage: number | null = null

    if (linkHeader) {
      const nextMatch = linkHeader.match(/page=(\d+)>; rel="next"/)
      if (nextMatch && nextMatch[1]) {
        nextPage = parseInt(nextMatch[1], 10)
      }
    }

    if (cards.length === 0) {
      return 'No cards found in this column.'
    }

    const cardList = cards
      .map((card: any) => {
        const creator = card.creator ? card.creator.name : 'Unknown'
        const createdAt = new Date(card.created_at).toLocaleString()
        const dueOn = card.due_on || 'No due date'
        const assignees = (card.assignees || []).map((a: any) => a.name).join(', ') || 'Unassigned'
        const commentsCount = card.comment_count || 0
        return `- **${card.title}** (ID: ${card.id})\n  - Creator: ${creator} — Created: ${createdAt}\n  - Due: ${dueOn} — Assigned: ${assignees}\n  - Comments: ${commentsCount}\n  - URL: ${card.app_url}`
      })
      .join('\n\n')

    let result = `Found ${cards.length} card(s) on this page`
    if (totalCount) {
      result += ` (Total: ${totalCount})`
    }
    if (page) {
      result += ` [Page ${page}]`
    }
    result += `:\n\n${cardList}`

    if (nextPage) {
      result += `\n\n📄 More results available. Use page=${nextPage} to get the next page.`
    }

    return result
  } catch (error: any) {
    return `Error: ${error.message}`
  }
}

export async function getBasecampCard(oauth: BasecampOAuth, cardId: number): Promise<string> {
  try {
    if (!oauth.isAuthenticated()) {
      await oauth.authenticate()
    }

    const accessToken = await oauth.getAccessToken()
    const baseUrl = oauth.getApiBaseUrl()

    const response = await fetch(`${baseUrl}/card_tables/cards/${cardId}.json`, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'User-Agent': 'Coday (https://github.com/whoz-oss/coday)',
      },
    })

    if (!response.ok) {
      return `Error fetching card: ${response.status} ${response.statusText}`
    }

    const card = await response.json()

    const creator = card.creator ? card.creator.name : 'Unknown'
    const createdAt = new Date(card.created_at).toLocaleString()
    const updatedAt = new Date(card.updated_at).toLocaleString()
    const dueOn = card.due_on || 'No due date'
    const assignees = (card.assignees || []).map((a: any) => a.name).join(', ') || 'Unassigned'
    const commentsCount = card.comment_count || 0
    const content = card.content ? card.content.replace(/<[^>]*>/g, '') : 'No content'
    const column = card.parent ? card.parent.title : 'Unknown column'

    const steps = (card.steps || []) as any[]
    let stepsSection = ''
    if (steps.length > 0) {
      const stepList = steps
        .map((s: any) => {
          const stepAssignees = (s.assignees || []).map((a: any) => a.name).join(', ') || 'Unassigned'
          const stepDue = s.due_on || 'No due date'
          return `  - [${s.completed ? 'x' : ' '}] **${s.title}** — Due: ${stepDue}, Assigned: ${stepAssignees}`
        })
        .join('\n')
      stepsSection = `\n\n## Steps\n\n${stepList}`
    }

    let result = `# ${card.title} (ID: ${card.id})

**Column:** ${column}
**Creator:** ${creator}
**Created:** ${createdAt}
**Updated:** ${updatedAt}
**Due:** ${dueOn}
**Assigned:** ${assignees}
**Completed:** ${card.completed ? 'Yes' : 'No'}
**Comments:** ${commentsCount}
**URL:** ${card.app_url}

## Content

${content}${stepsSection}`

    if (commentsCount > 0) {
      result += `\n\n_${commentsCount} comment(s) available — use getComments with recording ID ${card.id} to retrieve them._`
    }

    return result
  } catch (error: any) {
    return `Error: ${error.message}`
  }
}
