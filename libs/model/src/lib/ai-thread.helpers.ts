import { ThreadMessage } from './ai-thread.types'

export function partition(
  messages: ThreadMessage[],
  charBudget: number | undefined,
  ratio: number = 0.7
): {
  messages: ThreadMessage[]
  overflow: ThreadMessage[]
} {
  if (!charBudget || !messages.length) return { messages, overflow: [] }
  let overflowIndex = 0
  let count = 0
  const threshold = charBudget * ratio
  for (const message of messages) {
    count += message.length
    overflowIndex += count < threshold ? 1 : 0
  }
  if (count < charBudget) {
    return { messages, overflow: [] }
  }

  const underflow = messages.slice(0, overflowIndex)
  const overflow = messages.slice(overflowIndex)
  return {
    messages: underflow,
    overflow,
  }
}
