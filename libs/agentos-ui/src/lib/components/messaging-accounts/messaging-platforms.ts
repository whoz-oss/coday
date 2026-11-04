import { MessagingPlatform } from '@whoz-oss/agentos-api-client'

const NAMES: Record<MessagingPlatform, string> = {
  [MessagingPlatform.TELEGRAM]: 'Telegram',
}

/** The name a user knows a messaging platform by. */
export function messagingPlatformName(platform: MessagingPlatform): string {
  return NAMES[platform] ?? platform
}
