import { BasecampOAuth } from './basecamp-oauth'

export async function getBasecampMessages(
  oauth: BasecampOAuth,
  projectId: number,
  messageBoardId: number,
  page?: number
): Promise<string> {
  try {
    if (!oauth.isAuthenticated()) {
      await oauth.authenticate()
    }

    const accessToken = await oauth.getAccessToken()
    const baseUrl = oauth.getApiBaseUrl()

    const url = page
      ? `${baseUrl}/buckets/${projectId}/message_boards/${messageBoardId}/messages.json?page=${page}`
      : `${baseUrl}/buckets/${projectId}/message_boards/${messageBoardId}/messages.json`

    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'User-Agent': 'Coday (https://github.com/whoz-oss/coday)',
      },
    })

    if (!response.ok) {
      return `Error fetching messages: ${response.status} ${response.statusText}`
    }

    const messages = await response.json()

    // Extract pagination info from headers
    const totalCount = response.headers.get('X-Total-Count')
    const linkHeader = response.headers.get('Link')
    let nextPage: number | null = null

    if (linkHeader) {
      const nextMatch = linkHeader.match(/page=(\d+)>; rel="next"/)
      if (nextMatch && nextMatch[1]) {
        nextPage = parseInt(nextMatch[1], 10)
      }
    }

    if (messages.length === 0) {
      return 'No messages found in this message board.'
    }

    const messageList = messages
      .map((msg: any) => {
        const creator = msg.creator ? msg.creator.name : 'Unknown'
        const createdAt = new Date(msg.created_at).toLocaleString()
        const commentsCount = msg.comments_count || 0

        const contentPreview = msg.content ? msg.content.replace(/<[^>]*>/g, '').substring(0, 200) : 'No content'

        return `- **${msg.title}** (ID: ${msg.id})
  - Author: ${creator}
  - Created: ${createdAt}
  - Comments: ${commentsCount}
  - Preview: ${contentPreview}${msg.content && msg.content.length > 200 ? '...' : ''}
  - URL: ${msg.app_url}`
      })
      .join('\n\n')

    let result = `Found ${messages.length} message(s) on this page`
    if (totalCount) {
      result += ` (Total: ${totalCount})`
    }
    if (page) {
      result += ` [Page ${page}]`
    }
    result += `:\n\n${messageList}`

    if (nextPage) {
      result += `\n\n📄 More results available. Use page=${nextPage} to get the next page.`
    }

    return result
  } catch (error: any) {
    return `Error: ${error.message}`
  }
}
