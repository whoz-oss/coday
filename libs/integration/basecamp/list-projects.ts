import { BasecampOAuth } from './basecamp-oauth'

export async function listBasecampProjects(oauth: BasecampOAuth): Promise<string> {
  try {
    // S'assurer qu'on est authentifié
    if (!oauth.isAuthenticated()) {
      await oauth.authenticate()
    }

    const accessToken = await oauth.getAccessToken()
    const baseUrl = oauth.getApiBaseUrl()

    const response = await fetch(`${baseUrl}/projects.json`, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'User-Agent': 'Coday (https://github.com/whoz-oss/coday)',
      },
    })

    if (!response.ok) {
      return `Error fetching projects: ${response.status} ${response.statusText}`
    }

    const projects = await response.json()

    if (projects.length === 0) {
      return 'No projects found in this Basecamp account.'
    }

    const projectList = projects
      .map((p: any) => `- ${p.name} (ID: ${p.id})${p.description ? `: ${p.description}` : ''}`)
      .join('\n')

    return `Found ${projects.length} project(s):\n${projectList}`
  } catch (error: any) {
    return `Error: ${error.message}`
  }
}
