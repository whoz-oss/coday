import { AiThread } from '../ai-thread/ai-thread'
import { Agent } from '../model'
import { MessageEvent } from '@coday/coday-events'

export async function generateThreadName(thread: AiThread, agent: Agent): Promise<string> {
  // Extract context from first few user messages
  const messages = (await thread.getMessages(undefined, undefined)).messages
    .filter((msg) => msg instanceof MessageEvent && msg.role === 'user')
    .slice(0, 3)
    .map((msg) => (msg as MessageEvent).getTextContent())
    .join('\n\n')

  const prompt = `Here are the messages a user sent in a conversation with an AI:\n\n<messages>${messages}</messages>\n\nGenerate a short title for this conversation between <conversation-name></conversation-name> tags, and without introduction nor line jumps.\n`

  try {
    // Use the agent's AI client to generate the name (without stopSequences)
    const response = await agent.getAiClient().complete(prompt, {
      maxTokens: 300,
      temperature: 1.0,
    })

    // Extract content between <conversation-name> tags
    const match = response.match(/<conversation-name>([\s\S]*?)<\/conversation-name>/)
    if (match && match[1]) {
      const title = match[1].trim()

      // Clean up the title
      return title
        .replace(/^["']|["']$/g, '') // Remove surrounding quotes
        .replace(/\.$/, '') // Remove trailing period
        .trim()
    }

    // Fallback: if no tags found, return trimmed response or date-based name
    const fallbackTitle = response.trim()
    return fallbackTitle || `Thread ${new Date().toISOString().split('T')[0]}`
  } catch (error) {
    console.error('Error generating thread name:', error)
    // Fallback to date-based name
    return `Thread ${new Date().toISOString().split('T')[0]}`
  }
}
