import { MessageContent } from '@coday/coday-events'

export type FileContent =
  | {
      type: 'error' | 'text'
      content: string | Buffer
    }
  | MessageContent
