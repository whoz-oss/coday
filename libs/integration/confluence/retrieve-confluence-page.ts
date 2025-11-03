import axios from 'axios'
import TurndownService from 'turndown'
import { Interactor } from '../../model'

// Initialize Turndown service for HTML to Markdown conversion
const turndownService = new TurndownService({
  headingStyle: 'atx',
  codeBlockStyle: 'fenced',
  bulletListMarker: '-',
})

// Remove unnecessary elements that add noise
turndownService.remove(['script', 'style', 'nav', 'footer'])

export async function retrieveConfluencePage(
  pageId: string,
  confluenceBaseUrl: string,
  confluenceApiToken: string,
  confluenceUsername: string,
  interactor: Interactor
): Promise<string> {
  if (!confluenceBaseUrl || !confluenceApiToken || !confluenceUsername) {
    throw new Error('Confluence integration incorrectly set')
  }

  try {
    const response = await axios.get(`${confluenceBaseUrl}/wiki/api/v2/pages/${pageId}?body-format=view`, {
      auth: {
        username: confluenceUsername,
        password: confluenceApiToken,
      },
    })

    // Extract HTML content from response
    const htmlContent = response.data.body?.view?.value || ''

    if (!htmlContent) {
      return `No content found for Confluence page ${pageId}`
    }

    // Convert HTML to Markdown
    const markdownContent = turndownService.turndown(htmlContent)

    // Add page metadata as header
    const pageTitle = response.data.title || 'Untitled'
    const pageUrl = `${confluenceBaseUrl}/wiki/pages/${pageId}`

    const result = `# ${pageTitle}

**Source:** [${pageUrl}](${pageUrl})

---

${markdownContent}`
    interactor.debug(`formatted confluence output from ${htmlContent?.length} to ${result.length}`)
    return result
  } catch (error: any) {
    interactor.warn(`Failed to retrieve Confluence page`)
    return `Failed to retrieve Confluence page with ID ${pageId}: ${error.message}`
  }
}
