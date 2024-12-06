import { Interactor } from '../../model'
import { AiThreadService } from '../../ai-thread/ai-thread.service'
import { ThreadSummary } from '../../ai-thread/ai-thread.types'
import { lastValueFrom } from 'rxjs'
import { AiThread } from '../../ai-thread/ai-thread'

export async function selectAiThread(interactor: Interactor, threadService: AiThreadService): Promise<void> {
  // Interactive selection
  const threads: ThreadSummary[] = await lastValueFrom(threadService.list())
  if (threads.length === 0) {
    interactor.displayText('No threads available, creating one.')
    threadService.create()
    return
  }

  const currentThread: AiThread | null = threadService.getCurrentThread()
  const threadsByText = new Map<string, string>()

  threads.forEach((thread: ThreadSummary) => {
    const text = `${thread.id}: ${currentThread?.id === thread.id ? '[CURRENT] ' : ''}${thread.name}`
    threadsByText.set(text, thread.id)
  })

  const options = Array.from(threadsByText.keys())
  const newThreadLabel = 'New thread'
  options.unshift(newThreadLabel)
  const selected = await interactor.chooseOption(options, 'Select a thread')
  if (selected === newThreadLabel) {
    threadService.create()
    return
  }
  const selectedId = threadsByText.get(selected)
  if (!selectedId) {
    interactor.error('Failed to get selected thread ID')
    threadService.create()
    return
  }

  await threadService.select(selectedId)
}
