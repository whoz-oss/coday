import { Migration } from './data-migration'

const messageEventToMessageContent: Migration = {
  version: 1,
  description: 'Migrate AiThread MessageEvent.content from string to MessageContent',
  migrate: (aiThread: any) => {
    const migrated = { ...aiThread }
    // Ensure messages array exists before mapping
    if (!migrated.messages || !Array.isArray(migrated.messages)) {
      migrated.messages = []
      return migrated
    }

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

const addStarringField: Migration = {
  version: 2,
  description: 'Add starring field to threads for favorite functionality',
  migrate: (aiThread: any) => {
    const migrated = { ...aiThread }
    // Initialize starring as empty array if not present
    if (!migrated.starring) {
      migrated.starring = []
    }
    return migrated
  },
}

const addUsersField: Migration = {
  version: 3,
  description: 'Add users field to threads, initialized from username for retro-compatibility',
  migrate: (aiThread: any) => {
    const migrated = { ...aiThread }
    // Initialize users from username if not present
    if (!migrated.users) {
      migrated.users = migrated.username ? [{ userId: migrated.username }] : []
    }
    // Normalize: if users contains strings (from earlier dev iteration), convert to objects
    if (Array.isArray(migrated.users)) {
      migrated.users = migrated.users.map((u: any) => (typeof u === 'string' ? { userId: u } : u))
    }
    return migrated
  },
}

export const aiThreadMigrations: Migration[] = [messageEventToMessageContent, addStarringField, addUsersField]
