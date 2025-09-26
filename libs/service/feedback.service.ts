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
    agentName: string,
    feedback: 'positive' | 'negative',
    aiThread: AiThread,
    context: CommandContext
  }): Promise<void> {
    const { messageId, agentName, feedback, aiThread, context } = params

    try {
      // 1. Obtenir l'agent
      const agent = await this.agentService.findByName(agentName, context)
      if (!agent) {
        this.interactor.error(`Agent ${agentName} not found`)
        return
      }

      // 2. Vérifier que l'agent a accès aux memory tools
      // Pour simplifier, nous essaierons d'utiliser les memory tools
      // L'agent échouera naturellement s'il n'y a pas accès

      // 3. Demander des détails à l'utilisateur
      const promptMessage = feedback === 'negative' 
        ? "Qu'est-ce qui pourrait être amélioré dans cette réponse ?"
        : "Qu'est-ce qui était particulièrement utile dans cette réponse ?"
      
      const details = await this.interactor.promptText(promptMessage)
      if (!details) {
        this.interactor.displayText('Feedback cancelled')
        return
      }

      // 4. Fork du thread et préparer le contexte
      const forkedThread = aiThread.fork(agentName)
      
      // 5. Construire le prompt de curation adapté
      const curationPrompt = await this.buildCurationPrompt(
        messageId, 
        details, 
        feedback, 
        forkedThread
      )

      // 6. Exécuter l'agent dans le thread forké (pas de merge)
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
      return `Tu as reçu un retour positif sur l'une de tes réponses. L'utilisateur souhaite renforcer ce type d'approche.

Contexte complet de la conversation jusqu'à ta réponse:
<context>
${this.formatMessages(contextMessages)}
</context>

Retour positif de l'utilisateur:
"${userFeedback}"

Analyse ce retour et identifie les éléments qui ont été appréciés. Si tu identifies des patterns ou approches qui mériteraient d'être renforcés pour des situations similaires futures, utilise l'outil memorize pour les capturer.

Important:
- Ne sur-généralise pas à partir d'un seul exemple
- Reste nuancé et évite de devenir répétitif
- Focus sur les principes utiles plutôt que sur des détails spécifiques`
    } else {
      return `Tu as reçu un retour constructif sur l'une de tes réponses. L'utilisateur suggère une amélioration.

Contexte complet de la conversation jusqu'à ta réponse:
<context>
${this.formatMessages(contextMessages)}
</context>

Suggestion d'amélioration de l'utilisateur:
"${userFeedback}"

Analyse ce retour et identifie ce qui pourrait être amélioré dans ton approche. Si tu identifies des patterns ou comportements qui devraient être ajustés, utilise l'outil memorize pour capturer ces apprentissages.

Important:
- Reste équilibré dans tes ajustements
- Ne sur-corrige pas au point de perdre tes forces existantes
- Focus sur des améliorations actionnables et contextuelles
- Évite les généralisations excessives`
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