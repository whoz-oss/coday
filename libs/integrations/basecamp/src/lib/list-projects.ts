import { BasecampOAuth } from './basecamp-oauth'

export async function listBasecampProjects(oauth: BasecampOAuth, page?: number): Promise<string> {
  try {
    if (!oauth.isAuthenticated()) {
      await oauth.authenticate()
    }

    const accessToken = await oauth.getAccessToken()
    const baseUrl = oauth.getApiBaseUrl()

    const url = page ? `${baseUrl}/projects.json?page=${page}` : `${baseUrl}/projects.json`

    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'User-Agent': 'Coday (https://github.com/whoz-oss/coday)',
      },
    })

    if (!response.ok) {
      return `Error fetching projects: ${response.status} ${response.statusText}`
    }

    const projects = await response.json()

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

    if (projects.length === 0) {
      return 'No projects found in this Basecamp account.'
    }

    const projectList = projects
      .map((p: any) => `- ${p.name} (ID: ${p.id})${p.description ? `: ${p.description}` : ''}`)
      .join('\n')

    let result = `Found ${projects.length} project(s) on this page`
    if (totalCount) {
      result += ` (Total: ${totalCount})`
    }
    if (page) {
      result += ` [Page ${page}]`
    }
    result += `:\n${projectList}`

    if (nextPage) {
      result += `\n\n📄 More results available. Use page=${nextPage} to get the next page.`
    }

    return result
  } catch (error: any) {
    return `Error: ${error.message}`
  }
}
