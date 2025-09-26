import { CommandContext, Interactor } from '../model'
import { AgentService } from '../agent'
import { AiThread } from '../ai-thread/ai-thread'
import { MessageEvent } from '@coday/coday-events'

const MEMORY_CURATION = `As we are working with your memories, it might be the right time to do some light curation:

- are there outdated or irrelevant memories you can remove ?
- are there duplicated memories that would benefit a merge ?

Use the memory tools, and only if obviously necessary:

- when less than 5 memories present, do not curate.
- between 10 and 20 memories, look for simplification.
- over 40 memories, time for significant cleanup !
`

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
      const messageEvent = message as MessageEvent // MessageEvent from coday-events
      
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
      const integrations = agent.definition.integrations
      console.log(`agent ${agentName} integrations`, integrations)
      if (!integrations || !Object.hasOwn(integrations, 'MEMORY')) {
        this.interactor.warn(`Agent ${agentName} does not have memory integration, feedback not effective.`)
        return
      }

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
      const forkedThread = aiThread.fork(null)
      forkedThread.truncateAtMessage(messageId, 1)
      
      // 6. Build the appropriate curation prompt
      const curationPrompt = await this.buildCurationPrompt(
        details, 
        feedback, 
      )

      // 7. Execute agent in forked thread (no merge back)
      this.interactor.displayText(`🔄 Processing ${feedback} feedback for agent ${agentName}...`)
      await agent.run(curationPrompt, forkedThread)

      await agent.run(MEMORY_CURATION, forkedThread).then(() => 
        this.interactor.displayText('✅ Feedback processed successfully')
      )
    } catch (error: any) {
      console.error('Error processing feedback:', error)
      this.interactor.error(`Error processing feedback: ${error.message}`)
    }
  }

  private async buildCurationPrompt(
    userFeedback: string, 
    feedbackType: 'positive' | 'negative',
  ): Promise<string> {
    
    
    if (feedbackType === 'positive') {
      return `You have received positive feedback on the very last of your responses. The user wants to reinforce this type of approach.

Positive user feedback:
"${userFeedback}"

Analyze this feedback and identify the elements that were appreciated. If you identify patterns or approaches that should be reinforced for similar future situations, use the memorize tool to capture them.

Important:
- Don't over-generalize from a single example
- Stay nuanced and avoid becoming repetitive
- Focus on useful principles rather than specific details`
    } else {
      return `You have received constructive feedback on one of your responses. The user is not satisfied with your last or previous answers.

User feedback:
"${userFeedback}"

Analyze this feedback and identify what could be improved in your approach. If you identify patterns or behaviors that should be adjusted, use the memorize tool to capture these learnings.

Important:
- Stay balanced in your adjustments
- Don't over-correct to the point of losing your existing strengths
- Focus on actionable and contextual improvements
- Avoid excessive generalizations`
    }
  }

}