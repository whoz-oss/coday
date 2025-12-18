import axios from 'axios'
import { Interactor } from '../../model'

export async function searchZendeskArticles(
  query: string,
  zendeskSubdomain: string,
  zendeskEmail: string,
  zendeskApiToken: string,
  interactor: Interactor,
  locale?: string
): Promise<any> {
  if (!zendeskSubdomain || !zendeskEmail || !zendeskApiToken) {
    throw new Error('Zendesk integration incorrectly set')
  }

  try {
    interactor.displayText(`Searching Zendesk articles for query: "${query}"...`)

    // Build URL with query parameters
    const baseUrl = `https://${zendeskSubdomain}.zendesk.com/api/v2/help_center/articles/search.json`
    const params = new URLSearchParams()
    params.append('query', query)
    if (locale) {
      params.append('locale', locale)
    }

    const url = `${baseUrl}?${params.toString()}`

    const response = await axios.get(url, {
      auth: {
        username: `${zendeskEmail}/token`,
        password: zendeskApiToken,
      },
      headers: {
        'Content-Type': 'application/json',
      },
    })

    interactor.displayText(`... received ${response.data.results?.length || 0} search results from Zendesk.`)

    // Return simplified results with key information
    return response.data.results.map((article: any) => ({
      id: article.id,
      title: article.title,
      snippet: article.snippet?.replace(/<\/?em>/g, ''), // Remove <em> tags from snippet
      url: article.html_url,
      locale: article.locale,
      updated_at: article.updated_at,
    }))
  } catch (error: any) {
    interactor.warn(`Failed to search Zendesk articles`)
    return `Failed to perform search: "${query}": ${error.message}`
  }
}
