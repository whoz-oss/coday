/**
 * Validates that a string is a well-formed http(s) URL suitable for use as
 * the AgentOS proxy target.
 *
 * `new URL()` alone is too permissive: `new URL('localhost:8124')` succeeds
 * by treating `localhost:` as the scheme, which is the most likely migration
 * mistake when a user copies a bare hostname from the old AGENTOS_HOSTNAME var.
 * This helper adds an explicit protocol and hostname assertion.
 *
 * A pathname starting with '//' signals a doubled scheme
 * (e.g. 'http://http://localhost' parses as hostname='http', pathname='//localhost').
 *
 * Throws an Error with a descriptive message if the URL is invalid.
 */
export function validateAgentOsUrl(url: string): void {
  let parsed: URL
  try {
    parsed = new URL(url)
  } catch {
    throw new Error(
      `Invalid AGENTOS_URL: "${url}". Must be a complete URL including scheme, e.g. "http://localhost:8124".`
    )
  }
  if (!['http:', 'https:'].includes(parsed.protocol) || !parsed.hostname || parsed.pathname.startsWith('//')) {
    throw new Error(
      `Invalid AGENTOS_URL: "${url}". Expected an http(s) URL with a hostname, e.g. "http://localhost:8124".`
    )
  }
}
