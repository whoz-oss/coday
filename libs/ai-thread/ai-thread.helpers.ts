import { ThreadMessage } from '@coday/ai-thread/ai-thread.types'

export function partition(
  messages: ThreadMessage[],
  charBudget: number | undefined
): {
  messages: ThreadMessage[]
  overflow: ThreadMessage[]
} {
  if (!charBudget) return { messages, overflow: [] }
  let overflowIndex = 0
  let count = 0
  while (count < charBudget && overflowIndex < messages.length) {
    count += messages[overflowIndex].length
    overflowIndex++
  }

  console.log('partition: overflowIndex', overflowIndex)
  if (!overflowIndex) {
    // then all is overflow
    console.log('partition: all is overflow')
    return { messages: [], overflow: messages }
  }
  if (overflowIndex === messages.length) {
    console.log('partition: all is message')
    return {
      messages,
      overflow: [],
    }
  }

  const underflow = messages.slice(0, overflowIndex + 1)
  const overflow = messages.slice(overflowIndex)
  console.log('partition overflow', overflow.length, 'underflow', underflow.length)
  return {
    messages: underflow,
    overflow,
  }
}
