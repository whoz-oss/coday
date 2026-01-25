import axios from 'axios'
import { Interactor } from '@coday/model/interactor'

export async function retrieveZendeskArticle(
  articleId: string,
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
    interactor.displayText(`Retrieving Zendesk article ${articleId}...`)

    // Build URL - locale is optional for admins/agents
    const localeSegment = locale ? `/${locale}` : ''
    const url = `https://${zendeskSubdomain}.zendesk.com/api/v2/help_center${localeSegment}/articles/${articleId}.json`

    const response = await axios.get(url, {
      auth: {
        username: `${zendeskEmail}/token`,
        password: zendeskApiToken,
      },
      headers: {
        'Content-Type': 'application/json',
      },
    })

    interactor.displayText(`... received article from Zendesk.`)

    const article = response.data.article

    // Return article with key fields
    return {
      id: article.id,
      title: article.title,
      body: article.body, // HTML content
      author_id: article.author_id,
      section_id: article.section_id,
      locale: article.locale,
      created_at: article.created_at,
      updated_at: article.updated_at,
      html_url: article.html_url,
      draft: article.draft,
      promoted: article.promoted,
      vote_sum: article.vote_sum,
      comments_disabled: article.comments_disabled,
    }
  } catch (error: any) {
    interactor.warn(`Failed to retrieve Zendesk article ${articleId}`)
    return `Failed to retrieve article ${articleId}: ${error.message}`
  }
}
