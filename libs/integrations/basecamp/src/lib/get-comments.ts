import { BasecampOAuth } from './basecamp-oauth'

export async function getBasecampComments(oauth: BasecampOAuth, recordingId: number, page?: number): Promise<string> {
  try {
    if (!oauth.isAuthenticated()) {
      await oauth.authenticate()
    }

    const accessToken = await oauth.getAccessToken()
    const baseUrl = oauth.getApiBaseUrl()

    const url = page
      ? `${baseUrl}/recordings/${recordingId}/comments.json?page=${page}`
      : `${baseUrl}/recordings/${recordingId}/comments.json`

    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'User-Agent': 'Coday (https://github.com/whoz-oss/coday)',
      },
    })

    if (!response.ok) {
      return `Error fetching comments: ${response.status} ${response.statusText}`
    }

    const comments = await response.json()

    const totalCount = response.headers.get('X-Total-Count')
    const linkHeader = response.headers.get('Link')
    let nextPage: number | null = null

    if (linkHeader) {
      const nextMatch = linkHeader.match(/page=(\d+)>; rel="next"/)
      if (nextMatch && nextMatch[1]) {
        nextPage = parseInt(nextMatch[1], 10)
      }
    }

    if (comments.length === 0) {
      return 'No comments found for this recording.'
    }

    const commentList = comments
      .map((c: any) => {
        const creator = c.creator ? c.creator.name : 'Unknown'
        const createdAt = new Date(c.created_at).toLocaleString()
        const content = c.content ? c.content.replace(/<[^>]*>/g, '') : 'No content'
        return `- **${creator}** (${createdAt}) [ID: ${c.id}]\n  ${content}`
      })
      .join('\n\n')

    let result = `Found ${comments.length} comment(s) on this page`
    if (totalCount) {
      result += ` (Total: ${totalCount})`
    }
    if (page) {
      result += ` [Page ${page}]`
    }
    result += `:\n\n${commentList}`

    if (nextPage) {
      result += `\n\n📄 More results available. Use page=${nextPage} to get the next page.`
    }

    return result
  } catch (error: any) {
    return `Error: ${error.message}`
  }
}
