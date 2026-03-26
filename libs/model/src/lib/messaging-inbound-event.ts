/**
 * Generic inbound event from any messaging platform.
 * Platform-specific connectors resolve identities and enrich context
 * before sending this event to the messaging gateway.
 */
export interface MessagingInboundEvent {
  /** Identifies the messaging platform (e.g., "SLACK", "DISCORD") */
  source: string

  /** Coday username (email), already resolved by the connector */
  username: string

  /** The message text from the user */
  message: string

  /** Project name to execute in */
  projectName: string

  /**
   * Opaque context for the agent to reply in the right place.
   * The gateway does not interpret this — it passes it through to the agent.
   * For Slack: { channel: string, thread_ts?: string }
   * For Discord: { channelId: string, messageId?: string }
   */
  replyContext: Record<string, string>

  /** Hint about what triggered this event, e.g., "mentioned_in_channel", "direct_message" */
  eventType?: string

  /**
   * Recent conversation context since the agent's last intervention.
   * Formatted as a readable transcript by the connector.
   */
  conversationContext?: string

  /**
   * Optional: name of the agent to target for this event.
   * When set, the gateway prefixes the prompt with @AgentName to route to the right agent.
   * For Slack events, this would be 'Slackay'.
   */
  targetAgent?: string
}
