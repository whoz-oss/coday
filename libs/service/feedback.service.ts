import { CommandContext, Interactor } from '../model'
import { AgentService } from '../agent'
import { AiThread } from '../ai-thread/ai-thread'

export class FeedbackService {
  constructor(
    private interactor: Interactor,
    private agentService: AgentService
  ) {}

  async processFeedback(params: {
    messageId: string,
    feedback: 'positive' | 'negative',
    aiThread: AiThread,
    context: CommandContext
  }): Promise<void> {
    const { messageId, feedback, aiThread, context } = params

    try {
      // 1. Find the message and extract agent name from it
      const messagesResult = await aiThread.getMessages(undefined, undefined)
      const message = messagesResult.messages.find((m: any) => m.timestamp === messageId)
      
      if (!message) {
        this.interactor.error(`Message ${messageId} not found in thread`)
        return
      }

      // Check if this is a MessageEvent (only MessageEvents can receive feedback)
      if (message.type !== 'message') {
        this.interactor.error('Feedback can only be provided on message events')
        return
      }

      // Cast to MessageEvent to access role and speaker properties
      const messageEvent = message as any // MessageEvent from coday-events
      
      if (messageEvent.role !== 'assistant') {
        this.interactor.error('Feedback can only be provided on assistant messages')
        return
      }

      // Extract agent name from message speaker
      const agentName = messageEvent.name || 'assistant'
      
      // 2. Get the agent
      const agent = await this.agentService.findByName(agentName, context)
      if (!agent) {
        this.interactor.error(`Agent ${agentName} not found`)
        return
      }

      // 3. Check if agent has memory tools access
      // For now, we'll try to use memory tools and let the agent fail naturally if not available

      // 4. Ask user for feedback details
      const promptMessage = feedback === 'negative' 
        ? "What could be improved in this response?"
        : "What was particularly useful in this response?"
      
      const details = await this.interactor.promptText(promptMessage)
      if (!details) {
        this.interactor.displayText('Feedback cancelled')
        return
      }

      // 5. Fork the thread for isolated processing
      const forkedThread = aiThread.fork(agentName)
      
      // 6. Build the appropriate curation prompt
      const curationPrompt = await this.buildCurationPrompt(
        messageId, 
        details, 
        feedback, 
        forkedThread
      )

      // 7. Execute agent in forked thread (no merge back)
      this.interactor.displayText(`🔄 Processing ${feedback} feedback for agent ${agentName}...`)
      await agent.run(curationPrompt, forkedThread)
      
      this.interactor.displayText('✅ Feedback processed successfully')
    } catch (error: any) {
      console.error('Error processing feedback:', error)
      this.interactor.error(`Error processing feedback: ${error.message}`)
    }
  }

  private async buildCurationPrompt(
    messageId: string, 
    userFeedback: string, 
    feedbackType: 'positive' | 'negative',
    thread: AiThread
  ): Promise<string> {
    // Obtenir tout le contexte jusqu'au message concerné
    const messagesResult = await thread.getMessages(undefined, undefined)
    const allMessages = messagesResult.messages
    const messageIndex = allMessages.findIndex((m: any) => m.timestamp === messageId)
    
    if (messageIndex === -1) {
      throw new Error(`Message ${messageId} not found in thread`)
    }

    // Contexte = tous les messages jusqu'à celui concerné inclus
    const contextMessages = allMessages.slice(0, messageIndex + 1)
    
    if (feedbackType === 'positive') {
      return `You have received positive feedback on one of your responses. The user wants to reinforce this type of approach.

Complete conversation context up to your response:
<context>
${this.formatMessages(contextMessages)}
</context>

Positive user feedback:
"${userFeedback}"

Analyze this feedback and identify the elements that were appreciated. If you identify patterns or approaches that should be reinforced for similar future situations, use the memorize tool to capture them.

Important:
- Don't over-generalize from a single example
- Stay nuanced and avoid becoming repetitive
- Focus on useful principles rather than specific details`
    } else {
      return `You have received constructive feedback on one of your responses. The user suggests an improvement.

Complete conversation context up to your response:
<context>
${this.formatMessages(contextMessages)}
</context>

User improvement suggestion:
"${userFeedback}"

Analyze this feedback and identify what could be improved in your approach. If you identify patterns or behaviors that should be adjusted, use the memorize tool to capture these learnings.

Important:
- Stay balanced in your adjustments
- Don't over-correct to the point of losing your existing strengths
- Focus on actionable and contextual improvements
- Avoid excessive generalizations`
    }
  }

  private formatMessages(messages: any[]): string {
    return messages.map(m => {
      const role = m.role || 'unknown'
      let content = ''
      
      if (typeof m.content === 'string') {
        content = m.content
      } else if (Array.isArray(m.content)) {
        // Handle rich content format
        content = m.content
          .filter((c: any) => c.type === 'text')
          .map((c: any) => c.content)
          .join('\n')
      } else {
        content = JSON.stringify(m.content)
      }
      
      return `${role}: ${content}`
    }).join('\n\n')
  }
}