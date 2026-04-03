import axios from 'axios'
import { Interactor } from '@coday/model'

export async function searchConfluencePages(
  query: string,
  confluenceBaseUrl: string,
  confluenceApiToken: string,
  confluenceUsername: string,
  interactor: Interactor
): Promise<any> {
  if (!confluenceBaseUrl || !confluenceApiToken || !confluenceUsername) {
    throw new Error('Confluence integration incorrectly set')
  }

  try {
    interactor.displayText(`Searching Confluence for query: "${query}"...`)
    const words = query.split(' ')
    const queryText = [...words.map((w) => `text ~ ${w}`), 'type = page'].join(' AND ')
    const url = `${confluenceBaseUrl}/wiki/rest/api/search?cql=${encodeURIComponent(queryText)}&limit=10&expand=body.editor2`
    const response = await axios.get(url, {
      auth: {
        username: confluenceUsername,
        password: confluenceApiToken,
      },
    })
    interactor.displayText(`... received search results from Confluence.`)

    // Map to a clean, minimal structure — the raw API response is very noisy
    return response.data.results.map((r: any) => ({
      id: r.content?.id,
      title: r.title,
      excerpt: r.excerpt?.replace(/@@@hl@@@|@@@endhl@@@/g, '').trim(),
      url: r.url,
      space: r.resultGlobalContainer?.title,
      lastModified: r.lastModified,
    }))
  } catch (error: any) {
    interactor.warn(`Failed to search Confluence content`)
    return `Failed to perform search: "${query}": ${error.message}`
  }
}
