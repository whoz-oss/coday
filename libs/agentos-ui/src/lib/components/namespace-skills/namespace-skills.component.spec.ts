import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfig, Skill } from '@whoz-oss/agentos-api-client'
import { defer, of, throwError } from 'rxjs'
import { AgentStateService } from '../../services/agent-state.service'
import { SkillStateService } from '../../services/skill-state.service'
import { NamespaceSkillsComponent } from './namespace-skills.component'

/**
 * Focused on what the list adds over a plain listing: the count of agents naming a skill, which
 * the row shows to say what a deletion would break, which rows may be modified, and the failure
 * path of a deletion, which used to be swallowed.
 */
describe('NamespaceSkillsComponent', () => {
  let skillState: { listPlatform: jest.Mock; listByNamespace: jest.Mock; delete: jest.Mock }
  let agentState: { listByNamespace: jest.Mock; listPlatform: jest.Mock }
  let fixture: ComponentFixture<NamespaceSkillsComponent>

  const skill = (name: string, overrides: Partial<Skill> = {}): Skill =>
    ({ id: `skill-${name}`, name, description: `${name} d`, body: '', readOnly: false, ...overrides }) as Skill

  const agent = (name: string, skillSelectors?: string[]): AgentConfig => ({ name, skillSelectors }) as AgentConfig

  interface Row {
    key: string
    skill: Skill
    scope: 'platform' | 'namespace'
    namedByAgents: number
  }

  type Internals = {
    rows: () => Row[]
    isMutable: (row: Row) => boolean
    confirmDelete: (row: Row) => void
    deletion: {
      request: (id: string) => void
      error: () => string | null
      confirmingId: () => string | null
    }
  }

  /**
   * The component subscribes from its constructor, so the stubs have to be final before it
   * is created: what each test varies goes through here, not through a later mockReturnValue.
   */
  function setUp(
    options: {
      agents?: AgentConfig[]
      platformAgents?: AgentConfig[]
      platformSkills?: Skill[]
      namespaceSkills?: Skill[]
      agentsFail?: boolean
    } = {}
  ): Internals {
    skillState = {
      listPlatform: jest.fn().mockReturnValue(of(options.platformSkills ?? [])),
      listByNamespace: jest.fn().mockReturnValue(of(options.namespaceSkills ?? [skill('deploy'), skill('migrate')])),
      delete: jest.fn().mockReturnValue(of({})),
    }
    agentState = {
      listByNamespace: jest
        .fn()
        .mockReturnValue(options.agentsFail ? throwError(() => new Error('boom')) : of(options.agents ?? [])),
      listPlatform: jest.fn().mockReturnValue(of(options.platformAgents ?? [])),
    }
    TestBed.configureTestingModule({
      imports: [NamespaceSkillsComponent],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { params: { namespaceId: 'ns-1' } } } },
        { provide: Router, useValue: { navigate: jest.fn() } },
        { provide: SkillStateService, useValue: skillState },
        { provide: AgentStateService, useValue: agentState },
      ],
    })
    fixture = TestBed.createComponent(NamespaceSkillsComponent)
    return fixture.componentInstance as unknown as Internals
  }

  const rowOf = (component: Internals, name: string): Row => component.rows().find((r) => r.skill.name === name)!

  describe('agents naming a skill', () => {
    it('counts the agents that name it, whatever the case or path form of the selector', () => {
      const component = setUp({ agents: [agent('a', ['Deploy']), agent('b', ['deploy/SKILL.md'])] })

      expect(rowOf(component, 'deploy').namedByAgents).toBe(2)
      expect(rowOf(component, 'migrate').namedByAgents).toBe(0)
    })

    it('does not count an agent granted every skill, which keeps working without this one', () => {
      const component = setUp({ agents: [agent('wildcard', ['*'])] })

      expect(rowOf(component, 'deploy').namedByAgents).toBe(0)
    })

    it('counts platform agents too, since they deploy into the namespace', () => {
      const component = setUp({ platformAgents: [agent('shared', ['deploy'])] })

      expect(rowOf(component, 'deploy').namedByAgents).toBe(1)
    })

    it('renders the list without counts when the agents cannot be read', () => {
      const component = setUp({ agentsFail: true })

      expect(rowOf(component, 'deploy').namedByAgents).toBe(0)
      expect(skillState.listByNamespace).toHaveBeenCalled()
    })
  })

  it('loads each list once', () => {
    setUp({ agents: [agent('a', ['deploy'])] })

    fixture.detectChanges()

    expect(skillState.listByNamespace).toHaveBeenCalledTimes(1)
    expect(skillState.listPlatform).toHaveBeenCalledTimes(1)
    expect(agentState.listByNamespace).toHaveBeenCalledTimes(1)
  })

  it('lists the platform skills first, flagged by scope', () => {
    const component = setUp({ platformSkills: [skill('shared', { id: 'p-1' })] })

    expect(component.rows().map((r) => `${r.scope}:${r.skill.name}`)).toEqual([
      'platform:shared',
      'namespace:deploy',
      'namespace:migrate',
    ])
  })

  describe('which rows can be modified', () => {
    it('allows a namespace skill', () => {
      const component = setUp()

      expect(component.isMutable(rowOf(component, 'deploy'))).toBe(true)
    })

    it('refuses a platform skill, whatever its storage', () => {
      const component = setUp({ platformSkills: [skill('shared', { id: 'p-1' })] })

      expect(component.isMutable(rowOf(component, 'shared'))).toBe(false)
    })

    it('refuses a skill defined by a SKILL.md file', () => {
      const component = setUp({ namespaceSkills: [skill('fs', { readOnly: true })] })

      expect(component.isMutable(rowOf(component, 'fs'))).toBe(false)
    })
  })

  describe('deletion', () => {
    it('deletes only once the inline confirmation was requested', () => {
      const component = setUp()
      // The request is built eagerly by the component, so what matters is whether it is subscribed to.
      const sent = jest.fn()
      skillState.delete.mockImplementation(() =>
        defer(() => {
          sent()
          return of({})
        })
      )

      component.confirmDelete(rowOf(component, 'deploy'))
      expect(sent).not.toHaveBeenCalled()

      component.deletion.request(rowOf(component, 'deploy').key)
      component.confirmDelete(rowOf(component, 'deploy'))
      expect(sent).toHaveBeenCalledTimes(1)
      expect(skillState.delete).toHaveBeenLastCalledWith('skill-deploy')
    })

    it('never deletes a read-only skill', () => {
      const component = setUp({ namespaceSkills: [skill('fs', { readOnly: true })] })
      const row = rowOf(component, 'fs')

      component.deletion.request(row.key)
      component.confirmDelete(row)

      expect(skillState.delete).not.toHaveBeenCalled()
    })

    it('surfaces the filesystem refusal instead of swallowing it', () => {
      const component = setUp()
      skillState.delete.mockReturnValue(throwError(() => ({ status: 400 })))
      const row = rowOf(component, 'deploy')

      component.deletion.request(row.key)
      component.confirmDelete(row)

      expect(component.deletion.error()).toContain('SKILL.md')
    })

    it('surfaces any other failure', () => {
      const component = setUp()
      skillState.delete.mockReturnValue(throwError(() => ({ status: 500 })))
      const row = rowOf(component, 'deploy')

      component.deletion.request(row.key)
      component.confirmDelete(row)

      expect(component.deletion.error()).toBe('Deleting "deploy" failed.')
    })

    it('says how many agents still name a skill whose deletion failed', () => {
      const component = setUp({ agents: [agent('a', ['deploy'])] })
      skillState.delete.mockReturnValue(throwError(() => ({ status: 500 })))
      const row = rowOf(component, 'deploy')

      component.deletion.request(row.key)
      component.confirmDelete(row)

      expect(component.deletion.error()).toContain('1 agent(s) still name it')
    })

    it('clears a previous failure on the next attempt', () => {
      const component = setUp()
      const row = rowOf(component, 'deploy')
      skillState.delete.mockReturnValue(throwError(() => ({ status: 500 })))
      component.deletion.request(row.key)
      component.confirmDelete(row)

      skillState.delete.mockReturnValue(of({}))
      component.deletion.request(row.key)

      expect(component.deletion.error()).toBeNull()
    })
  })
})
