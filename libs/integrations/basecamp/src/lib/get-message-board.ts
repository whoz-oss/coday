import { BasecampOAuth } from './basecamp-oauth'

export async function getBasecampMessageBoard(oauth: BasecampOAuth, projectId: number): Promise<string> {
  try {
    if (!oauth.isAuthenticated()) {
      await oauth.authenticate()
    }

    const accessToken = await oauth.getAccessToken()
    const baseUrl = oauth.getApiBaseUrl()

    const projectResponse = await fetch(`${baseUrl}/projects/${projectId}.json`, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'User-Agent': 'Coday (https://github.com/whoz-oss/coday)',
      },
    })

    if (!projectResponse.ok) {
      return `Error fetching project: ${projectResponse.status} ${projectResponse.statusText}`
    }

    const project = await projectResponse.json()

    const messageBoard = project.dock?.find((item: any) => item.name === 'message_board')

    if (!messageBoard) {
      return `No message board found for project ${projectId}`
    }

    return `Message Board ID: ${messageBoard.id}

Use this ID with getBasecampMessages to retrieve messages.`
  } catch (error: any) {
    return `Error: ${error.message}`
  }
}
