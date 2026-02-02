/**
 * Helper to ensure route parameter is a string
 * Express route params can be string | string[], this normalizes to string
 */
export function getParamAsString(param: string | string[] | undefined): string {
  if (Array.isArray(param)) {
    return param[0] || ''
  }
  return param || ''
}
