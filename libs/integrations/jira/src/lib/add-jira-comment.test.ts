import { addJiraComment } from './add-jira-comment'
import { Interactor } from '@coday/model/interactor'

// Mock fetch globally
global.fetch = jest.fn()

describe('addJiraComment', () => {
  let mockInteractor: jest.Mocked<Interactor>
  const mockFetch = fetch as jest.MockedFunction<typeof fetch>

  beforeEach(() => {
    mockInteractor = {
      displayText: jest.fn(),
      error: jest.fn(),
    } as any
    mockFetch.mockClear()
  })

  it('should add a public comment successfully', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      text: jest.fn().mockResolvedValue('Success'),
    } as any)

    await addJiraComment(
      'TEST-123',
      'This is a public comment',
      'https://company.atlassian.net',
      'api-token',
      'username',
      mockInteractor
    )

    expect(mockFetch).toHaveBeenCalledWith('https://company.atlassian.net/rest/api/2/issue/TEST-123/comment', {
      method: 'POST',
      headers: {
        Authorization: 'Basic dXNlcm5hbWU6YXBpLXRva2Vu',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        body: 'This is a public comment',
      }),
    })

    expect(mockInteractor.displayText).toHaveBeenCalledWith('Successfully added comment to Jira ticket TEST-123')
  })

  it('should not include internal properties in public comments', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      text: jest.fn().mockResolvedValue('Success'),
    } as any)

    await addJiraComment(
      'TEST-456',
      'Public comment content',
      'https://company.atlassian.net',
      'api-token',
      'username',
      mockInteractor
    )

    const callArgs = mockFetch.mock.calls[0]?.[1]
    expect(callArgs).toBeDefined()
    const body = JSON.parse(callArgs?.body as string)

    expect(body.properties).toBeUndefined()
    expect(body.body).toBe('Public comment content')
  })

  it('should handle API errors gracefully', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      text: jest.fn().mockResolvedValue('Unauthorized'),
    } as any)

    await expect(
      addJiraComment(
        'TEST-123',
        'This comment will fail',
        'https://company.atlassian.net',
        'invalid-token',
        'username',
        mockInteractor
      )
    ).rejects.toThrow('Failed to add comment to Jira ticket TEST-123: Unauthorized')

    expect(mockInteractor.error).toHaveBeenCalled()
  })
})
