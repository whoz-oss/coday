export interface FileContent {
  type: 'text' | 'image' | 'binary' | 'error'
  content: string | Buffer
}

// Helper functions for backward compatibility
export const isTextContent = (content: FileContent): content is FileContent & { content: string } => {
  return content.type === 'text' && typeof content.content === 'string'
}

export const getTextContent = (content: FileContent): string => {
  if (content.type === 'error') {
    return content.content as string
  }
  if (isTextContent(content)) {
    return content.content
  }
  return `[${content.type.toUpperCase()} CONTENT]`
}