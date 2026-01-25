import { MessageContent } from './coday-events'

export type FileContent =
  | {
      type: 'error' | 'text'
      content: string | Buffer
    }
  | MessageContent
