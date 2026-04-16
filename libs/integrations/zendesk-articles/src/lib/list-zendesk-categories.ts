import axios from 'axios'
import { Interactor } from '@coday/model'

export async function listZendeskCategories(
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
    interactor.displayText('Listing Zendesk categories...')

    const localeSegment = locale ? `/${locale}` : ''
    const url = `https://${zendeskSubdomain}.zendesk.com/api/v2/help_center${localeSegment}/categories.json`

    const response = await axios.get(url, {
      auth: {
        username: `${zendeskEmail}/token`,
        password: zendeskApiToken,
      },
      headers: {
        'Content-Type': 'application/json',
      },
    })

    const categories = response.data.categories
    interactor.displayText(`... received ${categories?.length || 0} categories from Zendesk.`)

    return categories.map((category: any) => ({
      id: category.id,
      name: category.name,
      description: category.description,
      locale: category.locale,
      url: category.html_url,
      position: category.position,
    }))
  } catch (error: any) {
    interactor.warn('Failed to list Zendesk categories')
    return `Failed to list categories: ${error.message}`
  }
}
