import axios from 'axios'
import { Interactor } from '@coday/model'

export async function listZendeskSections(
  categoryId: string,
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
    interactor.displayText(`Listing Zendesk sections for category ${categoryId}...`)

    const localeSegment = locale ? `/${locale}` : ''
    const url = `https://${zendeskSubdomain}.zendesk.com/api/v2/help_center${localeSegment}/categories/${categoryId}/sections.json`

    const response = await axios.get(url, {
      auth: {
        username: `${zendeskEmail}/token`,
        password: zendeskApiToken,
      },
      headers: {
        'Content-Type': 'application/json',
      },
    })

    const sections = response.data.sections
    interactor.displayText(`... received ${sections?.length || 0} sections from Zendesk.`)

    return sections.map((section: any) => ({
      id: section.id,
      name: section.name,
      description: section.description,
      locale: section.locale,
      url: section.html_url,
      category_id: section.category_id,
      position: section.position,
    }))
  } catch (error: any) {
    interactor.warn(`Failed to list Zendesk sections for category ${categoryId}`)
    return `Failed to list sections for category ${categoryId}: ${error.message}`
  }
}
