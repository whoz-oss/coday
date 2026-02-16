import { Mailbox } from './mailbox'

describe('Mailbox', () => {
  let mailbox: Mailbox

  beforeEach(() => {
    mailbox = new Mailbox()
  })

  describe('send', () => {
    it('should queue a message for the recipient', () => {
      const message = mailbox.send('agent-1', 'agent-2', 'Hello')

      expect(message.id).toBe('msg-1')
      expect(message.from).toBe('agent-1')
      expect(message.to).toBe('agent-2')
      expect(message.content).toBe('Hello')
      expect(message.timestamp).toBeDefined()
    })

    it('should auto-increment message IDs', () => {
      const msg1 = mailbox.send('agent-1', 'agent-2', 'First')
      const msg2 = mailbox.send('agent-1', 'agent-2', 'Second')
      const msg3 = mailbox.send('agent-1', 'agent-2', 'Third')

      expect(msg1.id).toBe('msg-1')
      expect(msg2.id).toBe('msg-2')
      expect(msg3.id).toBe('msg-3')
    })

    it('should wake up a waiting recipient immediately', async () => {
      const waitPromise = mailbox.waitForMessage('agent-2')

      // Send message - should resolve the wait immediately
      mailbox.send('agent-1', 'agent-2', 'Wake up!')

      const result = await waitPromise
      expect(result).toBe('Message from agent-1: Wake up!')
    })
  })

  describe('receive', () => {
    it('should return all queued messages and consume them', () => {
      mailbox.send('agent-1', 'agent-2', 'First')
      mailbox.send('agent-1', 'agent-2', 'Second')

      const messages = mailbox.receive('agent-2')
      expect(messages).toHaveLength(2)
      expect(messages[0]!.content).toBe('First')
      expect(messages[1]!.content).toBe('Second')

      // Second receive should return empty
      const messages2 = mailbox.receive('agent-2')
      expect(messages2).toEqual([])
    })

    it('should return empty array for agent with no messages', () => {
      const messages = mailbox.receive('agent-1')
      expect(messages).toEqual([])
    })
  })

  describe('peek', () => {
    it('should return messages without consuming them', () => {
      mailbox.send('agent-1', 'agent-2', 'First')
      mailbox.send('agent-1', 'agent-2', 'Second')

      const peeked1 = mailbox.peek('agent-2')
      expect(peeked1).toHaveLength(2)

      const peeked2 = mailbox.peek('agent-2')
      expect(peeked2).toHaveLength(2)
      expect(peeked2).toEqual(peeked1)

      // Should still be available for receive
      const received = mailbox.receive('agent-2')
      expect(received).toHaveLength(2)
    })

    it('should return empty array for agent with no messages', () => {
      const peeked = mailbox.peek('agent-1')
      expect(peeked).toEqual([])
    })
  })

  describe('waitForMessage', () => {
    it('should return immediately if messages are queued', async () => {
      mailbox.send('agent-1', 'agent-2', 'Already here')

      const result = await mailbox.waitForMessage('agent-2')
      expect(result).toBe('Message from agent-1: Already here')

      // Message should be consumed
      expect(mailbox.getMessageCount('agent-2')).toBe(0)
    })

    it('should block until a message arrives', async () => {
      const waitPromise = mailbox.waitForMessage('agent-2')

      // Verify it's not resolved immediately
      let resolved = false
      waitPromise.then(() => {
        resolved = true
      })

      await new Promise((resolve) => setTimeout(resolve, 10))
      expect(resolved).toBe(false)

      // Send message
      mailbox.send('agent-1', 'agent-2', 'Finally!')

      const result = await waitPromise
      expect(result).toBe('Message from agent-1: Finally!')
    })

    it('should handle multiple messages in queue correctly', async () => {
      mailbox.send('agent-1', 'agent-2', 'First')
      mailbox.send('agent-1', 'agent-2', 'Second')

      const result1 = await mailbox.waitForMessage('agent-2')
      expect(result1).toBe('Message from agent-1: First')

      const result2 = await mailbox.waitForMessage('agent-2')
      expect(result2).toBe('Message from agent-1: Second')
    })
  })

  describe('broadcast', () => {
    it('should send to all agents except sender', () => {
      const agents = ['agent-1', 'agent-2', 'agent-3', 'agent-4']
      const messages = mailbox.broadcast('agent-1', 'Hello everyone', agents)

      expect(messages).toHaveLength(3)
      expect(messages.map((m) => m.to).sort()).toEqual(['agent-2', 'agent-3', 'agent-4'])
      expect(messages.every((m) => m.from === 'agent-1')).toBe(true)
      expect(messages.every((m) => m.content === 'Hello everyone')).toBe(true)
    })

    it('should not send message to self', () => {
      const agents = ['agent-1']
      const messages = mailbox.broadcast('agent-1', 'Talking to myself?', agents)

      expect(messages).toHaveLength(0)
    })

    it('should wake up waiting recipients', async () => {
      const wait2 = mailbox.waitForMessage('agent-2')
      const wait3 = mailbox.waitForMessage('agent-3')

      const agents = ['agent-1', 'agent-2', 'agent-3']
      mailbox.broadcast('agent-1', 'Wake up!', agents)

      const [result2, result3] = await Promise.all([wait2, wait3])
      expect(result2).toBe('Message from agent-1: Wake up!')
      expect(result3).toBe('Message from agent-1: Wake up!')
    })
  })

  describe('cancelWaiters', () => {
    it('should resolve waiting promises with __SHUTDOWN__', async () => {
      const waitPromise = mailbox.waitForMessage('agent-2')

      mailbox.cancelWaiters('agent-2')

      const result = await waitPromise
      expect(result).toBe('__SHUTDOWN__')
    })

    it('should handle multiple waiters', async () => {
      const wait1 = mailbox.waitForMessage('agent-2')
      const wait2 = mailbox.waitForMessage('agent-2')
      const wait3 = mailbox.waitForMessage('agent-2')

      mailbox.cancelWaiters('agent-2')

      const results = await Promise.all([wait1, wait2, wait3])
      expect(results).toEqual(['__SHUTDOWN__', '__SHUTDOWN__', '__SHUTDOWN__'])
    })

    it('should not affect other agents', async () => {
      const wait1 = mailbox.waitForMessage('agent-1')
      const wait2 = mailbox.waitForMessage('agent-2')

      mailbox.cancelWaiters('agent-1')

      const result1 = await wait1
      expect(result1).toBe('__SHUTDOWN__')

      // agent-2 should still be waiting
      let resolved = false
      wait2.then(() => {
        resolved = true
      })
      await new Promise((resolve) => setTimeout(resolve, 10))
      expect(resolved).toBe(false)

      // Send message to agent-2
      mailbox.send('agent-1', 'agent-2', 'Still working')
      const result2 = await wait2
      expect(result2).toBe('Message from agent-1: Still working')
    })
  })

  describe('cancelAllWaiters', () => {
    it('should cancel all waiting promises across all agents', async () => {
      const wait1 = mailbox.waitForMessage('agent-1')
      const wait2 = mailbox.waitForMessage('agent-2')
      const wait3 = mailbox.waitForMessage('agent-3')

      mailbox.cancelAllWaiters()

      const results = await Promise.all([wait1, wait2, wait3])
      expect(results).toEqual(['__SHUTDOWN__', '__SHUTDOWN__', '__SHUTDOWN__'])
    })
  })

  describe('getMessageCount', () => {
    it('should return correct count of pending messages', () => {
      expect(mailbox.getMessageCount('agent-2')).toBe(0)

      mailbox.send('agent-1', 'agent-2', 'First')
      expect(mailbox.getMessageCount('agent-2')).toBe(1)

      mailbox.send('agent-1', 'agent-2', 'Second')
      expect(mailbox.getMessageCount('agent-2')).toBe(2)

      mailbox.receive('agent-2')
      expect(mailbox.getMessageCount('agent-2')).toBe(0)
    })

    it('should return 0 for agent with no messages', () => {
      expect(mailbox.getMessageCount('non-existent')).toBe(0)
    })
  })

  describe('multiple waiters for same agent', () => {
    it('should resolve waiters in FIFO order', async () => {
      const results: string[] = []

      const wait1 = mailbox.waitForMessage('agent-2').then((r) => {
        results.push(`wait1: ${r}`)
        return r
      })
      const wait2 = mailbox.waitForMessage('agent-2').then((r) => {
        results.push(`wait2: ${r}`)
        return r
      })
      const wait3 = mailbox.waitForMessage('agent-2').then((r) => {
        results.push(`wait3: ${r}`)
        return r
      })

      // Send three messages
      mailbox.send('agent-1', 'agent-2', 'First')
      mailbox.send('agent-1', 'agent-2', 'Second')
      mailbox.send('agent-1', 'agent-2', 'Third')

      await Promise.all([wait1, wait2, wait3])

      expect(results).toEqual([
        'wait1: Message from agent-1: First',
        'wait2: Message from agent-1: Second',
        'wait3: Message from agent-1: Third',
      ])
    })
  })
})
