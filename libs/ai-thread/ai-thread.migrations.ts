// eslint-disable-next-line @nx/enforce-module-boundaries
import { Migration } from '../utils/data-migration'

const messageEventToMessageContent: Migration = {
  version: 1,
  description: 'Migrate AiThread MessageEvent.content from string to MessageContent',
  migrate: (aiThread: any) => {
    const migrated = { ...aiThread }
    migrated.messages = migrated.messages.map((message: any) => {
      if (message.type !== 'message') {
        return message
      }

      // now message is a MessageEvent
      const stringContent = message.content
      message.content = {
        ...message,
        content: [
          {
            type: 'text',
            content: stringContent,
          },
        ],
      }
      return message
    })
    return migrated
  },
}

export const aiThreadMigrations: Migration[] = [messageEventToMessageContent]
