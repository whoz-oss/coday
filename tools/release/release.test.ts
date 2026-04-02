import { notifyCoday, type NotifyCodayEnv } from './release'

const baseEnv: NotifyCodayEnv = {
  baseUrl: 'https://coday.example.com',
  promptId: 'test-prompt-id',
  runUrl: 'https://github.com/whoz-oss/coday/actions/runs/123',
  branch: 'master',
  commitSha: 'abc123',
}

describe('notifyCoday', () => {
  let fetchMock: jest.MockedFunction<typeof fetch>

  beforeEach(() => {
    fetchMock = jest.fn().mockResolvedValue({ ok: true } as Response)
    global.fetch = fetchMock
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    jest.spyOn(console, 'log').mockImplementation(() => {})
    jest.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    jest.restoreAllMocks()
  })

  describe('when env vars are missing', () => {
    it('skips and warns when baseUrl is missing', async () => {
      await notifyCoday('releaseVersion', new Error('boom'), {}, { ...baseEnv, baseUrl: undefined })

      expect(fetchMock).not.toHaveBeenCalled()
      expect(console.warn).toHaveBeenCalledWith(expect.stringContaining('Skipped'))
    })

    it('skips and warns when promptId is missing', async () => {
      await notifyCoday('releaseVersion', new Error('boom'), {}, { ...baseEnv, promptId: undefined })

      expect(fetchMock).not.toHaveBeenCalled()
      expect(console.warn).toHaveBeenCalledWith(expect.stringContaining('Skipped'))
    })
  })

  describe('when env vars are present', () => {
    it('builds and calls the correct webhook URL from baseUrl and promptId', async () => {
      await notifyCoday('releaseVersion', new Error('boom'), {}, baseEnv)

      expect(fetchMock).toHaveBeenCalledWith(
        'https://coday.example.com/api/webhooks/test-prompt-id/execute',
        expect.objectContaining({ method: 'POST' })
      )
    })

    it('sends correct headers', async () => {
      await notifyCoday('releaseVersion', new Error('boom'), {}, baseEnv)

      const [, options] = fetchMock.mock.calls[0]!
      expect((options as RequestInit).headers).toEqual({
        'Content-Type': 'application/json',
        'x-forwarded-email': 'ci-bot@coday',
      })
    })

    it('includes phase, error and env context in the payload', async () => {
      await notifyCoday('releaseChangelog', new Error('changelog failed'), {}, baseEnv)

      const [, options] = fetchMock.mock.calls[0]!
      const body = JSON.parse((options as RequestInit).body as string)

      expect(body.phase).toBe('releaseChangelog')
      expect(body.error).toBe('Error: changelog failed')
      expect(body.runUrl).toBe(baseEnv.runUrl)
      expect(body.branch).toBe('master')
      expect(body.commitSha).toBe('abc123')
      expect(body.awaitFinalAnswer).toBe(false)
    })

    it('merges extra context into the payload', async () => {
      await notifyCoday('releasePublish', 'publish failed', { failedProjects: ['server', 'client'] }, baseEnv)

      const [, options] = fetchMock.mock.calls[0]!
      const body = JSON.parse((options as RequestInit).body as string)

      expect(body.failedProjects).toEqual(['server', 'client'])
    })

    it('sets the title from the phase name', async () => {
      await notifyCoday('releaseVersion', 'error', {}, baseEnv)

      const [, options] = fetchMock.mock.calls[0]!
      const body = JSON.parse((options as RequestInit).body as string)

      expect(body.title).toBe('Release failure: releaseVersion')
    })

    it('does not throw when fetch fails', async () => {
      fetchMock.mockRejectedValue(new Error('network error'))

      await expect(notifyCoday('releaseVersion', 'error', {}, baseEnv)).resolves.toBeUndefined()
      expect(console.error).toHaveBeenCalledWith(expect.stringContaining('Failed to notify Coday'), expect.any(Error))
    })
  })
})
