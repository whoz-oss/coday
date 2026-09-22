import { describe, expect, it, jest } from '@jest/globals'
import { AiThread, ThreadSummary } from '@coday/model'
import { ThreadRepository } from '@coday/repository'
import { ThreadFileService } from './thread-file.service'
import { ThreadService } from './thread.service'

const projectName = 'Forge'
const threadId = 'thread-1'

function summary(name: string, summaryText: string): ThreadSummary {
  return {
    id: threadId,
    username: 'patrice.lamarque',
    projectId: projectName,
    name,
    summary: summaryText,
    createdDate: '2026-09-22T00:00:00.000Z',
    modifiedDate: '2026-09-22T00:00:00.000Z',
    price: 0,
    starring: [],
    users: [{ userId: 'patrice.lamarque' }],
  }
}

function createService(): ThreadService {
  return new ThreadService({} as never, '/unused', {} as ThreadFileService)
}

describe('ThreadService.refreshThreadCache', () => {
  it('refreshes an existing cache entry from an already-persisted thread without saving it again', async () => {
    const persisted = new AiThread({
      id: threadId,
      username: 'patrice.lamarque',
      projectId: projectName,
      name: 'Scheduled report',
      summary: 'Ready for human review',
      price: 0.0185205,
      messages: [
        {
          type: 'message',
          role: 'assistant',
          name: 'Coday',
          content: [{ type: 'text', content: 'Persisted response' }],
        },
      ],
    })
    const repository = {
      getById: jest.fn(async (_projectName: string, _threadId: string) => persisted),
    }
    const service = createService()
    const internals = service as unknown as {
      repositoryCache: Map<string, Pick<ThreadRepository, 'getById'>>
      threadListCache: Map<string, { data: ThreadSummary[]; timestamp: number }>
    }
    internals.repositoryCache.set(projectName, repository)
    internals.threadListCache.set(projectName, {
      data: [summary('Original title', 'Original summary')],
      timestamp: Date.now(),
    })

    await service.refreshThreadCache(projectName, threadId)

    expect(repository.getById).toHaveBeenCalledWith(projectName, threadId)
    expect(internals.threadListCache.get(projectName)?.data).toEqual([
      expect.objectContaining({
        id: threadId,
        name: 'Scheduled report',
        summary: 'Ready for human review',
        price: 0.0185205,
      }),
    ])
  })

  it('does not read storage when the project list was never cached', async () => {
    const repository = {
      getById: jest.fn(async (_projectName: string, _threadId: string) => null),
    }
    const service = createService()
    const internals = service as unknown as {
      repositoryCache: Map<string, Pick<ThreadRepository, 'getById'>>
    }
    internals.repositoryCache.set(projectName, repository)

    await service.refreshThreadCache(projectName, threadId)

    expect(repository.getById).not.toHaveBeenCalled()
  })
})
