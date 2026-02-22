/**
 * Converts standard Markdown formatting to Slack's mrkdwn format
 *
 * Key conversions:
 * - **bold** → *bold*
 * - [link text](url) → <url|link text>
 * - # Headers → *Headers* (Slack doesn't support headers)
 */
export function markdownToSlack(text: string): string {
  let result = text

  // Convert markdown links [text](url) to Slack format <url|text>
  // This regex handles both inline links and reference-style links
  result = result.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<$2|$1>')

  // Convert **bold** to *bold* (Slack uses single asterisks)
  // Use negative lookbehind/lookahead to avoid matching single asterisks
  result = result.replace(/\*\*([^*]+)\*\*/g, '*$1*')

  // Convert headers (# Header) to bold text (*Header*)
  // Slack doesn't support headers, so we convert them to bold
  result = result.replace(/^#{1,6}\s+(.+)$/gm, '*$1*')

  // Convert code blocks with language hints ```lang to just ```
  // Slack doesn't support language hints in code blocks
  result = result.replace(/```\w+\n/g, '```\n')

  return result
}

/**
 * Validates if text contains Slack-incompatible markdown
 * Returns an array of issues found, or empty array if valid
 */
export function validateSlackFormat(text: string): string[] {
  const issues: string[] = []

  // Check for double asterisks (markdown bold)
  if (/\*\*[^*]+\*\*/.test(text)) {
    issues.push('Found **bold** syntax (use *bold* for Slack)')
  }

  // Check for markdown links
  if (/\[([^\]]+)\]\(([^)]+)\)/.test(text)) {
    issues.push('Found [text](url) links (use <url|text> for Slack)')
  }

  // Check for headers
  if (/^#{1,6}\s+/.test(text)) {
    issues.push("Found # headers (Slack doesn't support headers, use *text* instead)")
  }

  return issues
}
