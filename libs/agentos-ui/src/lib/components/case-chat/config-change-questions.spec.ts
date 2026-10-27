import { CaseEvent } from '@whoz-oss/agentos-api-client'
import { CONFIG_CHANGE_PLAN_SCHEMA, configChangeQuestionIds } from './config-change-questions'

describe('configChangeQuestionIds', () => {
  const response = (structuredOutput: unknown, toolName = 'AGENTOS_CONFIG__propose') =>
    ({ type: 'ToolResponseEvent', toolName, structuredOutput }) as unknown as CaseEvent

  it('collects the question of every tool response carrying a configuration plan', () => {
    const ids = configChangeQuestionIds([
      response({ schema: CONFIG_CHANGE_PLAN_SCHEMA, questionId: 'q-1' }),
      response({ schema: CONFIG_CHANGE_PLAN_SCHEMA, questionId: 'q-2' }, 'renamed__propose'),
    ])

    expect([...ids]).toEqual(['q-1', 'q-2'])
  })

  it('ignores a structured output that is not a plan, whatever the tool name', () => {
    const ids = configChangeQuestionIds([
      response({ questionId: 'q-1' }),
      response({ schema: 'other/1', questionId: 'q-2' }),
      response(null),
      { type: 'MessageEvent' } as unknown as CaseEvent,
    ])

    expect(ids.size).toBe(0)
  })
})
