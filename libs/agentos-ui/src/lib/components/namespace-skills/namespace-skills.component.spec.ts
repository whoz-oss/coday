import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfig, Skill } from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { AgentStateService } from '../../services/agent-state.service'
import { SkillStateService } from '../../services/skill-state.service'
import { NamespaceSkillsComponent } from './namespace-skills.component'

/**
 * Focused on what the list adds over a plain listing: the count of agents naming a skill,
 * which the deletion prompt uses to say what it would break, and the failure path of a
 * deletion, which used to be swallowed.
 */
describe('NamespaceSkillsComponent', () => {
  let skillState: { listPlatform: jest.Mock; listByNamespace: jest.Mock; delete: jest.Mock }
  let agentState: { listByNamespace: jest.Mock; listPlatform: jest.Mock }
  let fixture: ComponentFixture<NamespaceSkillsComponent>

  const skill = (name: string, overrides: Partial<Skill> = {}): Skill =>
    ({ id: `skill-${name}`, name, description: `${name} d`, body: '', readOnly: false, ...overrides }) as Skill

  const agent = (name: string, skillSelectors?: string[]): AgentConfig => ({ name, skillSelectors }) as AgentConfig

  type Internals = {
    namedByAgents: (skill: Skill) => number
    deleteSkill: (skill: Skill) => void
    errorMessage: () => string | null
  }

  /**
   * The component subscribes from its constructor, so the stubs have to be final before it
   * is created: what each test varies goes through here, not through a later mockReturnValue.
   */
  function setUp(
    options: { agents?: AgentConfig[]; platformAgents?: AgentConfig[]; agentsFail?: boolean } = {}
  ): Internals {
    skillState = {
      listPlatform: jest.fn().mockReturnValue(of([])),
      listByNamespace: jest.fn().mockReturnValue(of([skill('deploy'), skill('migrate')])),
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

  describe('agents naming a skill', () => {
    it('counts the agents that name it, whatever the case or path form of the selector', () => {
      const component = setUp({ agents: [agent('a', ['Deploy']), agent('b', ['deploy/SKILL.md'])] })

      expect(component.namedByAgents(skill('deploy'))).toBe(2)
      expect(component.namedByAgents(skill('migrate'))).toBe(0)
    })

    it('does not count an agent granted every skill, which keeps working without this one', () => {
      const component = setUp({ agents: [agent('wildcard', ['*'])] })

      expect(component.namedByAgents(skill('deploy'))).toBe(0)
    })

    it('counts platform agents too, since they deploy into the namespace', () => {
      const component = setUp({ platformAgents: [agent('shared', ['deploy'])] })

      expect(component.namedByAgents(skill('deploy'))).toBe(1)
    })

    it('renders the list without counts when the agents cannot be read', () => {
      const component = setUp({ agentsFail: true })

      expect(component.namedByAgents(skill('deploy'))).toBe(0)
      expect(skillState.listByNamespace).toHaveBeenCalled()
    })
  })

  it('loads each list once, although the template and the class both subscribe', () => {
    // The class subscribes to keep the full objects the item template resolves by id, the
    // async pipe subscribes to render the cards: without sharing, every load went out twice.
    setUp({ agents: [agent('a', ['deploy'])] })

    fixture.detectChanges()

    expect(skillState.listByNamespace).toHaveBeenCalledTimes(1)
    expect(skillState.listPlatform).toHaveBeenCalledTimes(1)
    expect(agentState.listByNamespace).toHaveBeenCalledTimes(1)
  })

  describe('deletion', () => {
    it('surfaces the filesystem refusal instead of swallowing it', () => {
      const component = setUp()
      skillState.delete.mockReturnValue(throwError(() => ({ status: 400 })))

      component.deleteSkill(skill('deploy'))

      expect(component.errorMessage()).toContain('SKILL.md')
    })

    it('surfaces any other failure', () => {
      const component = setUp()
      skillState.delete.mockReturnValue(throwError(() => ({ status: 500 })))

      component.deleteSkill(skill('deploy'))

      expect(component.errorMessage()).toBe('Deleting "deploy" failed.')
    })

    it('clears a previous failure on the next attempt', () => {
      const component = setUp()
      skillState.delete.mockReturnValue(throwError(() => ({ status: 500 })))
      component.deleteSkill(skill('deploy'))

      skillState.delete.mockReturnValue(of({}))
      component.deleteSkill(skill('deploy'))

      expect(component.errorMessage()).toBeNull()
    })
  })
})
