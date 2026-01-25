/**
 * Utility functions for limiting output size and line count
 */

export interface OutputLimiterOptions {
  /** Maximum number of lines to keep (keeps the last N lines) */
  lineLimit: number
  /** Maximum number of characters to keep (keeps the last N characters) */
  charLimit: number
}

/**
 * Limits the output by both character count and line count
 *
 * @param output The text output to limit
 * @param options Configuration for limiting behavior
 * @returns The limited output string
 */
export const limitOutput = (output: string, options: OutputLimiterOptions): string => {
  const { lineLimit, charLimit } = options

  const truncated = limitOutputChars(output, charLimit)

  return limitOutputLines(truncated, lineLimit)
}

/**
 * Limits the output by line count only
 *
 * @param output The text output to limit
 * @param lineLimit Maximum number of lines to keep (keeps the last N lines)
 * @returns The limited output string
 */
export const limitOutputLines = (output: string, lineLimit: number): string => {
  const lines = output.split('\n')
  if (lines.length > lineLimit) {
    return lines.slice(-lineLimit).join('\n')
  }
  return output
}

/**
 * Limits the output by character count only
 *
 * @param output The text output to limit
 * @param charLimit Maximum number of characters to keep (keeps the last N characters)
 * @returns The limited output string
 */
export const limitOutputChars = (output: string, charLimit: number): string => {
  return output.length > charLimit ? output.slice(-charLimit) : output
}

// Default constants
export const DEFAULT_LINE_LIMIT = 1000
export const DEFAULT_CHAR_LIMIT = 50000
