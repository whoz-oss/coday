import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfig, Skill } from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { AgentStateService } from '../../services/agent-state.service'
import { SkillStateService } from '../../services/skill-state.service'
import { SkillInspectComponent } from './skill-inspect.component'

/**
 * Focused on the "Used by" list: no endpoint queries agents by selector, so the component
 * derives it from the namespace's agent configs. What matters is that a named grant and a
 * `*` grant are told apart, and that a failure reads as unknown rather than as none.
 */
describe('SkillInspectComponent (used by)', () => {
  let skillState: { getById: jest.Mock }
  let agentState: { listByNamespace: jest.Mock; listPlatform: jest.Mock }
  let fixture: ComponentFixture<SkillInspectComponent>

  const skill = { id: 'skill-1', name: 'deploy', description: 'd', body: '# deploy', readOnly: false } as Skill

  const agent = (name: string, skillSelectors?: string[]): AgentConfig => ({ name, skillSelectors }) as AgentConfig

  type Internals = {
    ngOnInit: () => void
    consumers: () => Array<{ name: string; grant: string; platform: boolean }>
    consumersUnavailable: () => boolean
    consumersLoading: () => boolean
  }

  // `null` means platform scope: a default parameter would swallow an explicit `undefined`.
  function setUp(namespaceId: string | null = 'ns-1'): Internals {
    skillState = { getById: jest.fn().mockReturnValue(of(skill)) }
    agentState = {
      listByNamespace: jest.fn().mockReturnValue(of([])),
      listPlatform: jest.fn().mockReturnValue(of([])),
    }
    TestBed.configureTestingModule({
      imports: [SkillInspectComponent],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { params: namespaceId ? { namespaceId, skillId: 'skill-1' } : { skillId: 'skill-1' } },
          },
        },
        { provide: Router, useValue: { navigate: jest.fn() } },
        { provide: SkillStateService, useValue: skillState },
        { provide: AgentStateService, useValue: agentState },
      ],
    })
    fixture = TestBed.createComponent(SkillInspectComponent)
    return fixture.componentInstance as unknown as Internals
  }

  it('lists the agents that name the skill, and those granted every skill, named ones first', () => {
    const component = setUp()
    agentState.listByNamespace.mockReturnValue(of([agent('wildcard', ['*']), agent('namer', ['deploy'])]))

    component.ngOnInit()

    expect(component.consumers()).toEqual([
      { name: 'namer', grant: 'named', platform: false },
      { name: 'wildcard', grant: 'all', platform: false },
    ])
  })

  it('marks a platform agent as such', () => {
    const component = setUp()
    agentState.listPlatform.mockReturnValue(of([agent('shared-agent', ['deploy'])]))

    component.ngOnInit()

    expect(component.consumers()).toEqual([{ name: 'shared-agent', grant: 'named', platform: true }])
  })

  it('leaves out an agent whose selectors do not reach the skill', () => {
    const component = setUp()
    agentState.listByNamespace.mockReturnValue(of([agent('other', ['migrate']), agent('none')]))

    component.ngOnInit()

    expect(component.consumers()).toEqual([])
    expect(component.consumersUnavailable()).toBe(false)
  })

  it('reports usage as unknown when the agent configs cannot be read', () => {
    // An empty list would read as "safe to delete"; a failure must not look like that.
    const component = setUp()
    agentState.listByNamespace.mockReturnValue(throwError(() => new Error('boom')))

    component.ngOnInit()

    expect(component.consumersUnavailable()).toBe(true)
    expect(component.consumersLoading()).toBe(false)
  })

  it('still lists the namespace agents when only the platform call fails', () => {
    const component = setUp()
    agentState.listByNamespace.mockReturnValue(of([agent('namer', ['deploy'])]))
    agentState.listPlatform.mockReturnValue(throwError(() => new Error('boom')))

    component.ngOnInit()

    expect(component.consumers()).toEqual([{ name: 'namer', grant: 'named', platform: false }])
    expect(component.consumersUnavailable()).toBe(false)
  })

  it('asks for no agent at platform scope, where consumers span every namespace', () => {
    const component = setUp(null)

    component.ngOnInit()

    expect(agentState.listByNamespace).not.toHaveBeenCalled()
    expect(component.consumers()).toEqual([])
  })
})
