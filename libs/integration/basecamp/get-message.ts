import { BasecampOAuth } from './basecamp-oauth'

export async function getBasecampMessage(oauth: BasecampOAuth, projectId: number, messageId: number): Promise<string> {
  try {
    if (!oauth.isAuthenticated()) {
      await oauth.authenticate()
    }

    const accessToken = await oauth.getAccessToken()
    const baseUrl = oauth.getApiBaseUrl()

    const response = await fetch(`${baseUrl}/buckets/${projectId}/messages/${messageId}.json`, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'User-Agent': 'Coday (https://github.com/whoz-oss/coday)',
      },
    })

    if (!response.ok) {
      return `Error fetching message: ${response.status} ${response.statusText}`
    }

    const message = await response.json()

    const creator = message.creator ? message.creator.name : 'Unknown'
    const createdAt = new Date(message.created_at).toLocaleString()
    const updatedAt = new Date(message.updated_at).toLocaleString()
    const commentsCount = message.comments_count || 0

    const content = message.content ? message.content.replace(/<[^>]*>/g, '') : 'No content'

    let result = `# ${message.title}

**Author:** ${creator}
**Created:** ${createdAt}
**Updated:** ${updatedAt}
**Comments:** ${commentsCount}
**URL:** ${message.app_url}

## Content

${content}`

    // Ajouter les commentaires s'il y en a
    if (commentsCount > 0 && message.comments_url) {
      result += `\n\n## Comments

To get comments, use the URL: ${message.comments_url}`
    }

    return result
  } catch (error: any) {
    return `Error: ${error.message}`
  }
}
