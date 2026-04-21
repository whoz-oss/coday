import axios from 'axios'
import { Interactor } from '@coday/model'

export async function listZendeskArticlesInSection(
  sectionId: string,
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
    interactor.displayText(`Listing Zendesk articles in section ${sectionId}...`)

    const localeSegment = locale ? `/${locale}` : ''
    const url = `https://${zendeskSubdomain}.zendesk.com/api/v2/help_center${localeSegment}/sections/${sectionId}/articles.json`

    const response = await axios.get(url, {
      auth: {
        username: `${zendeskEmail}/token`,
        password: zendeskApiToken,
      },
      headers: {
        'Content-Type': 'application/json',
      },
    })

    const articles = response.data.articles
    interactor.displayText(`... received ${articles?.length || 0} articles from Zendesk.`)

    return articles.map((article: any) => ({
      id: article.id,
      title: article.title,
      locale: article.locale,
      url: article.html_url,
      section_id: article.section_id,
      updated_at: article.updated_at,
    }))
  } catch (error: any) {
    interactor.warn(`Failed to list Zendesk articles in section ${sectionId}`)
    return `Failed to list articles in section ${sectionId}: ${error.message}`
  }
}
