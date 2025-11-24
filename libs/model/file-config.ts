/**
 * File configuration for thread file uploads and management
 */

/**
 * Maximum file size in bytes (10 MB)
 */
export const MAX_FILE_SIZE = 10 * 1024 * 1024

/**
 * Allowed file extensions for thread file uploads
 * These are file types that can be read at least partially by the system
 */
export const ALLOWED_FILE_EXTENSIONS = [
  // Documents
  '.pdf',
  '.doc',
  '.docx',
  '.odt',
  '.rtf',

  // Spreadsheets
  '.xls',
  '.xlsx',
  '.ods',
  '.csv',
  '.tsv',

  // Text & Markup
  '.txt',
  '.md',
  '.markdown',
  '.rst',

  // Data formats
  '.json',
  '.xml',
  '.yaml',
  '.yml',
  '.toml',

  // Web
  '.html',
  '.htm',
  '.css',
  '.svg',

  // Code source (common languages)
  '.ts',
  '.tsx',
  '.js',
  '.jsx',
  '.py',
  '.java',
  '.cpp',
  '.c',
  '.h',
  '.hpp',
  '.cs',
  '.go',
  '.rs',
  '.rb',
  '.php',
  '.swift',
  '.kt',
  '.scala',
  '.sh',
  '.bash',
  '.sql',

  // Configuration
  '.env',
  '.properties',
  '.ini',
  '.conf',
  '.config',

  // Logs
  '.log',
] as const

/**
 * Check if a file extension is allowed
 * @param filename The filename to check
 * @returns true if the extension is allowed
 */
export function isFileExtensionAllowed(filename: string): boolean {
  const ext = filename.toLowerCase().substring(filename.lastIndexOf('.'))
  return ALLOWED_FILE_EXTENSIONS.includes(ext as any)
}

/**
 * Get a human-readable string of allowed extensions
 * @returns Comma-separated list of extensions
 */
export function getAllowedExtensionsString(): string {
  return ALLOWED_FILE_EXTENSIONS.join(', ')
}
