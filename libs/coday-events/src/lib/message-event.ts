import { CodayEvent } from './coday-events'

export type TextContent = {
  type: 'text'
  content: string
}

export type ImageContent = {
  type: 'image'
  content: string
  mimeType: 'image/jpeg' | 'image/png' | 'image/gif' | 'image/webp'
  source?: string
  width?: number
  height?: number
}

export type MessageContent = TextContent | ImageContent

export class MessageEvent extends CodayEvent {
  role: 'user' | 'assistant'
  name: string
  content: MessageContent[]
  static override type = 'message'

  constructor(event: Partial<MessageEvent>) {
    super(event, MessageEvent.type)
    this.role = event.role!
    this.name = event.name!
    this.content = event.content!

    this.length = this.content
      .map((content) => {
        if (content.type === 'text') {
          return content.content.length
        }
        if (content.type === 'image') {
          const tokens = ((content.width || 0) * (content.height || 0)) / 750
          return tokens ? tokens * 3.5 : content.content.length
        }
        return 0
      })
      .reduce((sum, length) => sum + length, 0)
  }
}
