import { BasecampOAuth } from './basecamp-oauth'

export async function getBasecampForwards(
  oauth: BasecampOAuth,
  projectId: number,
  inboxId: number,
  page?: number
): Promise<string> {
  try {
    if (!oauth.isAuthenticated()) {
      await oauth.authenticate()
    }

    const accessToken = await oauth.getAccessToken()
    const baseUrl = oauth.getApiBaseUrl()

    // Use project-scoped route (more reliable than flat route)
    const url = page
      ? `${baseUrl}/buckets/${projectId}/inboxes/${inboxId}/forwards.json?page=${page}`
      : `${baseUrl}/buckets/${projectId}/inboxes/${inboxId}/forwards.json`

    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'User-Agent': 'Coday (https://github.com/whoz-oss/coday)',
      },
    })

    if (!response.ok) {
      return `Error fetching forwards: ${response.status} ${response.statusText}`
    }

    const forwards = await response.json()

    const totalCount = response.headers.get('X-Total-Count')
    const linkHeader = response.headers.get('Link')
    let nextPage: number | null = null

    if (linkHeader) {
      const nextMatch = linkHeader.match(/page=(\d+)>; rel="next"/)
      if (nextMatch && nextMatch[1]) {
        nextPage = parseInt(nextMatch[1], 10)
      }
    }

    if (forwards.length === 0) {
      return 'No forwarded emails found in this inbox.'
    }

    const forwardList = forwards
      .map((f: any) => {
        const createdAt = new Date(f.created_at).toLocaleString()
        const repliesCount = f.replies_count || 0
        const contentPreview = f.content ? f.content.substring(0, 300) : 'No content'
        return `- **${f.subject || f.title}** (ID: ${f.id})\n  - Received: ${createdAt}\n  - Replies: ${repliesCount}\n  - Preview: ${contentPreview}${f.content && f.content.length > 300 ? '...' : ''}\n  - URL: ${f.app_url}`
      })
      .join('\n\n')

    let result = `Found ${forwards.length} forwarded email(s) on this page`
    if (totalCount) {
      result += ` (Total: ${totalCount})`
    }
    if (page) {
      result += ` [Page ${page}]`
    }
    result += `:\n\n${forwardList}`

    if (nextPage) {
      result += `\n\n📄 More results available. Use page=${nextPage} to get the next page.`
    }

    return result
  } catch (error: any) {
    return `Error: ${error.message}`
  }
}

export async function getBasecampForward(oauth: BasecampOAuth, projectId: number, forwardId: number): Promise<string> {
  try {
    if (!oauth.isAuthenticated()) {
      await oauth.authenticate()
    }

    const accessToken = await oauth.getAccessToken()
    const baseUrl = oauth.getApiBaseUrl()

    const response = await fetch(`${baseUrl}/buckets/${projectId}/inbox_forwards/${forwardId}.json`, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'User-Agent': 'Coday (https://github.com/whoz-oss/coday)',
      },
    })

    if (!response.ok) {
      return `Error fetching forward: ${response.status} ${response.statusText}`
    }

    const f = await response.json()

    const createdAt = new Date(f.created_at).toLocaleString()
    const repliesCount = f.replies_count || 0

    let result = `# ${f.subject || f.title}

**Received:** ${createdAt}
**From:** ${f.from || 'Unknown'}
**Replies:** ${repliesCount}
**URL:** ${f.app_url}

## Content

${f.content || 'No content'}`

    if (repliesCount > 0 && f.replies_url) {
      result += `\n\n_${repliesCount} reply/replies available — use getComments with recording ID ${f.id} to retrieve them._`
    }

    return result
  } catch (error: any) {
    return `Error: ${error.message}`
  }
}
