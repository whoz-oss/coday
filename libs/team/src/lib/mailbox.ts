export interface MailboxMessage {
  id: string
  from: string
  to: string
  content: string
  timestamp: string
}

export class Mailbox {
  private queues: Map<string, MailboxMessage[]> = new Map()
  private waiters: Map<string, Array<(message: string) => void>> = new Map()
  private nextId: number = 1

  /**
   * Send a message from one agent to another.
   * If the recipient is waiting (via waitForMessage), wake them up immediately.
   */
  send(from: string, to: string, content: string): MailboxMessage {
    const message: MailboxMessage = {
      id: `msg-${this.nextId++}`,
      from,
      to,
      content,
      timestamp: new Date().toISOString(),
    }

    // Check if recipient is waiting
    const recipientWaiters = this.waiters.get(to)
    if (recipientWaiters && recipientWaiters.length > 0) {
      const resolve = recipientWaiters.shift()!
      if (recipientWaiters.length === 0) this.waiters.delete(to)
      resolve(`Message from ${from}: ${content}`)
      return message
    }

    // Otherwise queue it
    if (!this.queues.has(to)) {
      this.queues.set(to, [])
    }
    this.queues.get(to)!.push(message)
    return message
  }

  /**
   * Broadcast a message from one agent to all other registered agents.
   * Returns the list of messages sent.
   */
  broadcast(from: string, content: string, allAgentNames: string[]): MailboxMessage[] {
    return allAgentNames.filter((name) => name !== from).map((name) => this.send(from, name, content))
  }

  /**
   * Read and consume all pending messages for an agent.
   */
  receive(agentName: string): MailboxMessage[] {
    const messages = this.queues.get(agentName) ?? []
    this.queues.delete(agentName)
    return messages
  }

  /**
   * Peek at pending messages without consuming them.
   */
  peek(agentName: string): MailboxMessage[] {
    return [...(this.queues.get(agentName) ?? [])]
  }

  /**
   * Wait for a message to arrive for the given agent.
   * If there are already queued messages, returns the first one immediately (consumed).
   * Otherwise, blocks until a message arrives via send().
   */
  waitForMessage(agentName: string): Promise<string> {
    // Check for queued messages first
    const queue = this.queues.get(agentName)
    if (queue && queue.length > 0) {
      const message = queue.shift()!
      if (queue.length === 0) this.queues.delete(agentName)
      return Promise.resolve(`Message from ${message.from}: ${message.content}`)
    }

    // No messages — register a waiter
    return new Promise<string>((resolve) => {
      if (!this.waiters.has(agentName)) {
        this.waiters.set(agentName, [])
      }
      this.waiters.get(agentName)!.push(resolve)
    })
  }

  /**
   * Cancel all waiting promises for an agent (used during shutdown).
   * Resolves them with a shutdown message.
   */
  cancelWaiters(agentName: string): void {
    const agentWaiters = this.waiters.get(agentName)
    if (agentWaiters) {
      agentWaiters.forEach((resolve) => resolve('__SHUTDOWN__'))
      this.waiters.delete(agentName)
    }
  }

  /**
   * Cancel ALL waiters across all agents (used during team cleanup)
   */
  cancelAllWaiters(): void {
    for (const [agentName] of this.waiters) {
      this.cancelWaiters(agentName)
    }
  }

  /**
   * Get the count of pending messages for an agent
   */
  getMessageCount(agentName: string): number {
    return this.queues.get(agentName)?.length ?? 0
  }
}
