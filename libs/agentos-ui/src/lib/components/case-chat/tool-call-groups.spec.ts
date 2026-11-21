import { ToolResponseEvent } from '@whoz-oss/agentos-api-client'
import { TimelineItem, ToolCall } from './case-chat.models'
import { groupToolCalls, summarizeToolGroup } from './tool-call-groups'

const call = (
  requestId: string,
  toolName = 'FILES__readFile',
  done?: boolean,
  args: string | null = null
): ToolCall => ({
  requestId,
  toolName,
  args,
  response: done === undefined ? undefined : ({ success: done } as ToolResponseEvent),
})

const tool = (requestId: string, toolName?: string, done?: boolean, args?: string | null): TimelineItem => ({
  kind: 'tool',
  call: call(requestId, toolName, done, args),
})

const notice: TimelineItem = { kind: 'notice', notice: { severity: 'warning', title: 'w' }, eventId: 'n-1' }
const delegation = { kind: 'delegation', delegation: { delegationId: 'd-1' } } as unknown as TimelineItem
const message = { kind: 'message', event: { id: 'm-1' } } as unknown as TimelineItem

describe('groupToolCalls', () => {
  it('leaves a lone tool call untouched', () => {
    const items = [message, tool('t-1'), message]
    expect(groupToolCalls(items)).toEqual(items)
  })

  it('replaces a run of consecutive tool calls with one group', () => {
    const result = groupToolCalls([message, tool('t-1'), tool('t-2'), tool('t-3')])

    expect(result).toHaveLength(2)
    expect(result[1]).toEqual({
      kind: 'toolGroup',
      groupId: 'group-t-1',
      calls: [call('t-1'), call('t-2'), call('t-3')],
    })
  })

  it('is broken by messages, delegations and notices into separate runs', () => {
    const result = groupToolCalls([
      tool('t-1'),
      tool('t-2'),
      message,
      tool('t-3'),
      delegation,
      tool('t-4'),
      tool('t-5'),
      notice,
      tool('t-6'),
    ])

    expect(result.map((item) => item.kind)).toEqual([
      'toolGroup',
      'message',
      'tool',
      'delegation',
      'toolGroup',
      'notice',
      'tool',
    ])
  })

  it('keeps the group id stable while the run grows', () => {
    const two = groupToolCalls([tool('t-1'), tool('t-2')])[0]
    const four = groupToolCalls([tool('t-1'), tool('t-2'), tool('t-3'), tool('t-4')])[0]

    expect(two?.kind === 'toolGroup' && two.groupId).toBe('group-t-1')
    expect(four?.kind === 'toolGroup' && four.groupId).toBe('group-t-1')
  })

  it('returns an empty list for an empty timeline', () => {
    expect(groupToolCalls([])).toEqual([])
  })
})

describe('summarizeToolGroup', () => {
  it('names the live step, the last pending call, while something runs', () => {
    const summary = summarizeToolGroup([
      call('t-1', 'FILES__readFile', true, '{"filePath":"a.ts"}'),
      call('t-2', 'FILES__readFile', undefined, '{"filePath":"b.ts"}'),
      call('t-3', 'FILES__readFile', undefined, '{"filePath":"c.ts"}'),
    ])

    expect(summary).toEqual({
      total: 3,
      pending: 2,
      failed: 0,
      succeeded: 1,
      label: 'Reading c.ts',
      detail: '',
    })
  })

  it('says how many tools were used once everything completed', () => {
    const summary = summarizeToolGroup([
      call('t-1', 'FILES__readFile', true, '{"filePath":"a.ts"}'),
      call('t-2', 'FILES__listFiles', true),
    ])

    expect(summary.label).toBe('Used 2 tools')
    expect(summary.pending).toBe(0)
    expect(summary.succeeded).toBe(2)
    expect(summary.detail).toBe('Reading a.ts · Listing files')
  })

  it('surfaces failures', () => {
    const summary = summarizeToolGroup([call('t-1', 'FILES__readFile', true), call('t-2', 'FILES__readFile', false)])

    expect(summary.failed).toBe(1)
    expect(summary.succeeded).toBe(1)
  })

  it('lists distinct labels only, the first three, and counts the rest', () => {
    const summary = summarizeToolGroup([
      call('t-1', 'FILES__readFile', true, '{"filePath":"a.ts"}'),
      call('t-2', 'FILES__readFile', true, '{"filePath":"a.ts"}'),
      call('t-3', 'FILES__listFiles', true),
      call('t-4', 'GIT__gitStatus', true),
      call('t-5', 'FILES__remove', true),
      call('t-6', 'GIT__gitFetch', true),
    ])

    expect(summary.label).toBe('Used 6 tools')
    expect(summary.detail).toBe('Reading a.ts · Listing files · Checking the Git status · +2 more')
  })

  it('uses the singular for one tool', () => {
    expect(summarizeToolGroup([call('t-1', 'FILES__readFile', true)]).label).toBe('Used 1 tool')
  })
})
