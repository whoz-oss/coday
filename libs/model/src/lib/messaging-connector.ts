/**
 * Interface that platform-specific messaging connectors implement.
 * Provides outbound messaging capabilities to the gateway.
 */
export interface MessagingConnector {
  /** Platform identifier (e.g., "SLACK", "DISCORD", "WHATSAPP") */
  readonly source: string

  /**
   * Send a text message to a channel/conversation.
   * @param replyContext - Opaque context from the inbound event (channel, thread_ts, etc.)
   * @param text - Message text to send
   */
  sendMessage(replyContext: Record<string, string>, text: string): Promise<void>
}
