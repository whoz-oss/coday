/**
 * Gravatar helpers.
 *
 * Gravatar identifies an avatar by the SHA-256 of the trimmed, lower-cased email, so the email itself is
 * never sent, only its hash.
 */

const GRAVATAR_BASE_URL = 'https://gravatar.com/avatar'

/**
 * Gravatar URL for an email, or null when it cannot be computed (no email, or no Web Crypto: `crypto.subtle`
 * is only available in secure contexts, i.e. https or localhost).
 *
 * `d=404` makes Gravatar answer 404 for an email without an avatar, so the `<img>` fails and the caller
 * can fall back to the initials instead of showing Gravatar's default picture.
 *
 * @param email address of the user
 * @param size rendered size in CSS pixels; the image is requested at twice that size for high-DPI screens
 */
export async function gravatarUrl(email: string | null | undefined, size: number): Promise<string | null> {
  const normalized = email?.trim().toLowerCase()
  if (!normalized || !globalThis.crypto?.subtle) return null

  const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(normalized))
  const hash = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${GRAVATAR_BASE_URL}/${hash}?s=${size * 2}&d=404`
}
