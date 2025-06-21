import { ThreadMessage } from '@coday/ai-thread/ai-thread.types'

export function partition(
  messages: ThreadMessage[],
  charBudget: number | undefined
): {
  messages: ThreadMessage[]
  overflow: ThreadMessage[]
} {
  if (!charBudget || !messages.length) return { messages, overflow: [] }
  let overflowIndex = 0
  let count = 0
  while (count < charBudget && overflowIndex < messages.length) {
    count += messages[overflowIndex].length
    overflowIndex += count < charBudget ? 1 : 0
  }

  const underflow = messages.slice(0, overflowIndex)
  const overflow = messages.slice(overflowIndex)
  return {
    messages: underflow,
    overflow,
  }
}
