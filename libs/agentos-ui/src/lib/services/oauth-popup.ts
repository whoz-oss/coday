/** Window name shared by every OAuth authorization popup, so a second one reuses the first instead of piling up. */
export const OAUTH_POPUP_NAME = 'agentos_oauth_popup'

/**
 * Opens the OAuth authorization popup, centered on the current window.
 *
 * Must be called straight from a click handler, or the browser blocks it. When the authorization URL is
 * not known yet (it comes from the backend), open it blank with no argument and set `popup.location.href`
 * once it is.
 *
 * @returns the popup, or null when the browser blocked it
 */
export function openOAuthPopup(url = 'about:blank'): Window | null {
  const width = 600
  const height = 900
  const left = window.screenX + (window.outerWidth - width) / 2
  const top = window.screenY + (window.outerHeight - height) / 2
  const features = `width=${width},height=${height},left=${left},top=${top},popup=yes,resizable=yes,scrollbars=yes`
  return window.open(url, OAUTH_POPUP_NAME, features)
}
