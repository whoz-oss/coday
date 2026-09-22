import { describe, expect, it, jest } from '@jest/globals'
import type { AiThread } from '@coday/model'
import type { ThreadService } from '@coday/service'
import { persistThreadMetadataUpdate } from './thread-metadata-update'

const projectName = 'Forge'
const threadId = 'thread-1'

function threadWithMessages(): AiThread {
  return {
    id: threadId,
    name: 'Original title',
    summary: 'Original summary',
    messagesLength: 1,
  } as unknown as AiThread
}

function threadServiceFor(thread: AiThread | null): jest.Mocked<Pick<ThreadService, 'getThread' | 'updateThread'>> {
  return {
    getThread: jest.fn(async () => thread),
    updateThread: jest.fn(async () => thread as AiThread),
  }
}

describe('persistThreadMetadataUpdate', () => {
  it('persists title and summary after a thread already has messages', async () => {
    const thread = threadWithMessages()
    const threadService = threadServiceFor(thread)

    await persistThreadMetadataUpdate(threadService, projectName, threadId, {
      name: 'Scheduled report',
      summary: 'Ready for human review',
    })

    expect(thread.name).toBe('Scheduled report')
    expect(thread.summary).toBe('Ready for human review')
    expect(threadService.updateThread).toHaveBeenCalledWith(projectName, threadId, {
      name: 'Scheduled report',
      summary: 'Ready for human review',
    })
  })

  it('does not persist metadata from an empty thread snapshot', async () => {
    const thread = {
      id: threadId,
      name: '',
      summary: '',
      messagesLength: 0,
    } as unknown as AiThread
    const threadService = threadServiceFor(thread)

    await persistThreadMetadataUpdate(threadService, projectName, threadId, {
      name: 'Scheduled report',
    })

    expect(thread.name).toBe('Scheduled report')
    expect(threadService.updateThread).not.toHaveBeenCalled()
  })

  it('does not write when the thread cannot be found', async () => {
    const threadService = threadServiceFor(null)

    await persistThreadMetadataUpdate(threadService, projectName, threadId, {
      summary: 'Ready for human review',
    })

    expect(threadService.updateThread).not.toHaveBeenCalled()
  })

  it('refreshes the cache without a second YAML write when metadata is already persisted', async () => {
    const thread = threadWithMessages()
    const threadService = {
      ...threadServiceFor(thread),
      refreshThreadCache: jest.fn(async (_projectName: string, _threadId: string) => undefined),
    }

    await persistThreadMetadataUpdate(threadService, projectName, threadId, {
      name: 'Scheduled report',
      metadataPersisted: true,
    })

    expect(threadService.refreshThreadCache).toHaveBeenCalledWith(projectName, threadId)
    expect(threadService.getThread).not.toHaveBeenCalled()
    expect(threadService.updateThread).not.toHaveBeenCalled()
  })
})
