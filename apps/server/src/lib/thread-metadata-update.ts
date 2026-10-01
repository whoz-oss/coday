import type { ThreadService } from '@coday/service'

export type ThreadMetadataUpdate = {
  name?: string
  summary?: string
  metadataPersisted?: boolean
}

type ThreadMetadataService = Pick<ThreadService, 'getThread' | 'updateThread'> & {
  refreshThreadCache?: (projectName: string, threadId: string) => Promise<void>
}

/**
 * Mirrors ThreadUpdateEvent metadata into ThreadService after a thread has
 * messages on disk. Callers deliberately do not await this background update,
 * so the event stream remains responsive.
 */
export async function persistThreadMetadataUpdate(
  threadService: ThreadMetadataService,
  projectName: string,
  threadId: string,
  update: ThreadMetadataUpdate
): Promise<void> {
  if (update.metadataPersisted && threadService.refreshThreadCache) {
    await threadService.refreshThreadCache(projectName, threadId)
    return
  }

  const thread = await threadService.getThread(projectName, threadId)
  if (!thread) return

  if (update.name) thread.name = update.name
  if (update.summary) thread.summary = update.summary

  // Do not save an empty snapshot before the thread lifecycle has autosaved
  // its first message.
  if (thread.messagesLength > 0) {
    await threadService.updateThread(projectName, threadId, update)
  }
}
