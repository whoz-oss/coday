import { MessageEvent } from '@coday/coday-events'
import { partition } from '@coday/ai-thread/ai-thread.helpers'

const message1 = new MessageEvent({ role: 'user', name: 'joe', content: 'hello' })
const message2 = new MessageEvent({ role: 'assistant', name: 'HAL', content: 'Hi, how are you ?' })
const message3 = new MessageEvent({ role: 'user', name: 'joe', content: 'HAL, you need to sleep.' })
const message4 = new MessageEvent({ role: 'assistant', name: 'HAL', content: 'How dare you, meatbag !!' })
const allMessages = [message1, message2, message3, message4]

describe('partition', () => {
  it('should handle empty message list', () => {
    const result = partition([], undefined)
    expect(result.messages.length).toBe(0)
    expect(result.overflow.length).toBe(0)
  })
  it('should handle single message list', () => {
    const result = partition([message1], undefined)
    expect(result.messages.length).toBe(1)
    expect(result.overflow.length).toBe(0)
  })
  it('should handle single message list and overflow', () => {
    const result = partition([message1], 1)
    expect(result.messages.length).toBe(0)
    expect(result.overflow.length).toBe(1)
  })
  it('should not overflow without max', () => {
    const result = partition(allMessages, undefined)
    expect(result.messages.length).toBe(4)
    expect(result.overflow.length).toBe(0)
  })
  it('should fully overflow with max at 1', () => {
    const result = partition(allMessages, 1)
    expect(result.messages.length).toBe(0)
    expect(result.overflow.length).toBe(4)
  })
  it('should partially overflow with max after msg1', () => {
    const length = message1.length + 1
    const result = partition(allMessages, length)
    expect(result.messages.length).toBe(1)
    expect(result.overflow.length).toBe(3)
  })
  it('should partially overflow with max after msg2', () => {
    const length = message1.length + message2.length + 1
    const result = partition(allMessages, length)
    expect(result.messages.length).toBe(2)
    expect(result.overflow.length).toBe(2)
  })
  it('should partially overflow with max after msg3', () => {
    const length = message1.length + message2.length + message3.length + 1
    const result = partition(allMessages, length)
    expect(result.messages.length).toBe(3)
    expect(result.overflow.length).toBe(1)
  })
  it('should not overflow with max after msg4', () => {
    const length = message1.length + message2.length + message3.length + message4.length + 1
    const result = partition(allMessages, length)
    expect(result.messages.length).toBe(4)
    expect(result.overflow.length).toBe(0)
  })
})
