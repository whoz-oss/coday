import axios from 'axios'
import TurndownService from 'turndown'
import { Interactor } from '@coday/model/interactor'

// Initialize Turndown service for HTML to Markdown conversion
const turndownService = new TurndownService({
  headingStyle: 'atx',
  codeBlockStyle: 'fenced',
  bulletListMarker: '-',
})

// Remove unnecessary elements that add noise
turndownService.remove(['script', 'style', 'nav', 'footer'])

/**
 * Convert HTML to Markdown with timeout protection against ReDoS (CVE-2025-9670)
 * @param html - HTML content to convert
 * @param timeoutMs - Maximum time allowed for conversion (default: 2000ms)
 * @returns Markdown string or error message
 */
async function convertHtmlToMarkdown(html: string, timeoutMs: number = 2000): Promise<string> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error(`HTML to Markdown conversion timed out after ${timeoutMs}ms (possible ReDoS attack)`))
    }, timeoutMs)

    try {
      const markdown = turndownService.turndown(html)
      clearTimeout(timer)
      resolve(markdown)
    } catch (error) {
      clearTimeout(timer)
      reject(error)
    }
  })
}

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

    // Convert HTML to Markdown with timeout protection (CVE-2025-9670 mitigation)
    let markdownContent: string
    try {
      markdownContent = await convertHtmlToMarkdown(htmlContent, 2000)
    } catch (error: any) {
      interactor.warn(`HTML conversion failed or timed out: ${error.message}`)
      // Fallback: return sanitized HTML or plain text
      const plainText = htmlContent
        .replace(/<[^>]+>/g, ' ')
        .replace(/\s+/g, ' ')
        .trim()
      return `# Confluence Page ${pageId} (conversion failed)

**Note:** HTML to Markdown conversion failed. Showing plain text instead.

${plainText.substring(0, 10000)}...`
    }

    // Add page metadata as header
    const pageTitle = response.data.title || 'Untitled'
    const pageUrl = `${confluenceBaseUrl}/wiki/pages/${pageId}`

    const result = `# ${pageTitle}

**Source:** [${pageUrl}](${pageUrl})

---

${markdownContent}`

    interactor.debug(
      `✓ Successfully converted Confluence page to Markdown, from ${htmlContent?.length} to ${result.length} chars.`
    )
    return result
  } catch (error: any) {
    interactor.warn(`Failed to retrieve Confluence page`)
    return `Failed to retrieve Confluence page with ID ${pageId}: ${error.message}`
  }
}
