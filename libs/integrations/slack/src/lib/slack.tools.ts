// Slack API Types
import { CodayTool } from '@coday/model'
import { AssistantToolFactory } from '@coday/model'
import { Interactor } from '@coday/model'
import { IntegrationService } from '@coday/service'
import { FunctionTool } from '@coday/model'

interface SlackChannel {
  id: string
  name: string
  is_private: boolean
  is_member: boolean
  topic?: { value: string }
  purpose?: { value: string }
  num_members?: number
}

interface SlackUser {
  id: string
  name: string
  real_name?: string
  deleted: boolean
  is_bot: boolean
}

interface SlackMessage {
  type: string
  user?: string
  text: string
  ts: string
  thread_ts?: string
  reply_count?: number
}

interface SlackApiResponse {
  ok: boolean
  error?: string

  [key: string]: unknown
}

interface SlackChannelsResponse extends SlackApiResponse {
  channels?: SlackChannel[]
  response_metadata?: { next_cursor?: string }
}

interface SlackUsersResponse extends SlackApiResponse {
  members?: SlackUser[]
  response_metadata?: { next_cursor?: string }
}

interface SlackHistoryResponse extends SlackApiResponse {
  messages?: SlackMessage[]
  has_more?: boolean
  response_metadata?: { next_cursor?: string }
}

interface SlackRepliesResponse extends SlackApiResponse {
  messages?: SlackMessage[]
  has_more?: boolean
}

interface SlackPostMessageResponse extends SlackApiResponse {
  channel?: string
  ts?: string
  message?: SlackMessage
}

// User cache for mention resolution
const userCache: Map<string, SlackUser> = new Map()
let userCachePopulated = false

export class SlackTools extends AssistantToolFactory {
  static readonly TYPE = 'SLACK' as const

  constructor(
    interactor: Interactor,
    private readonly integrationService: IntegrationService,
    instanceName: string,
    config?: any
  ) {
    super(interactor, instanceName, config)
  }

  protected async buildTools(): Promise<CodayTool[]> {
    const result: CodayTool[] = []
    if (!this.integrationService.hasIntegration(this.name)) {
      console.log('failed to have integration slack')
      return result
    }

    const botToken = this.integrationService.getApiKey(this.name)
    if (!botToken) {
      console.log('no bot token')
      return result
    }

    // Helper function for Slack API calls
    const slackApiCall = async <T extends SlackApiResponse>(
      method: string,
      params: Record<string, string> = {},
      httpMethod: 'GET' | 'POST' = 'GET',
      body?: Record<string, unknown>
    ): Promise<T> => {
      const url = new URL(`https://slack.com/api/${method}`)

      if (httpMethod === 'GET') {
        Object.entries(params).forEach(([key, value]) => {
          url.searchParams.append(key, value)
        })
      }

      const options: RequestInit = {
        method: httpMethod,
        headers: {
          Authorization: `Bearer ${botToken}`,
          'Content-Type':
            httpMethod === 'POST' ? 'application/json; charset=utf-8' : 'application/x-www-form-urlencoded',
        },
      }

      if (httpMethod === 'POST' && body) {
        options.body = JSON.stringify(body)
      }

      const response = await fetch(url.toString(), options)
      const data = (await response.json()) as T

      if (!data.ok) {
        throw new Error(`Slack API error: ${data.error || 'Unknown error'}`)
      }

      return data
    }

    // Helper to populate user cache
    const populateUserCache = async (): Promise<void> => {
      if (userCachePopulated) return

      try {
        let cursor: string | undefined
        do {
          const params: Record<string, string> = { limit: '200' }
          if (cursor) params.cursor = cursor

          const response = await slackApiCall<SlackUsersResponse>('users.list', params)

          response.members?.forEach((user) => {
            if (!user.deleted) {
              userCache.set(user.id, user)
            }
          })

          cursor = response.response_metadata?.next_cursor
        } while (cursor)

        userCachePopulated = true
      } catch (error) {
        this.interactor.debug(`Failed to populate user cache: ${error}`)
      }
    }

    // Helper to resolve user mentions in text
    const resolveUserMentions = async (text: string): Promise<string> => {
      await populateUserCache()

      // Replace <@U123456> patterns with @username
      return text.replace(/<@([A-Z0-9]+)>/g, (match, userId) => {
        const user = userCache.get(userId)
        if (user) {
          return `@${user.real_name || user.name}`
        }
        return match
      })
    }

    // Tool 1: List channels
    const listChannelsFunction: FunctionTool<{ types?: string; limit?: number }> = {
      type: 'function',
      function: {
        name: `${this.name}_list_channels`,
        description:
          'List Slack channels where the bot is a member. Only returns channels the bot has been invited to and can actually read/write. Returns channel ID, name, topic, and member count.',
        parameters: {
          type: 'object',
          properties: {
            types: {
              type: 'string',
              description:
                'Comma-separated list of channel types. Options: public_channel, private_channel, mpim (group DM), im (direct message). Default: "public_channel,private_channel"',
            },
            limit: {
              type: 'number',
              description: 'Maximum number of channels to return (default: 100, max: 1000)',
            },
          },
        },
        parse: JSON.parse,
        function: async (params: { types?: string; limit?: number }): Promise<string> => {
          try {
            const apiParams: Record<string, string> = {
              types: params.types || 'public_channel,private_channel',
              limit: String(params.limit || 100),
              exclude_archived: 'true',
            }

            // Use users.conversations to get only channels where bot is member
            const response = await slackApiCall<SlackChannelsResponse>('users.conversations', apiParams)

            const channels = response.channels || []
            if (channels.length === 0) {
              return 'No channels found. The bot may need to be invited to channels first.'
            }

            const channelList = channels.map((ch) => ({
              id: ch.id,
              name: ch.name,
              private: ch.is_private,
              topic: ch.topic?.value || '',
              purpose: ch.purpose?.value || '',
              members: ch.num_members,
            }))

            return JSON.stringify(channelList, null, 2)
          } catch (error) {
            return `Error listing channels: ${error instanceof Error ? error.message : String(error)}`
          }
        },
      },
    }

    // Tool 2: Read channel messages
    const readChannelFunction: FunctionTool<{ channel: string; limit?: number; oldest?: string; latest?: string }> = {
      type: 'function',
      function: {
        name: `${this.name}_read_channel`,
        description:
          'Read messages from a Slack channel. Returns messages with timestamps, user mentions resolved to names, and thread indicators. Use slack_list_channels first to get channel IDs. The channel parameter is required.',
        parameters: {
          type: 'object',
          properties: {
            channel: {
              type: 'string',
              description: 'Channel ID (e.g., C1234567890). Get this from slack_list_channels. REQUIRED.',
            },
            limit: {
              type: 'number',
              description: 'Number of messages to return (default: 20, max: 100)',
            },
            oldest: {
              type: 'string',
              description: 'Only messages after this Unix timestamp (e.g., "1234567890.123456")',
            },
            latest: {
              type: 'string',
              description: 'Only messages before this Unix timestamp',
            },
          },
        },
        parse: JSON.parse,
        function: async (params: {
          channel: string
          limit?: number
          oldest?: string
          latest?: string
        }): Promise<string> => {
          try {
            if (!params.channel) {
              return 'Error: channel parameter is required. Use slack_list_channels to get available channel IDs.'
            }

            const apiParams: Record<string, string> = {
              channel: params.channel,
              limit: String(params.limit || 20),
            }
            if (params.oldest) apiParams.oldest = params.oldest
            if (params.latest) apiParams.latest = params.latest

            const response = await slackApiCall<SlackHistoryResponse>('conversations.history', apiParams)

            const messages = response.messages || []
            if (messages.length === 0) {
              return 'No messages found in this channel.'
            }

            // Resolve user mentions and format messages
            const formattedMessages = await Promise.all(
              messages.map(async (msg) => {
                const user = msg.user ? userCache.get(msg.user) : undefined
                const resolvedText = await resolveUserMentions(msg.text)

                return {
                  timestamp: msg.ts,
                  user: user?.real_name || user?.name || msg.user || 'Unknown',
                  text: resolvedText,
                  hasThread: !!msg.thread_ts && msg.reply_count && msg.reply_count > 0,
                  replyCount: msg.reply_count || 0,
                }
              })
            )

            // Reverse to show oldest first
            formattedMessages.reverse()

            return JSON.stringify(formattedMessages, null, 2)
          } catch (error) {
            return `Error reading channel: ${error instanceof Error ? error.message : String(error)}`
          }
        },
      },
    }

    // Tool 3: Read thread replies
    const readThreadFunction: FunctionTool<{ channel: string; thread_ts: string; limit?: number }> = {
      type: 'function',
      function: {
        name: `${this.name}_read_thread`,
        description:
          'Read replies in a Slack thread. Use the timestamp from slack_read_channel to identify the thread. Returns all messages in the thread including the parent message. Both channel and thread_ts parameters are required.',
        parameters: {
          type: 'object',
          properties: {
            channel: {
              type: 'string',
              description: 'Channel ID where the thread is located. REQUIRED.',
            },
            thread_ts: {
              type: 'string',
              description: 'Timestamp of the parent message (thread_ts from slack_read_channel). REQUIRED.',
            },
            limit: {
              type: 'number',
              description: 'Maximum number of replies to return (default: 50, max: 100)',
            },
          },
        },
        parse: JSON.parse,
        function: async (params: { channel: string; thread_ts: string; limit?: number }): Promise<string> => {
          try {
            if (!params.channel || !params.thread_ts) {
              return 'Error: both channel and thread_ts parameters are required.'
            }

            const apiParams: Record<string, string> = {
              channel: params.channel,
              ts: params.thread_ts,
              limit: String(params.limit || 50),
            }

            const response = await slackApiCall<SlackRepliesResponse>('conversations.replies', apiParams)

            const messages = response.messages || []
            if (messages.length === 0) {
              return 'No messages found in this thread.'
            }

            // Resolve user mentions and format messages
            const formattedMessages = await Promise.all(
              messages.map(async (msg) => {
                const user = msg.user ? userCache.get(msg.user) : undefined
                const resolvedText = await resolveUserMentions(msg.text)

                return {
                  timestamp: msg.ts,
                  user: user?.real_name || user?.name || msg.user || 'Unknown',
                  text: resolvedText,
                  isParent: msg.ts === params.thread_ts,
                }
              })
            )

            return JSON.stringify(formattedMessages, null, 2)
          } catch (error) {
            return `Error reading thread: ${error instanceof Error ? error.message : String(error)}`
          }
        },
      },
    }

    // Tool 4: Post message
    const postMessageFunction: FunctionTool<{ channel: string; text: string; thread_ts?: string }> = {
      type: 'function',
      function: {
        name: `${this.name}_post_message`,
        description:
          'Post a message to a Slack channel or DM. Can also reply to a thread by providing thread_ts. The bot must be a member of the channel to post. Both channel and text parameters are required.\n\n' +
          '## Message Posting Rules\n' +
          '- When interacting with a user in Slack, prefer a direct message with the user unless asked otherwise or called from a channel.\n' +
          '- To mention a user, use `<@firstname.lastname>` or `<@id_of_user>`.\n' +
          '- Always keep it short, under 10 lines of text.\n\n' +
          '## Slack Message Formatting Constraints\n\n' +
          '**What works:**\n' +
          '- `*text*` for bold (single asterisk)\n' +
          '- `_text_` for italic\n' +
          '- `` `code` `` for inline code\n' +
          '- ` ``` code block ``` ` for code blocks\n' +
          '- Bullet lists with `-`\n' +
          '- Numbered lists with `1. 2. 3.`\n' +
          '- Direct URLs (automatically clickable): https://example.com\n' +
          '- Emojis\n\n' +
          '**Link formatting:**\n' +
          '- To display text with a link: `<https://example.com|Link text>` (pipe separator)\n' +
          '- Example: `<https://github.com/user/repo|View repository>` displays as "View repository" (clickable)\n' +
          '- Avoid posting raw URLs when you can use descriptive link text\n\n' +
          '**Best practices for Slack:**\n' +
          '- Use single asterisks `*text*` for emphasis/titles\n' +
          '- Prefer descriptive link text over raw URLs for better readability\n' +
          '- For multiple links, use link text format to keep messages clean',
        parameters: {
          type: 'object',
          properties: {
            channel: {
              type: 'string',
              description: 'Channel ID to post to. For DMs, use the user ID or DM channel ID. REQUIRED.',
            },
            text: {
              type: 'string',
              description: 'Message text to post. Supports Slack markdown (bold, italic, links, etc.). REQUIRED.',
            },
            thread_ts: {
              type: 'string',
              description: 'Optional: timestamp of parent message to reply in thread',
            },
          },
        },
        parse: JSON.parse,
        function: async (params: { channel: string; text: string; thread_ts?: string }): Promise<string> => {
          try {
            if (!params.channel || !params.text) {
              return 'Error: both channel and text parameters are required.'
            }

            const body: Record<string, unknown> = {
              channel: params.channel,
              text: params.text,
            }
            if (params.thread_ts) {
              body.thread_ts = params.thread_ts
            }

            const response = await slackApiCall<SlackPostMessageResponse>('chat.postMessage', {}, 'POST', body)

            return JSON.stringify({
              success: true,
              channel: response.channel,
              timestamp: response.ts,
              message: 'Message posted successfully',
            })
          } catch (error) {
            return `Error posting message: ${error instanceof Error ? error.message : String(error)}`
          }
        },
      },
    }

    // Tool 5: List users (for reference)
    const listUsersFunction: FunctionTool<{ limit?: number }> = {
      type: 'function',
      function: {
        name: `${this.name}_list_users`,
        description:
          'List Slack workspace users. Useful for finding user IDs to send DMs or understanding who is mentioned in messages.',
        parameters: {
          type: 'object',
          properties: {
            limit: {
              type: 'number',
              description: 'Maximum number of users to return (default: 100)',
            },
          },
        },
        parse: JSON.parse,
        function: async (params: { limit?: number }): Promise<string> => {
          try {
            await populateUserCache()

            const users = Array.from(userCache.values())
              .filter((u) => !u.is_bot)
              .slice(0, params.limit || 100)
              .map((u) => ({
                id: u.id,
                name: u.name,
                realName: u.real_name,
              }))

            return JSON.stringify(users, null, 2)
          } catch (error) {
            return `Error listing users: ${error instanceof Error ? error.message : String(error)}`
          }
        },
      },
    }

    result.push(listChannelsFunction, readChannelFunction, readThreadFunction, postMessageFunction, listUsersFunction)

    return result
  }
}
