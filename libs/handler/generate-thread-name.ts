import { AiThread } from '../ai-thread/ai-thread'
import { Agent } from '../model'
import { MessageEvent } from '@coday/coday-events'

export async function generateThreadName(thread: AiThread, agent: Agent): Promise<string> {
  // Extract context from first few user messages
  const messages = (await thread.getMessages(undefined, undefined)).messages
    .filter((msg) => msg instanceof MessageEvent && msg.role === 'user')
    .slice(0, 3)
    .map((msg) => (msg as MessageEvent).content)
    .join('\n\n')

  const prompt = `Here are the messages a user sent in a conversation with an AI:\n\n${messages}\n\nGenerate a title for this conversation between the conversation-name tags, and without introduction nor line jumps.\n<conversation-name>`

  try {
    // Use the agent's AI client to generate the name
    const title = await agent.getAiClient().complete(prompt, {
      maxTokens: 30,
      temperature: 0.7,
      stopSequences: ['</conversation-name>'],
    })

    // Clean up the title
    return title
      .trim()
      .replace(/^["']|["']$/g, '')
      .replace(/\.$/, '')
  } catch (error) {
    // Fallback to date-based name
    return `Thread ${new Date().toISOString().split('T')[0]}`
  }
}
