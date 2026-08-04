import { AgentConfig } from '@whoz-oss/agentos-api-client'
import { filterAndSortAgents } from './agent.utils'

const agent = (name: string, description?: string): AgentConfig => ({ name, description }) as AgentConfig

describe('agent.utils', () => {
  describe('filterAndSortAgents', () => {
    describe('empty prefix', () => {
      it('returns all agents when prefix is empty', () => {
        const agents = [agent('Alice'), agent('Bob'), agent('Charlie')]
        expect(filterAndSortAgents(agents, '')).toEqual(agents)
      })

      it('returns an empty array when the agent list is empty', () => {
        expect(filterAndSortAgents([], '')).toEqual([])
      })
    })

    describe('name matching', () => {
      it('returns agents whose name starts with the prefix', () => {
        const agents = [agent('Alice'), agent('Bob'), agent('Alfred')]
        const result = filterAndSortAgents(agents, 'al')
        expect(result).toEqual([agent('Alice'), agent('Alfred')])
      })

      it('is case-insensitive on the prefix', () => {
        const agents = [agent('Alice'), agent('Bob')]
        expect(filterAndSortAgents(agents, 'AL')).toEqual([agent('Alice')])
        expect(filterAndSortAgents(agents, 'Al')).toEqual([agent('Alice')])
        expect(filterAndSortAgents(agents, 'al')).toEqual([agent('Alice')])
      })

      it('is case-insensitive on the agent name', () => {
        const agents = [agent('ALICE'), agent('Bob')]
        expect(filterAndSortAgents(agents, 'alice')).toEqual([agent('ALICE')])
      })
    })

    describe('description matching', () => {
      it('returns agents whose description contains the prefix when name does not match', () => {
        const agents = [agent('Bob', 'Handles alice workflows'), agent('Charlie', 'Unrelated')]
        const result = filterAndSortAgents(agents, 'alice')
        expect(result).toEqual([agent('Bob', 'Handles alice workflows')])
      })

      it('is case-insensitive on the description', () => {
        const agents = [agent('Bob', 'Handles ALICE workflows')]
        expect(filterAndSortAgents(agents, 'alice')).toEqual([agent('Bob', 'Handles ALICE workflows')])
      })

      it('does not crash when description is undefined', () => {
        const agents = [agent('Bob')]
        expect(() => filterAndSortAgents(agents, 'alice')).not.toThrow()
        expect(filterAndSortAgents(agents, 'alice')).toEqual([])
      })
    })

    describe('ordering: name matches first, then description matches', () => {
      it('puts name-matching agents before description-matching agents', () => {
        const nameMatch = agent('Alice', 'some description')
        const descMatch = agent('Bob', 'alice helper')
        const noMatch = agent('Charlie', 'unrelated')

        const result = filterAndSortAgents([descMatch, noMatch, nameMatch], 'alice')
        expect(result).toEqual([nameMatch, descMatch])
      })

      it('does not include an agent in both groups (name match wins over description match)', () => {
        const both = agent('Alice', 'alice-based workflows')
        const result = filterAndSortAgents([both], 'alice')
        expect(result).toEqual([both])
        expect(result).toHaveLength(1)
      })
    })

    describe('no match', () => {
      it('returns an empty array when no agent matches the prefix', () => {
        const agents = [agent('Alice'), agent('Bob', 'some description')]
        expect(filterAndSortAgents(agents, 'zzz')).toEqual([])
      })
    })
  })
})
