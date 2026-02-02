import { MessageContent } from '@coday/model'

export type FileContent =
  | {
      type: 'error' | 'text'
      content: string | Buffer
    }
  | MessageContent
