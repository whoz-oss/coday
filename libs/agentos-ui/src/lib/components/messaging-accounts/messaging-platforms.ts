import { MessagingPlatform } from '@whoz-oss/agentos-api-client'

const NAMES: Record<MessagingPlatform, string> = {
  [MessagingPlatform.TELEGRAM]: 'Telegram',
  [MessagingPlatform.SLACK]: 'Slack',
}

/** The platforms an agent can have a bot on, in the order the agent's page shows them. */
export const MESSAGING_PLATFORMS: MessagingPlatform[] = [MessagingPlatform.TELEGRAM, MessagingPlatform.SLACK]

/** The name a user knows a messaging platform by. */
export function messagingPlatformName(platform: MessagingPlatform): string {
  return NAMES[platform] ?? platform
}
