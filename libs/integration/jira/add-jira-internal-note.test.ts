import { addJiraInternalNote } from './add-jira-internal-note'
import { Interactor } from '../../model'

// Mock fetch globally
global.fetch = jest.fn()

describe('addJiraInternalNote', () => {
  let mockInteractor: jest.Mocked<Interactor>
  const mockFetch = fetch as jest.MockedFunction<typeof fetch>

  beforeEach(() => {
    mockInteractor = {
      displayText: jest.fn(),
      error: jest.fn(),
    } as any
    mockFetch.mockClear()
  })

  it('should add an internal note successfully', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      text: jest.fn().mockResolvedValue('Success'),
    } as any)

    await addJiraInternalNote(
      'TEST-123',
      'This is an internal note',
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
        body: 'This is an internal note',
        properties: [
          {
            key: 'sd.public.comment',
            value: {
              internal: true,
            },
          },
        ],
      }),
    })

    expect(mockInteractor.displayText).toHaveBeenCalledWith('Successfully added internal note to Jira ticket TEST-123')
  })

  it('should handle API errors gracefully', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      text: jest.fn().mockResolvedValue('Unauthorized'),
    } as any)

    await expect(
      addJiraInternalNote(
        'TEST-123',
        'This note will fail',
        'https://company.atlassian.net',
        'invalid-token',
        'username',
        mockInteractor
      )
    ).rejects.toThrow('Failed to add internal note to Jira ticket TEST-123: Unauthorized')

    expect(mockInteractor.error).toHaveBeenCalled()
  })

  it('should include internal property in the request', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      text: jest.fn().mockResolvedValue('Success'),
    } as any)

    await addJiraInternalNote(
      'TEST-456',
      'Internal note content',
      'https://company.atlassian.net',
      'api-token',
      'username',
      mockInteractor
    )

    const callArgs = mockFetch.mock.calls[0]?.[1]
    expect(callArgs).toBeDefined()
    const body = JSON.parse(callArgs?.body as string)

    expect(body.properties).toBeDefined()
    expect(body.properties[0].key).toBe('sd.public.comment')
    expect(body.properties[0].value.internal).toBe(true)
  })
})
