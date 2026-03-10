import { AiClient } from '@coday/model'
import { MessageEvent } from '@coday/model'
import { ThreadService } from '@coday/service'
import { AiThread } from '@coday/model'
import { debugLog } from './log'

const MIN_USER_MESSAGES = 4

/**
 * Runs post-processing on a thread after its session ends.
 * Currently: generates name + summary via a SMALL AI call if the thread has enough messages.
 * Fire-and-forget — never throws, errors are logged only.
 */
export class ThreadPostProcessor {
  constructor(
    private readonly aiClient: AiClient,
    private readonly threadService: ThreadService
  ) {}

  /**
   * Entry point. Called after ThreadCodayInstance.cleanup().
   * Returns immediately — processing is async and non-blocking.
   */
  process(thread: AiThread, projectName: string): void {
    this.run(thread, projectName).catch((err) =>
      debugLog('POST_PROCESSOR', `Unhandled error for thread ${thread.id}:`, err)
    )
  }

  private async run(thread: AiThread, projectName: string): Promise<void> {
    const userCount = thread.getUserMessageCount()
    if (userCount < MIN_USER_MESSAGES) {
      debugLog('POST_PROCESSOR', `Skipping thread ${thread.id}: only ${userCount} user message(s)`)
      return
    }

    debugLog('POST_PROCESSOR', `Processing thread ${thread.id} (${userCount} user messages)`)

    // Build a compact text transcript (MessageEvent only, last 40 messages max)
    const { messages } = await thread.getMessages(undefined, undefined)
    const textMessages = messages
      .filter((m): m is MessageEvent => m instanceof MessageEvent)
      .slice(-40)
      .map((m) => {
        const role = m.role === 'user' ? 'User' : m.name || 'Assistant'
        const text = m.content
          .filter((c) => c.type === 'text')
          .map((c) => (c as { type: 'text'; content: string }).content)
          .join(' ')
          .slice(0, 500) // cap each message to avoid huge prompts
        return `${role}: ${text}`
      })

    if (!textMessages.length) {
      debugLog('POST_PROCESSOR', `Skipping thread ${thread.id}: no text messages`)
      return
    }

    const transcript = textMessages.join('\n')

    const prompt = `You are given a conversation transcript. Reply ONLY with a valid JSON object (no markdown, no explanation) with two fields:
- "name": a short title for the conversation (max 60 chars, no quotes)
- "summary": 2-4 sentences summarising the main topics and outcomes

Transcript:
${transcript}

JSON:`

    let name: string | undefined
    let summary: string | undefined

    try {
      const raw = await this.aiClient.complete(prompt, { maxTokens: 300 })
      // Strip potential markdown code fences
      const cleaned = raw
        .replace(/^```(?:json)?\s*/i, '')
        .replace(/\s*```$/, '')
        .trim()
      const parsed = JSON.parse(cleaned)
      name = typeof parsed.name === 'string' ? parsed.name.trim().slice(0, 60) : undefined
      summary = typeof parsed.summary === 'string' ? parsed.summary.trim() : undefined
    } catch (err) {
      debugLog('POST_PROCESSOR', `Failed to parse AI response for thread ${thread.id}:`, err)
      return
    }

    if (!name && !summary) {
      debugLog('POST_PROCESSOR', `Nothing to update for thread ${thread.id}`)
      return
    }

    // Only set name if thread doesn't already have one (auto-naming)
    const updates: { name?: string; summary?: string } = {}
    if (summary) updates.summary = summary
    if (name && !thread.name) updates.name = name

    try {
      await this.threadService.updateThread(projectName, thread.id, updates)
      debugLog(
        'POST_PROCESSOR',
        `Thread ${thread.id} updated — name: ${updates.name ?? '(kept)'}, summary: ${!!updates.summary}`
      )
    } catch (err) {
      debugLog('POST_PROCESSOR', `Failed to persist updates for thread ${thread.id}:`, err)
    }
  }
}
